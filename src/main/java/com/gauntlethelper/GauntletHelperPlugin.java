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

import com.gauntlethelper.boss.HunllefProjectiles;
import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.guidance.AttemptClock;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.overlay.BossTileOverlay;
import com.gauntlethelper.overlay.BowlKeys;
import com.gauntlethelper.overlay.CraftOverlay;
import com.gauntlethelper.overlay.DemiOutlineOverlay;
import com.gauntlethelper.overlay.GauntletTimeLeft;
import com.gauntlethelper.overlay.GauntletTimerOverlay;
import com.gauntlethelper.overlay.GroundLootOverlay;
import com.gauntlethelper.overlay.InventoryOverlay;
import com.gauntlethelper.overlay.MinimapOverlay;
import com.gauntlethelper.overlay.ResourceOverlay;
import com.gauntlethelper.overlay.TasksOverlay;
import com.gauntlethelper.overlay.TrackerInfoBox;
import com.gauntlethelper.overlay.WorldOverlay;
import com.gauntlethelper.plan.DemiBossTracker;
import com.gauntlethelper.plan.FishDropHold;
import com.gauntlethelper.plan.FoodTargetLatch;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Instructions;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.InventoryAdvice;
import com.gauntlethelper.plan.InventoryCue;
import com.gauntlethelper.plan.PotionProgress;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.WeaponSelection;
import com.gauntlethelper.record.AttemptCapture;
import com.gauntlethelper.record.AttemptRecorder;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.DecorativeObjectDespawned;
import net.runelite.api.events.DecorativeObjectSpawned;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GroundObjectDespawned;
import net.runelite.api.events.GroundObjectSpawned;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.WallObjectDespawned;
import net.runelite.api.events.WallObjectSpawned;
import net.runelite.api.events.WorldViewUnloaded;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

/**
 * The entry point and wiring: injection, startUp/shutDown, the config provider, the @Subscribe handlers and the
 * getters the overlays read. The per-tick observation lives in TickHandler, menu handling in MenuHandler and attempt
 * recording in record/AttemptCapture; the attempt's state stays here,
 * package-private, so those helpers read it in one place. RuneLite's event bus registers this object:
 * a @Subscribe or @Provides method added, removed or moved needs a client restart.
 */
@PluginDescriptor(name = "Gauntlet Helper",
		description = "Corrupted and regular Gauntlet prep guide: resource targets, routes and crafting cues",
		tags = {"gauntlet", "corrupted", "prep", "cg", "hunllef"},
		conflicts = {"The Gauntlet", "Gauntlet Minimap", "Gauntlet Crafting", "Hunllef Projectiles"},
		internalName = GauntletHelperPlugin.DATA_FOLDER)
public class GauntletHelperPlugin extends Plugin
{
	/** Name of the plugin's data folder below RuneLite's plugin-data folder and the name of the plugin in the Hub. */
	static final String DATA_FOLDER = "gauntlet-helper";

	/** Food target suggested in the time-low hint; suggestion only, never applied. */
	static final int LATE_FOOD_TARGET = 12;

	@Inject
	private OverlayManager overlayManager;
	@Inject
	private TasksOverlay overlay;
	@Inject
	private WorldOverlay worldOverlay;
	@Inject
	private GroundLootOverlay groundLootOverlay;
	@Inject
	private BossTileOverlay bossTileOverlay;
	@Inject
	private ResourceOverlay resourceOverlay;
	@Inject
	private CraftOverlay craftOverlay;
	@Inject
	private InventoryOverlay inventoryOverlay;
	@Inject
	private MinimapOverlay minimapOverlay;
	@Inject
	GauntletHelperConfig config;
	@Inject
	Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	Guidance guidance;
	@Inject
	ItemManager itemManager;
	@Inject
	InfoBoxManager infoBoxManager;
	@Inject
	private Gson gson;
	@Inject
	private DemiOutlineOverlay demiOutlineOverlay;
	@Inject
	private GauntletTimerOverlay timerOverlay;
	@Inject
	private HunllefProjectiles hunllefProjectiles;

