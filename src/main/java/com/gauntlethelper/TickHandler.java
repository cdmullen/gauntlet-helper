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

package com.gauntlethelper;

import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.game.GauntletIds;
import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.overlay.GauntletTimeLeft;
import com.gauntlethelper.overlay.TrackerInfoBox;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.InventoryAdvice;
import com.gauntlethelper.plan.InventoryCue;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.Simulation;
import com.gauntlethelper.record.AttemptCapture;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;

/**
 * The plugin's per-tick observation: reads the client, rebuilds the plan, runs the guidance and publishes the map
 * view. The state it writes stays on the plugin. Client thread only.
 */
final class TickHandler
{
	/** Ticks a used crystal keeps the teleport guidance, from where it was used, before the player lands at home. */
	static final int TELEPORT_HOLD_TICKS = 5;
	/** Ticks between the full ground scans that catch anything the item events missed. */
	static final int LOOT_SCAN_TICKS = 100;
	/** Tiles the player can run in one tick; a longer move is a teleport. */
	private static final int MAX_RUN_TILES = 2;

	private final GauntletHelperPlugin plugin;
	private int teleportHold, lastCrystals;
	private WorldPoint teleportFrom;
	private int lastLootScan;
	private WorldPoint lastLootPosition;

	TickHandler(GauntletHelperPlugin plugin)
	{
		this.plugin = plugin;
	}

	/** The only per-tick observe (GauntletHelperPlugin.onGameTick says why no other handler is needed). */
	void observe()
	{
		observeState();
		if (plugin.recorder != null)
			new AttemptCapture(plugin, plugin.client, plugin.config, plugin.guidance).record(plugin.recorder);
	}

	/** Attempt reset (logged out, dead, plugin off): everything, and the recording stops. */
	void clear()
	{
		plugin.inPrep = false;
		plugin.inBossFight = false;
		plugin.mode = GauntletMode.CORRUPTED;
		plugin.guidance.setMode(GauntletMode.CORRUPTED);
		clearPrepGuidance();
		resetAttemptLedgers();
		if (plugin.recorder != null)
			plugin.recorder.stop(plugin.client.getTickCount(), "attempt reset");
	}

	/**
	 * What ends with preparation, the boss fight included: the plan, the teleport suggestion, the timer and the
	 * inventory cues; the scene is scanned again on return. Shared by clear and leavePrep.
	 */
	private void clearPrepGuidance()
	{
		plugin.plan = null;
		plugin.teleportSuggested = false;
		teleportHold = 0;
		plugin.scanRequired = true;
		plugin.clock.reset();
		plugin.slotActions.clear();
		plugin.inventoryActions.clear();
		plugin.inventoryAdvice = null;
		plugin.fishDrops.reset();
	}

	/**
	 * The attempt's ledgers, which the boss fight keeps: the scene and map, demi kills, loot memory, crafted potions,
	 * the fish quota and the weapon choice. Add a new attempt ledger here. Shared by clear and leavePrep.
	 */
	private void resetAttemptLedgers()
	{
		plugin.guidance.reset();
		plugin.demiBosses.reset();
		plugin.loot.reset();
		plugin.potionProgress.reset();
		plugin.foodTargetLatch.reset();
		plugin.weaponSelection.reset();
	}

