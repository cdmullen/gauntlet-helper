/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, cdmullen
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.gauntlethelper.record;

import com.gauntlethelper.GauntletHelperPlugin;
import com.gauntlethelper.config.ArmorGoal;
import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.game.GauntletIds;
import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.guidance.SceneGeometry;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.overlay.GauntletTimeLeft;
import com.gauntlethelper.plan.GroundLoot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLiteProperties;

/**
 * What the Log attempts setting records each tick:
 * when a recording starts and stops, its header, and the frame of raw observations plus the guidance chosen. Built
 * per tick from the plugin's own client, config and scene, after its observation; reads state only. AttemptRecorder
 * writes the frames (bump AttemptRecorder.FORMAT when a field's meaning changes).
 */
public final class AttemptCapture
{
	/** The end line's "result": Hunllef's death was seen, the player died, or neither before the recording ended. */
	public static final String RESULT_WON = "won";
	public static final String RESULT_DIED = "died";
	public static final String RESULT_LEFT = "left";
	private static final int NO_ANIMATION = -1;

	private final GauntletHelperPlugin plugin;
	private final Client client;
	private final GauntletHelperConfig config;
	private final Guidance guidance;

	public AttemptCapture(GauntletHelperPlugin plugin, Client client, GauntletHelperConfig config, Guidance guidance)
	{
		this.plugin = plugin;
		this.client = client;
		this.config = config;
		this.guidance = guidance;
	}

	/** Setting Log attempts: one recorder line per tick of changed observations and guidance. */
	public void record(AttemptRecorder recorder)
	{
		if (recorder == null)
			return;
		int tick = client.getTickCount();
		if (!config.logAttempts())
		{
			recorder.stop(tick, "setting turned off");
			return;
		}
		if (!plugin.isInPrep() && !plugin.isInBossFight())
		{
			recorder.stop(tick, "left CG");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
			return;
		if (!recorder.isRecording())
		{
			// A recording starts during preparation only, never part-way through the boss.
			if (!plugin.isInPrep())
				return;
			int seconds = plugin.getSecondsRemaining();
			GauntletMode mode = plugin.getMode();
			ArmorGoal goal = config.armorGoal();
			String folder = AttemptRecorder.getAttemptFolder(mode != GauntletMode.REGULAR, (goal == null ? ArmorGoal.T1 : goal).name());
			recorder.setKeepRecordings(config.keepRecordings());
			recorder.start(tick, seconds > 0 ? "preparation, timer " + seconds + "s" : "preparation", buildHeader(), folder);
			recorder.setResult(RESULT_LEFT);
		}
		try
		{
			recorder.record(buildFrame(tick));
			if (plugin.isInBossFight() && isHunllefDeathShown(client.getTopLevelWorldView()))
				recorder.setResult(RESULT_WON);
		}
		catch (RuntimeException e)
		{
			// Diagnostics must never affect guidance: give up on this recording instead.
			recorder.recordEvent("capture failed: " + e);
			recorder.stop(tick, "capture failed");
		}
	}

	private Map<String, Object> buildHeader()
	{
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("runelite", RuneLiteProperties.getVersion());
		header.put("mode", plugin.getMode().name());
		// Every setting in ConfigManager's saved-string form, so the recording shows the configuration it ran with.
		header.put("config", AttemptFrame.readConfigValues(config));
		return header;
	}

	/**
	 * Raw observations plus the guidance this tick chose. Reads state only. Everything the planner reads from the
	 * client is here; the guidance fields are what it chose. The parts
	 * are added in a fixed order, which is the order of the recorded fields.
	 */
	public AttemptFrame buildFrame(int tick)
	{
		AttemptFrame frame = new AttemptFrame(tick);
		frame.put("phase", plugin.isInBossFight() ? "Hunllef" : plugin.getPhaseLabel());
		Player player = client.getLocalPlayer();
		putPlayer(frame, player);
		putMap(frame);
		WorldView view = client.getTopLevelWorldView();
		putScene(frame, view);
		putObjects(frame);
		putNpcs(frame, view, player);
		putGround(frame, view);
		putGuidance(frame);
		return frame;
	}

	private void putPlayer(AttemptFrame frame, Player player)
	{
		WorldPoint at = player == null ? null : player.getWorldLocation();
		frame.put("player", at == null ? null : AttemptFrame.ints(at.getX(), at.getY(), at.getPlane()));
		// Routes start from the drawn position, which trails the server tile while walking.
		LocalPoint local = player == null ? null : player.getLocalLocation();
		frame.put("local", local == null ? null : AttemptFrame.ints(local.getX(), local.getY()));
		frame.put("anim", player == null || player.getAnimation() == NO_ANIMATION ? null : player.getAnimation());
		frame.put("hp", client.getBoostedSkillLevel(Skill.HITPOINTS));
		frame.put("timer", GauntletTimeLeft.read(client));
		frame.put("secs", plugin.getSecondsRemaining());
	}

	/** The map varbits, discovered rooms, current room and the held and worn items. */
	private void putMap(AttemptFrame frame)
	{
		frame.put("mapVarbits", AttemptFrame.ints(client.getVarbitValue(VarbitID.GAUNTLET_START),
				client.getVarbitValue(VarbitID.GAUNTLET_CURRENT_ROOM_X),
				client.getVarbitValue(VarbitID.GAUNTLET_CURRENT_ROOM_Z)));
		long found = 0;
		for (int i = 0; i < 49; i++)
		{
			if (client.getVarbitValue(VarbitID.GAUNTLET_ROOM_0_FOUND + i) == 1)
				found |= 1L << i;
		}
		frame.put("roomsFound", Long.toHexString(found));
		GauntletRoom room = guidance.getCurrentMapRoom();
		frame.put("room", room == null ? null : room.getColumn() + "," + room.getRow());
		frame.put("home", guidance.isAtHome());
		frame.put("inv", readSlots(client.getItemContainer(InventoryID.INV)));
		frame.put("worn", readSlots(client.getItemContainer(InventoryID.WORN)));
	}

	/** Scene base and size, collision, and loaded tiles. */
	private static void putScene(AttemptFrame frame, WorldView view)
	{
		frame.put("base", view == null ? null : AttemptFrame.ints(view.getBaseX(), view.getBaseY(), view.getPlane(), view.getSizeX(), view.getSizeY()));
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		frame.put("collision", maps == null || view.getPlane() >= maps.length || maps[view.getPlane()] == null ? null
				: AttemptFrame.encodeRunLength(maps[view.getPlane()].getFlags()));
		frame.put("tiles", view == null || view.getScene() == null ? null : encodeLoadedTiles(view));
	}

	private void putObjects(AttemptFrame frame)
	{
		List<String> objects = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.getObjects().entrySet())
		{
			objects.add(buildObjectRecord(entry.getKey(), entry.getValue()));
		}
		// Kept outside the route set by Guidance, but still read by the sceptre and barrier cues.
		if (guidance.getToolStorage() != null)
			objects.add(buildObjectRecord(guidance.getToolStorage(), ObjectKind.TOOLS));
		for (TileObject barrier : guidance.getBarriers())
		{
			objects.add(buildObjectRecord(barrier, ObjectKind.BARRIER));
		}
		frame.set("obj", objects);
	}