	// The attempt's state, written by TickHandler on the client thread (package-private for it and MenuHandler). The
	// overlays and infoboxes draw on the client thread too, so none of it needs to be volatile.
	final List<TrackerInfoBox> boxes = new ArrayList<>();
	final DemiBossTracker demiBosses = new DemiBossTracker();
	Requirements plan;
	private Instructions planInstructions;
	boolean inPrep;
	boolean inBossFight;
	/** The attempt's Gauntlet, CORRUPTED unless the varbits say regular (TickHandler.readGauntletState). */
	GauntletMode mode = GauntletMode.CORRUPTED;
	boolean scanRequired = true;
	volatile boolean running;
	boolean teleportSuggested;
	final GroundLoot loot = new GroundLoot();
	final AttemptClock clock = new AttemptClock();
	final PotionProgress potionProgress = new PotionProgress();
	final FoodTargetLatch foodTargetLatch = new FoodTargetLatch();
	final WeaponSelection weaponSelection = new WeaponSelection();
	InventoryAdvice inventoryAdvice;
	Inventory snapshot;
	Map<Integer, String> inventoryActions = new HashMap<>();
	final Map<Integer, String> slotActions = new HashMap<>();
	final FishDropHold fishDrops = new FishDropHold();
	int shownFoodDrop;
	// Default-off diagnostics (setting Log attempts); client thread only.
	AttemptRecorder recorder;
	private final TickHandler observer = new TickHandler(this);
	private final MenuHandler menus = new MenuHandler(this);
	private final BowlKeys bowlKeys = new BowlKeys();

	public Map<Integer, String> getSlotActions() { return slotActions; }

	/** Inventory action cues by item id, before the settings that show them (the recorder's "cues"). */
	public Map<Integer, String> getInventoryActions() { return inventoryActions; }
	public GroundLoot getLoot() { return loot; }

	public boolean isNeededLoot(GroundLoot.Entry entry)
	{
		return plan != null && snapshot != null && loot.isNeeded(entry, plan, snapshot, plan.isReadyForStoredFood())
				&& (guidance == null || guidance.isLootAllowed(loot, entry));
	}

	public int getSecondsRemaining() { return clock.getSeconds(); }
	public int getGameTick() { return client == null ? 0 : client.getTickCount(); }
	public int getStoredFood() { return loot.countHomeFood(); }

	public String getRouteWarning() { return guidance == null ? null : guidance.getRouteWarning(); }

	/**
	 * The panel's warning line: stored fish about to expire (whatever the settings), else time low while Time-aware
	 * return guidance is on, the prep clock is readable and the plan isn't ready for Hunllef yet. Null when neither
	 * applies.
	 */
	public String getTimeHint()
	{
		if (config == null)
			return null;
		if (loot.hasExpiringHomeFood(client.getTickCount()))
			return plan != null && plan.isReadyForStoredFood() ? "Stored fish expiring soon: retrieve remaining food"
					: "Stored fish expiring; finish gear and potions before retrieval";
		if (!config.useTimeAwareReturnGuidance())
			return null;
		return clock.isUrgent(Math.max(0, config.returnWarningSeconds())) ? formatLowTimeHint(plan) : null;
	}

	/**
	 * The time-low hint; with armor upgrades left (T2 or T3 goal) it says they are optional. The goal itself never
	 * changes. Null once the plan is ready for Hunllef (Requirements.isReady, the "Ready for Hunllef" status): with
	 * nothing left to finish at home, neither line says anything useful.
	 */
	static String formatLowTimeHint(Requirements plan)
	{
		if (plan != null && plan.isReady())
			return null;
		boolean upgrades = plan != null && !plan.findArmorUpgrades().isEmpty();
		return "Time low: finish at home\n" + (upgrades ? "Skip armor upgrades if short\n" : "") + "Suggested food target " + LATE_FOOD_TARGET;
	}

	public String getInventoryCue(int id, int slot)
	{
		if (config == null)
			return null;
		if (config.highlightDroppableItems())
		{
			if (slotActions.containsKey(slot))
				return slotActions.get(slot);
			if (inventoryAdvice != null && inventoryAdvice.getFinishedTools().containsKey(id))
				return InventoryCue.drop(inventoryAdvice.getFinishedTools().get(id));
		}
		return config.highlightInventoryActions() ? inventoryActions.get(id) : null;
	}

	public GauntletMap getGauntletMap() { return guidance == null ? null : guidance.getGauntletMap(); }
	public Requirements getPlan() { return plan; }
	public HomeVisit getHomeVisit() { return guidance.getHomeVisit(getPlan()); }
	public BowlKeys getBowlKeys() { return bowlKeys; }

	/** The plan's wording, built once for each tick's final plan. */
	public Instructions getPlanInstructions()
	{
		Requirements current = getPlan();
		if (planInstructions == null || !planInstructions.isFor(current))
			planInstructions = Instructions.of(current);
		return planInstructions;
	}

	public boolean isInPrep() { return inPrep; }
	public boolean isInBossFight() { return inBossFight; }
	public GauntletMode getMode() { return mode; }

	public String getPhaseLabel()
	{
		return guidance == null ? "Waiting for preparation" : guidance.getPhaseLabel(getPlan());
	}