	/**
	 * One tick's observation, in this order: leave early while loading or reconnecting (the attempt stays), logged
	 * out, dead or outside preparation;
	 * read the inventory into the plan; observe the scene and ground items and credit them to the plan; run the
	 * scene's guidance; set the inventory cues and the teleport suggestion; log; refresh the infoboxes.
	 */
	private void observeState()
	{
		Client client = plugin.client;
		Player player = client.getLocalPlayer();
		if (client.getGameState() == GameState.LOADING || client.getGameState() == GameState.CONNECTION_LOST)
		{
			plugin.scanRequired = true;
			plugin.guidance.pauseGuidance();
			return;
		}
		if (!plugin.running || client.getGameState() != GameState.LOGGED_IN || player == null || client.getBoostedSkillLevel(Skill.HITPOINTS) <= 0)
		{
			clear();
			return;
		}
		boolean wasInPrep = plugin.inPrep;
		readGauntletState();
		if (!plugin.inPrep)
		{
			leavePrep();
			return;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
		if (inventory == null)
		{
			plugin.plan = null;
			return;
		}
		Map<Integer, Integer> held = countItems(inventory);
		Map<Integer, Integer> worn = countItems(equipment);
		if (shouldHoldTeleport(held.getOrDefault(ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM, 0), player.getWorldLocation()))
			return;
		int slots = countOccupiedSlots(inventory);
		plugin.snapshot = new Inventory(held, worn, slots);
		plugin.plan = Requirements.live(plugin.snapshot, plugin.config, plugin.potionProgress, plugin.weaponSelection);
		observeScene(player, wasInPrep);
		creditObservations();
		updateGuidance();
		boolean urgent = isUrgent();
		plugin.guidance.setPlanningSeconds(plugin.clock.getSeconds());
		adviseInventory();
		applyGuidance(urgent);
		previewBowlVisit();
		setInventoryCues(inventory);
		suggestTeleport(held, slots, urgent);
		refreshInfoBoxes();
	}

	/**
	 * inPrep, inBossFight and the attempt's mode from the Gauntlet varbits. On entry PLAYER_IN_GAUNTLET turns 1 one tick
	 * before GAUNTLET_CORRUPTED is set, with the player still on the lobby tile, so inside waits for the instance:
	 * a Corrupted run would otherwise read as regular on the tick the recording's header and folder are chosen.
	 */
	private void readGauntletState()
	{
		Client client = plugin.client;
		WorldView view = client.getTopLevelWorldView();
		boolean inside = client.getVarbitValue(VarbitID.PLAYER_IN_GAUNTLET) == 1 && (view == null || view.isInstance());
		int corruptedVarbit = client.getVarbitValue(VarbitID.GAUNTLET_CORRUPTED);
		boolean fighting = client.getVarbitValue(VarbitID.GAUNTLET_BOSS_STARTED) == 1;
		plugin.inPrep = GauntletHelperPlugin.isPrep(true, inside, fighting);
		plugin.inBossFight = fighting && (corruptedVarbit == 1 || inside);
		if (inside)
			setMode(GauntletMode.of(true, corruptedVarbit));
	}

	/** The attempt's mode, kept through the boss fight. The two Gauntlets never share an attempt: leaving one resets it. */
	private void setMode(GauntletMode mode)
	{
		plugin.mode = mode;
		plugin.guidance.setMode(mode);
	}

	/** Outside preparation: the boss keeps the map and loot memory, anything else resets the attempt's ledgers. */
	private void leavePrep()
	{
		Guidance guidance = plugin.guidance;
		clearPrepGuidance();
		if (plugin.inBossFight)
		{
			guidance.pauseGuidance();
			guidance.observeGlobalMapOnly();
			plugin.loot.expire(plugin.client.getTickCount());
			guidance.syncLoot(plugin.loot);
		}
		else
			resetAttemptLedgers();
	}

	/**
	 * True while a just-used crystal is still teleporting the player: the last tick's guidance stands. The crystal
	 * leaves the inventory a few ticks before the player lands at home, and without this the guide would briefly show
	 * "Light room" and a walking path home.
	 */
	private boolean shouldHoldTeleport(int crystals, WorldPoint here)
	{
		if (plugin.teleportSuggested && crystals < lastCrystals)
		{
			teleportHold = TELEPORT_HOLD_TICKS;
			teleportFrom = here;
			plugin.guidance.observeCrystalUsed(plugin.plan);
		}
		lastCrystals = crystals;
		if (teleportHold <= 0)
			return false;
		teleportHold--;
		if (here != null && teleportFrom != null && here.distanceTo2D(teleportFrom) <= 1)
			return true;
		teleportHold = 0;
		return false;
	}

	private static int countOccupiedSlots(ItemContainer inventory)
	{
		int slots = 0;
		for (Item item : inventory.getItems())
		{
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				slots++;
			}
		}
		return slots;
	}

	/** Scene objects (scanned on entering prep, after loading or until the first doors are seen), position, loot. */
	private void observeScene(Player player, boolean wasInPrep)
	{
		Guidance guidance = plugin.guidance;
		boolean sceneChanged = !wasInPrep || plugin.scanRequired;
		if (sceneChanged || guidance.needsInitialDoorScan())
		{
			guidance.scan();
			plugin.scanRequired = false;
		}
		guidance.observePosition(player);
		observeLoot(sceneChanged, player.getWorldLocation());
		plugin.loot.expire(plugin.client.getTickCount());
	}