	private static void putNpcs(AttemptFrame frame, WorldView view, Player player)
	{
		List<String> npcs = new ArrayList<>();
		List<Integer> foes = new ArrayList<>();
		if (view != null && view.npcs() != null)
		{
			for (NPC npc : view.npcs())
			{
				if (player != null && npc.getInteracting() == player)
					foes.add(npc.getIndex());
				LocalPoint drawn = npc.getLocalLocation();
				// Routing reads the world area, so its width is the size (getFootprintSize is not in tiles).
				WorldArea area = npc.getWorldArea();
				int size = area != null ? Math.max(area.getWidth(), area.getHeight()) : Math.max(1, Math.round(npc.getFootprintSize() / 51.2f));
				npcs.add(npc.getId() + "#" + npc.getIndex() + "@" + formatPoint(npc.getWorldLocation()) + " s="
						+ size + (drawn == null ? "" : " l=" + drawn.getX() + "," + drawn.getY())
						+ (npc.isDead() ? " dead" : ""));
			}
		}
		frame.set("npc", npcs);
		// Who the player fights keeps the Defeat objective on that enemy.
		Actor fighting = player == null ? null : player.getInteracting();
		frame.put("fight", fighting instanceof NPC ? ((NPC) fighting).getIndex() : null);
		frame.put("foes", foes.isEmpty() ? null : foes);
	}

	/** Raw ground items, then the piles the plugin remembers. */
	private void putGround(AttemptFrame frame, WorldView view)
	{
		List<String> ground = new ArrayList<>();
		if (view != null && view.getScene() != null)
		{
			for (Tile[] column : view.getScene().getTiles()[view.getPlane()])
			{
				for (Tile tile : column)
				{
					List<TileItem> items = tile == null ? null : tile.getGroundItems();
					if (items == null)
						continue;
					for (TileItem item : items)
					{
						ground.add(item.getId() + "x" + item.getQuantity() + "@" + formatPoint(tile.getWorldLocation()) + " exp=" + item.getDespawnTime());
					}
				}
			}
		}
		frame.set("ground", ground);
		List<String> piles = new ArrayList<>();
		for (GroundLoot.Entry entry : plugin.getLoot().getEntries())
		{
			piles.add(entry.id + "x" + entry.quantity + "@" + formatPoint(entry.point) + " exp=" + entry.expires + (entry.home ? " home" : ""));
		}
		frame.set("loot", piles);
	}