	public String getSecondaryInstruction()
	{
		if (guidance == null)
			return null;
		if (!guidance.isInventorySpaceReturn() || getPlan() == null)
			return guidance.getNextTaskInstruction();
		// Only the home jobs this inventory has.
		boolean canCraft = getHomeVisit().canCraft();
		boolean holdsFish = getPlan().hasFish();
		if (canCraft && holdsFish)
			return guidance.isFullReturnStorageFirst() ? "Cook and store fish, then craft at home" : "Craft, cook and store fish at home";
		if (canCraft)
			return "Craft at singing bowl";
		if (holdsFish)
			return "Cook and store fish at home";
		return "Make inventory space at home";
	}

	public boolean isTeleportSuggested() { return teleportSuggested; }

	public String getInstruction()
	{
		if (plan == null)
		{
			return "Waiting for preparation";
		}
		if (!arePrepCuesAllowed())
		{
			return "Resource checklist";
		}
		if (plan.isReady())
		{
			// The scene's only ready task is the walk to Hunllef's barrier.
			return guidance.getSceneInstruction() == null ? getPlanInstructions().getInstruction() : guidance.getSceneInstruction();
		}
		// While the pump is the current home task, its own instruction stands, so the text doesn't name the range
		// while the pump is highlighted and the path leads to it.
		if (inventoryAdvice != null && config.highlightDroppableItems() && shouldShowHomeDrops()
				&& inventoryAdvice.getInstruction() != null && !guidance.shouldFillBeforeStorage())
			return inventoryAdvice.getInstruction();
		if (teleportSuggested)
		{
			return "Use teleport crystal to return home";
		}
		return guidance.getSceneInstruction() == null ? getPlanInstructions().getInstruction() : guidance.getSceneInstruction();
	}

	/**
	 * Home drop cues show when nothing is craftable, while a started fish drop is unfinished, or while fish storage
	 * goes first to make room for waiting crafts.
	 */
	boolean shouldShowHomeDrops()
	{
		return guidance.isAtHome() && (inventoryAdvice.isDropInProgress() || guidance.shouldStoreBeforeCrafting()
				|| ((!plan.isInventoryAction() || plan.needsInventorySpace()) && !getHomeVisit().canCraft()));
	}

	public boolean arePrepCuesAllowed()
	{
		return inPrep && plan != null && client.getGameState() == GameState.LOGGED_IN;
	}

	/** Preparation in either Gauntlet: inside, before the boss fight. */
	public static boolean isPrep(boolean loggedIn, boolean inside, boolean fighting)
	{
		return loggedIn && inside && !fighting;
	}