	/** Stored fish, the attempt's fish quota, armor crafted onto the floor and finished tools, into the plan. */
	private void creditObservations()
	{
		plugin.plan = plugin.plan.withStoredFood(plugin.loot.countHomeFood());
		plugin.plan = plugin.foodTargetLatch.apply(plugin.plan);
		plugin.plan = plugin.plan.withGroundArmor(plugin.loot.findArmorOnGround(plugin.snapshot));
		if (!Simulation.findFinishedTools(plugin.plan, plugin.snapshot).isEmpty())
			plugin.plan = plugin.plan.withToolDropsContinued();
	}

	/** The scene's target for this plan, with demi history and the timer read first. */
	private void updateGuidance()
	{
		Guidance guidance = plugin.guidance;
		guidance.setDemiHistory(plugin.demiBosses);
		plugin.clock.observe(GauntletTimeLeft.read(plugin.client), plugin.client.getTickCount());
		if (isTimeLow())
			plugin.plan = plugin.plan.withUpgradesDeferred();
		// At home the bowl crafts come before equipping what they made (home is last tick's, read before the update).
		if (guidance.isAtHome())
		{
			HomeVisit visit = HomeVisit.craftingBeforeEquip(plugin.plan);
			plugin.plan = visit.getRequirements();
			guidance.startHomeVisit(visit);
		}
		guidance.update(plugin.plan);
	}

	/** The whole visit's crafts for the bowl menu at home and the key line on the way there, read by the overlays. */
	private void previewBowlVisit()
	{
		Guidance guidance = plugin.guidance;
		HomeVisit visit = guidance.getHomeVisit(plugin.plan);
		if ((guidance.isAtHome() || guidance.getTargetKind() == ObjectKind.BOWL) && visit.getHomeCraftPlan() == null)
			visit.previewCrafts();
	}

	/** Time low (setting Time guidance), or stored fish expiring once only their retrieval is left. */
	private boolean isUrgent()
	{
		return isTimeLow() || (plugin.loot.hasExpiringHomeFood(plugin.client.getTickCount()) && plugin.plan.isReadyForStoredFood());
	}

	private boolean isTimeLow()
	{
		GauntletHelperConfig config = plugin.config;
		return config.useTimeAwareReturnGuidance() && plugin.clock.isUrgent(Math.max(0, config.returnWarningSeconds()));
	}

	/** Drop and action advice for this inventory, holding a started fish drop until it is finished. */
	private void adviseInventory()
	{
		Inventory snapshot = plugin.snapshot;
		boolean atHome = plugin.guidance.isAtHome();
		plugin.inventoryAdvice = new InventoryAdvice(plugin.plan, snapshot, atHome);
		int fishLeft = plugin.fishDrops.updateRemaining(atHome, snapshot.getHeld(ItemID.GAUNTLET_FOOD), snapshot.getHeld(ItemID.GAUNTLET_RAW_FOOD));
		if (fishLeft > 0)
			plugin.inventoryAdvice.holdFishDrop(fishLeft);
	}

	/** Home space first, else the task steps, the demi-room cue and Next; then the sceptre and the door shown (Guidance). */
	private void applyGuidance(boolean urgent)
	{
		plugin.guidance.applyGuidance(plugin.plan, plugin.snapshot, plugin.loot, plugin.inventoryAdvice, urgent);
	}

	/** Inventory action cues by item, and drop cues by slot (finished tools anywhere, home drops when shown). */
	private void setInventoryCues(ItemContainer inventory)
	{
		InventoryAdvice advice = plugin.inventoryAdvice;
		plugin.inventoryActions = InventoryAdvice.buildActions(plugin.plan, plugin.snapshot);
		plugin.slotActions.clear();
		Map<Integer, Integer> remaining = new HashMap<>(advice.getFinishedTools());
		if (plugin.shouldShowHomeDrops())
			remaining.putAll(advice.getDrops());
		plugin.shownFoodDrop = plugin.config.highlightDroppableItems() ? remaining.getOrDefault(ItemID.GAUNTLET_FOOD, 0) : 0;
		Item[] items = inventory.getItems();
		for (int i = 0; i < items.length; i++)
		{
			int id = GauntletIds.item(items[i].getId());
			int n = remaining.getOrDefault(id, 0);
			if (n <= 0)
				continue;
			int quantity = Math.min(n, items[i].getQuantity());
			plugin.slotActions.put(i, InventoryCue.drop(quantity));
			remaining.put(id, n - quantity);
		}
	}