	/** The guidance this tick chose: instructions, teleport, inventory cues and the scene's own fields. */
	private void putGuidance(AttemptFrame frame)
	{
		boolean inPrep = plugin.isInPrep();
		frame.put("now", inPrep ? plugin.getInstruction() : null);
		frame.put("next", inPrep ? plugin.getSecondaryInstruction() : null);
		frame.put("teleport", inPrep && plugin.isTeleportSuggested());
		List<String> drops = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : new TreeMap<>(plugin.getSlotActions()).entrySet())
		{
			drops.add(entry.getKey() + ":" + entry.getValue());
		}
		frame.put("drops", drops);
		List<String> cues = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : new TreeMap<>(plugin.getInventoryActions()).entrySet())
		{
			cues.add(entry.getKey() + ":" + entry.getValue());
		}
		frame.put("cues", cues);
		guidance.describeGuidance(frame);
	}

	/** 1 where the scene has a tile (routes only cross existing tiles), run-length encoded like collision. */
	private static String encodeLoadedTiles(WorldView view)
	{
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		int[][] loaded = new int[view.getSizeX()][view.getSizeY()];
		for (int x = 0; x < loaded.length && x < tiles.length; x++)
		{
			for (int y = 0; y < loaded[x].length && y < tiles[x].length; y++)
			{
				loaded[x][y] = tiles[x][y] == null ? 0 : 1;
			}
		}
		return AttemptFrame.encodeRunLength(loaded);
	}

	/** Non-empty slots as [slot, item id, quantity]. */
	private static List<List<Integer>> readSlots(ItemContainer container)
	{
		List<List<Integer>> result = new ArrayList<>();
		if (container == null)
			return result;
		Item[] items = container.getItems();
		for (int i = 0; i < items.length; i++)
		{
			if (items[i].getId() >= 0 && items[i].getQuantity() > 0)
				result.add(AttemptFrame.ints(i, items[i].getId(), items[i].getQuantity()));
		}
		return result;
	}

	/** Hunllef changes into its death form when it dies, in both Gauntlets. */
	static boolean isHunllefDeathShown(WorldView view)
	{
		if (view == null || view.npcs() == null)
			return false;
		for (NPC npc : view.npcs())
		{
			if (GauntletIds.npc(npc.getId()) == NpcID.CRYSTAL_HUNLLEF_DEATH_HM)
				return true;
		}
		return false;
	}

	/** The event for one hit splat: "hit player 12 type=16" or "hit npc#id#index 12 type=16". */
	public static String formatHit(Actor target, Player player, Hitsplat hit)
	{
		String name = target == player ? "player" : target instanceof NPC ? "npc#" + ((NPC) target).getId() + "#" + ((NPC) target).getIndex() : "other";
		return "hit " + name + " " + hit.getAmount() + " type=" + hit.getHitsplatType();
	}

	/** "x,y" of a world point, "?" for none; the position form of every recorded member and event. */
	public static String formatPoint(WorldPoint p)
	{
		return p == null ? "?" : p.getX() + "," + p.getY();
	}

	/** The recorded object class: G(ame), W(all), D(ecorative) or F(loor) object. */
	private static String getObjectType(TileObject object)
	{
		if (object instanceof GameObject)
			return "G";
		if (object instanceof WallObject)
			return "W";
		if (object instanceof DecorativeObject)
			return "D";
		return "F";
	}

	/**
	 * "KIND#id@x,y C min=sx,sy max=sx,sy [eff=id] [light] [lit]": class letter (G game, W wall, D decorative,
	 * F ground), scene footprint, the door's current morph and whether it offers Light. The part before " eff=" is
	 * the object's identity; the rest can change without a respawn.
	 */
	private String buildObjectRecord(TileObject object, ObjectKind kind)
	{
		Point min = SceneGeometry.getSceneMin(object);
		Point max = SceneGeometry.getSceneMax(object);
		String type = getObjectType(object);
		StringBuilder text = new StringBuilder(kind + "#" + object.getId() + "@" + formatPoint(object.getWorldLocation())
				+ " " + type + " min=" + min.getX() + "," + min.getY() + " max=" + max.getX() + "," + max.getY());
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null && composition.getImpostor() != null)
			text.append(" eff=").append(composition.getImpostor().getId());
		if (guidance.canUnlockRoom(object))
			text.append(" light");
		if (kind.isDoorway() && guidance.isOpenPassage(object, kind))
			text.append(" lit");
		return text.toString();
	}
}