	@Provides
	GauntletHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GauntletHelperConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(worldOverlay);
		overlayManager.add(groundLootOverlay);
		overlayManager.add(bossTileOverlay);
		overlayManager.add(resourceOverlay);
		overlayManager.add(craftOverlay);
		overlayManager.add(inventoryOverlay);
		overlayManager.add(minimapOverlay);
		overlayManager.add(demiOutlineOverlay);
		overlayManager.add(timerOverlay);
		running = true;
		recorder = new AttemptRecorder(gson, () -> getPluginDirectory().joinSegment("attempts"));
		clientThread.invoke(() ->
		{
			if (running)
			{
				observer.observe();
			}
		});
	}

	@Override
	protected void shutDown()
	{
		running = false;
		bowlKeys.forget();
		overlayManager.remove(overlay);
		overlayManager.remove(worldOverlay);
		overlayManager.remove(groundLootOverlay);
		overlayManager.remove(bossTileOverlay);
		overlayManager.remove(resourceOverlay);
		overlayManager.remove(craftOverlay);
		overlayManager.remove(inventoryOverlay);
		overlayManager.remove(minimapOverlay);
		overlayManager.remove(demiOutlineOverlay);
		overlayManager.remove(timerOverlay);
		// shutDown runs on the EDT; attempt state and infobox list are owned by the client thread.
		// A quick re-enable may replace the recorder before this runs, so close the one this shutdown owns.
		AttemptRecorder closing = recorder;
		clientThread.invoke(() ->
		{
			for (TrackerInfoBox box : boxes)
			{
				infoBoxManager.removeInfoBox(box);
			}
			boxes.clear();
			if (closing != null)
				closing.close(client.getTickCount());
			GauntletTimeLeft.apply(client, false);
			observer.clear();
		});
	}

	// The only per-tick observe: GameTick follows the tick's varbit and container events.
	@Subscribe
	public void onGameTick(GameTick event)
	{
		observer.observe();
		timerOverlay.update(inPrep, inBossFight, client.getTickCount(), clock.getTicksLeft(), mode);
		GauntletTimeLeft.apply(client, GauntletTimerOverlay.shouldHideTimeLeft(config, inPrep, inBossFight));
	}

	@Subscribe
	public void onItemSpawned(ItemSpawned event)
	{
		if (inPrep || inBossFight)
			loot.recordSpawn(event.getTile().getWorldLocation(), event.getItem().getId(), event.getItem().getQuantity());
	}

	@Subscribe
	public void onItemDespawned(ItemDespawned event)
	{
		if (inPrep || inBossFight)
			loot.recordDespawn(event.getTile().getWorldLocation(), event.getItem().getId(), event.getItem().getQuantity());
	}

	@Subscribe
	public void onItemQuantityChanged(ItemQuantityChanged event)
	{
		if (inPrep || inBossFight)
			loot.recordQuantityChange(event.getTile().getWorldLocation(), event.getItem().getId(), event.getOldQuantity(), event.getNewQuantity());
	}

	@Subscribe
	public void onWorldViewUnloaded(WorldViewUnloaded event)
	{
		if (event.getWorldView().isTopLevel())
			loot.clearSpawnOrder();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (recorder != null)
			recorder.recordEvent("game state " + event.getGameState());
		// A lost connection normally reconnects into the same instance, so like a loading screen it only pauses the
		// guidance and the attempt's ledgers (map, loot, potions, fish quota) stay; the scene is scanned again on return.
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.CONNECTION_LOST)
		{
			scanRequired = true;
			guidance.pauseGuidance();
		}
		else if (event.getGameState() != GameState.LOGGED_IN)
		{
			observer.clear();
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		if (event.getActor() == client.getLocalPlayer())
		{
			if (recorder != null)
			{
				recorder.recordEvent("player died");
				recorder.setResult(AttemptCapture.RESULT_DIED);
			}
			observer.clear();
		}
		else if (inPrep)
		{
			if (recorder != null && event.getActor() instanceof NPC)
				recorder.recordEvent("death npc#" + ((NPC) event.getActor()).getId() + "#"
						+ ((NPC) event.getActor()).getIndex() + "@"
						+ AttemptCapture.formatPoint(event.getActor().getWorldLocation()));
			demiBosses.record(event.getActor());
			if (event.getActor() instanceof NPC)
				guidance.recordNpcDeath((NPC) event.getActor());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied event)
	{
		if (recorder != null && recorder.isRecording())
			recorder.recordEvent(AttemptCapture.formatHit(event.getActor(), client.getLocalPlayer(), event.getHitsplat()));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!GauntletHelperConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		clientThread.invoke(() ->
		{
			if (running)
			{
				observer.observe();
				GauntletTimeLeft.apply(client, GauntletTimerOverlay.shouldHideTimeLeft(config, inPrep, inBossFight));
			}
		});
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		menus.handleOptionClicked(event);
	}

	/**
	 * Needed loot's Take first (setting "Reorder ground items") and the barrier's Pass below Walk here with raw fish
	 * (setting "Block Hunllef entry with raw fish"); MenuHandler.putNeededTakesFirst and deprioritizeHunllefEntry say how.
	 */
	@Subscribe
	public void onPostMenuSort(PostMenuSort event)
	{
		menus.putNeededTakesFirst();
		menus.deprioritizeHunllefEntry();
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (inPrep)
			guidance.observeNpc(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (inPrep)
			guidance.recordNpcUnloaded(event.getNpc());
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (inPrep)
		{
			guidance.add(event.getGameObject());
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (inPrep || inBossFight)
			guidance.remove(event.getGameObject());
	}

	@Subscribe
	public void onWallObjectSpawned(WallObjectSpawned event)
	{
		if (inPrep)
		{
			guidance.add(event.getWallObject());
		}
	}

	@Subscribe
	public void onWallObjectDespawned(WallObjectDespawned event)
	{
		if (inPrep || inBossFight)
			guidance.remove(event.getWallObject());
	}

	@Subscribe
	public void onDecorativeObjectSpawned(DecorativeObjectSpawned event)
	{
		if (inPrep)
		{
			guidance.add(event.getDecorativeObject());
		}
	}

	@Subscribe
	public void onDecorativeObjectDespawned(DecorativeObjectDespawned event)
	{
		if (inPrep || inBossFight)
			guidance.remove(event.getDecorativeObject());
	}

	@Subscribe
	public void onGroundObjectSpawned(GroundObjectSpawned event)
	{
		if (inPrep)
		{
			guidance.add(event.getGroundObject());
		}
	}

	@Subscribe
	public void onGroundObjectDespawned(GroundObjectDespawned event)
	{
		if (inPrep || inBossFight)
			guidance.remove(event.getGroundObject());
	}

	/** Setting "Prayer-disable projectile"; HunllefProjectiles says how. */
	@Subscribe
	public void onProjectileMoved(ProjectileMoved event)
	{
		if (inBossFight)
			hunllefProjectiles.replace(event.getProjectile());
	}
}