	private void suggestTeleport(Map<Integer, Integer> held, int slots, boolean urgent)
	{
		Guidance guidance = plugin.guidance;
		Requirements plan = plugin.plan;
		plugin.teleportSuggested = !guidance.isAtHome() && !guidance.isOutwardTrip() && guidance.getGroundTarget() == null
				// Only planned reasons: full inventory, low time, a long first-home staff trip or a trip-planner teleport.
				// Not "26+ slots and something craftable": the route may still have room left.
				&& (guidance.isInventorySpaceReturn() || urgent || guidance.needsStaffTeleport() || guidance.needsTripTeleport())
				&& !plan.isReady() && (!plan.isInventoryAction() || slots == Inventory.SIZE)
				&& held.getOrDefault(ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM, 0) > 0
				&& guidance.isTeleportWorthwhile();
	}

	private void refreshInfoBoxes()
	{
		List<TrackerInfoBox> boxes = plugin.boxes;
		if (boxes.isEmpty())
		{
			for (Requirement resource : Requirement.values())
			{
				TrackerInfoBox box = new TrackerInfoBox(plugin.itemManager.getImage(resource.getItemId()), plugin, plugin.config, resource);
				boxes.add(box);
				plugin.infoBoxManager.addInfoBox(box);
			}
		}
		for (TrackerInfoBox box : boxes)
		{
			box.refresh();
		}
	}

	/**
	 * Ground items into the loot table: the tiles the item events changed and the remembered ones, or every loaded tile
	 * after anything that can lose events (a scene change, a teleport) and every LOOT_SCAN_TICKS. Both read a tile the
	 * same way, so a scan only ever corrects the table.
	 */
	private void observeLoot(boolean sceneChanged, WorldPoint here)
	{
		Client client = plugin.client;
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.getScene() == null)
			return;
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		boolean teleported = here != null && lastLootPosition != null && here.distanceTo2D(lastLootPosition) > MAX_RUN_TILES;
		lastLootPosition = here;
		int tick = client.getTickCount();
		if (sceneChanged || teleported || tick < lastLootScan || tick - lastLootScan >= LOOT_SCAN_TICKS)
		{
			lastLootScan = tick;
			scanLoot(tiles, here);
			return;
		}
		for (WorldPoint at : plugin.loot.findTilesToRead())
		{
			int x = at.getX() - view.getBaseX();
			int y = at.getY() - view.getBaseY();
			if (at.getPlane() == view.getPlane() && x >= 0 && x < tiles.length && y >= 0 && y < tiles[x].length && tiles[x][y] != null)
				readLootTile(tiles[x][y], at, here);
		}
	}

	private void scanLoot(Tile[][] tiles, WorldPoint here)
	{
		plugin.loot.clearChangedTiles();
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
					continue;
				// Read once: getWorldLocation builds a new point on every call, and this loop visits every scene tile.
				WorldPoint at = tile.getWorldLocation();
				if (at != null)
					readLootTile(tile, at, here);
			}
		}
	}

	/** Replaces what is remembered at a tile with what is on it now. */
	private void readLootTile(Tile tile, WorldPoint at, WorldPoint here)
	{
		Guidance guidance = plugin.guidance;
		List<TileItem> items = tile.getGroundItems();
		boolean bare = items == null || items.isEmpty();
		if (!guidance.isDiscoveredTile(at))
		{
			if (!bare)
				plugin.loot.markUnread(at);
			return;
		}
		if (bare)
		{
			// Most tiles are bare: forget anything remembered there without building a list.
			if (GroundLoot.isEmptyTileTrusted(at, here))
				plugin.loot.observe(at, Collections.emptyList());
			return;
		}
		boolean home = guidance.isHomeTile(at);
		List<GroundLoot.Entry> entries = new ArrayList<>();
		for (TileItem item : items)
		{
			int id = GauntletIds.item(item.getId());
			entries.add(new GroundLoot.Entry(at, id, item.getQuantity(), item.getDespawnTime(), home));
			plugin.demiBosses.observeGroundItem(id);
		}
		plugin.loot.observe(at, entries);
	}

	private static Map<Integer, Integer> countItems(ItemContainer container)
	{
		Map<Integer, Integer> result = new HashMap<>();
		if (container == null)
		{
			return result;
		}
		for (Item item : container.getItems())
		{
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				result.merge(GauntletIds.item(item.getId()), item.getQuantity(), Integer::sum);
			}
		}
		return result;
	}
}
