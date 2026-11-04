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

package com.gauntlethelper.guidance;

import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.game.GauntletIds;
import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.map.OpeningRoute;
import com.gauntlethelper.map.PerimeterSearch;
import com.gauntlethelper.map.RoomUnlock;
import com.gauntlethelper.plan.DemiBossTracker;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.InventoryAdvice;
import com.gauntlethelper.plan.OpeningBudget;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.record.AttemptFrame;
import java.awt.Color;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;

/**
 * Live prep guidance: the facade the plugin and overlays call, and the coordinator of one update. It owns the shared
 * state; the jobs live in its helpers.
 */
@Singleton
public class Guidance
{
	/** Lighting a room needs a sceptre; recovery cues use this blue instead of the task color. */
	public static final Color SCEPTRE_BLUE = new Color(40, 120, 255);
	public static final String DEMI_HUNTING = "Demi hunting";

	/** A dropped sceptre further than this many route steps is skipped in favor of the spawn tool storage. */
	static final int NEARBY_SCEPTRE_STEPS = 32;

	static final String PATHLESS_RESOURCE = "Needed room resource remains; gather manually while its path is unavailable";

	@Inject
	Client client;
	@Inject
	GauntletHelperConfig config;
	final OpeningRoute opening = new OpeningRoute();
	/** The current target, path and next/side cues; the helpers below read and write it. */
	final GuidanceState targeting = new GuidanceState();
	/** The ground loot last synced (syncLoot), for routes that count a dropped sceptre within reach (Routing.canLightDoor). */
	GroundLoot lastLoot;
	final Map<TileObject, ObjectKind> objects = new IdentityLinkedMap<>();
	final GauntletMap gauntletMap = new GauntletMap();
	final PerimeterSearch perimeter = new PerimeterSearch();
	/** The attempt's shared guidance state: home return, opening loop, first home visit and instruction text. */
	final AttemptLatches attempt = new AttemptLatches();
	private HomeVisit homeVisit;
	DemiBossTracker demiHistory = new DemiBossTracker();
	int planningSeconds = -1;
	/** The attempt's Gauntlet (GauntletHelperPlugin sets it); CORRUPTED unless the varbits say regular. */
	GauntletMode mode = GauntletMode.CORRUPTED;
	/** Spawn-room tool storage (the middle object that hands back missing tools, including the sceptre). */
	TileObject toolStorage;
	/** Loaded barriers into Hunllef's room; the route goal once the loadout is ready. */
	final List<TileObject> barriers = new ArrayList<>();
	/** Set each update: standing at home with a bowl craft available, so filling vials waits. */
	private boolean craftBeforeWater;
	final GuidanceLog guidanceLog = new GuidanceLog(this, targeting);
	final MapObserver mapObserver = new MapObserver(this);
	final OpeningSetup orientation = new OpeningSetup(this);
	final Routing routing = new Routing(this, targeting);
	final TaskPriorities priorities = new TaskPriorities(this, targeting);
	final ObjectObserver objectObserver = new ObjectObserver(this, targeting);
	final Harvests harvests = new Harvests(this, targeting);
	final RoomTour tour = new RoomTour(this, targeting);
	final DefaultTask objectTask = new DefaultTask(this, targeting);
	final NextTask next = new NextTask(this, targeting);
	final DemiCue demiCue = new DemiCue(this);

	public void setDemiHistory(DemiBossTracker history) { demiHistory = history; }
	public void setPlanningSeconds(int seconds) { planningSeconds = seconds; }
	public void setMode(GauntletMode mode) { this.mode = mode == null ? GauntletMode.CORRUPTED : mode; }
	public GauntletMode getMode() { return mode; }

	public void reset()
	{
		homeVisit = null;
		lastLoot = null;
		attempt.reset();
		perimeter.reset();
		harvests.reset();
		priorities.reset();
		demiCue.reset();
		// objectObserver.clear() below clears the rest of the target state.
		targeting.tourSummary = null;
		targeting.clearNext();
		planningSeconds = -1;
		gauntletMap.reset();
		opening.reset();
		orientation.reset();
		guidanceLog.reset();
		objectObserver.clear();
	}

	public void pauseGuidance()
	{
		attempt.clearTrip();
		tour.clear();
		// Keeps the held door, ground target, last route and tour summary; the next update replaces them.
		targeting.clearTask();
		targeting.clearNext();
	}

	/*
	 * One tick's guidance, in this order. TickHandler runs it in two calls, update then applyGuidance, because the
	 * inventory advice between them reads where update found the player (isAtHome) and applyGuidance needs that advice.
	 *
	 * update:
	 *   1. start the update (clear last tick's task; the held door stays)
	 *   2. build this tick's route from the player (stop here when the scene isn't loaded)
	 *   3. observe the opening loop and the dungeon map
	 *   4. plan the opening loop's shard and fish budgets
	 *   5. choose the default object task: fill vials, else the post-loop search, else the opening loop or nearest
	 *      station/door (DefaultTask)
	 *   6. observe whether the player is home, and limit the first home visit's crafts
	 *   7. hold the door shown
	 * applyGuidance:
	 *   8. home space (cook, store, craft or fill before a constrained departure), else
	 *   9. the task steps, which may replace the default task (TaskPriorities.taskSteps, first claim wins), the
	 *      demi-room cue and Next (NextTask.NEXT_STEPS, first NextDecision wins)
	 *  10. the sceptre, over the task published so far: recover a missing one (a Decision replacing a Light room task),
	 *      drop one no longer needed (Sceptre.Cue)
	 *  11. hold the door shown again, now the one the task steps chose
	 *
	 * The default task is chosen first because the task steps read what it leaves: the target and its kind (a
	 * resource or door task already set), lastRoute, the room tour, the opening's first-door scan and the held door.
	 */
	public void update(Requirements plan)
	{
		startUpdate(plan);
		WorldView view = client.getTopLevelWorldView();
		Player player = client.getLocalPlayer();
		LocalPathfinder route = buildRoute(view, player);
		if (route == null)
			return;
		targeting.lastRoute = route;
		observeOpening(route, view, player);
		finishLoopAtHome(plan, player);
		// The opening observation just changed the map these are worked out from.
		targeting.clearRouteCache();
		planOpeningBudget(plan, player);
		chooseObjectTask(plan, route, view, player);
		observeHome(player);
		restrictFirstHomeCrafts(plan);
		holdShownDoor();
	}

	/** Steps 8 to 11 of the guidance order above, after update and the inventory advice. */
	public void applyGuidance(Requirements plan, Inventory inv, GroundLoot loot, InventoryAdvice advice, boolean urgent)
	{
		if (!prepareHomeSpace(plan, advice))
		{
			applyPriorities(plan, inv, loot, urgent);
			// Again after the task steps: a demi room they now head for is the destination, so its cue latches this tick.
			updateDemiRoomCue(plan);
			updateNextTask(plan);
		}
		// After the task steps and Next: the sceptre reads the task they published, and any second update has run.
		TickContext context = TickContext.of(this, plan, inv, loot, urgent);
		priorities.publishSceptreRecovery(context);
		publishSceptreCue(priorities.sceptre.chooseCue(context), inv, advice);
		// After the task steps, so the next update holds the door actually shown rather than update's own first choice.
		holdShownDoor();
	}

	private static void publishSceptreCue(Sceptre.Cue cue, Inventory inv, InventoryAdvice advice)
	{
		if (cue == Sceptre.Cue.DROP)
			advice.dropSceptre(inv);
		else if (cue == Sceptre.Cue.KEEP)
			advice.keepSceptre();
	}

	/** Clears last update's task, Next, ground target, route and tour; the held door stays. */
	private void startUpdate(Requirements plan)
	{
		attempt.beginUpdate(plan);
		priorities.hunt.beginUpdate();
		// Before any planning, so this tick's catch already counts against its node (else coverage flickers a tick).
		harvests.trackHarvest(plan);
		priorities.sceptre.clearRecovery();
		orientation.beginUpdate();
		// The held door stays: a doorway keeps its lit door between ticks.
		targeting.clearTask();
		targeting.clearNext();
		targeting.groundTarget = null;
		targeting.lastRoute = null;
		targeting.clearRouteCache();
		targeting.tourSummary = null;
		tour.beginUpdate();
		craftBeforeWater = false;
	}

	/** Collision-aware routes from the player over the loaded tiles; null while the scene or player isn't ready. */
	private LocalPathfinder buildRoute(WorldView view, Player player)
	{
		if (view == null || view.getScene() == null || player == null || view.getCollisionMaps() == null)
			return null;
		CollisionData map = view.getCollisionMaps()[view.getPlane()];
		if (map == null)
			return null;
		int[][] flags = map.getFlags();
		if (flags == null || flags.length == 0 || flags[0].length == 0)
			return null;
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		boolean[][] loaded = new boolean[flags.length][flags[0].length];
		for (int x = 0; x < loaded.length; x++)
		{
			for (int y = 0; y < loaded[x].length; y++)
			{
				loaded[x][y] = x < tiles.length && y < tiles[x].length && tiles[x][y] != null;
			}
		}
		LocalPoint origin = player.getLocalLocation();
		if (origin == null)
			return null;
		return new LocalPathfinder(flags, loaded, origin.getSceneX(), origin.getSceneY());
	}

	/** The first room, the opening loop's progress and the dungeon map, from this tick's position and scene. */
	private void observeOpening(LocalPathfinder route, WorldView view, Player player)
	{
		orientation.refreshFirstRoom(player);
		orientation.updateOpening(route, player);
		// Share the entry latched by this tick's passage/map/physical observation.
		orientation.refreshFirstRoom(player);
		guidanceLog.logOpening(player);
		mapObserver.refreshGlobalMap(view, player, view.getScene().getTiles()[view.getPlane()]);
	}

	/**
	 * Home mid-loop with only fish left in the loop's rooms: the loop is over and this is its home visit, so the fish wait
	 * for the later roam instead of leading back out before the crafts.
	 */
	private void finishLoopAtHome(Requirements plan, Player player)
	{
		WorldPoint here = player.getWorldLocation();
		if (!opening.isActive() || opening.getStep() == 0 || orientation.isFirstRoomFocus()
				|| !opening.isRoomInterior(GauntletMap.HOME, here.getX(), here.getY()))
			return;
		if (OpeningBudget.isOnlyFishLeftInLoop(plan, gauntletMap, opening))
			opening.finishAtHome();
	}

	/**
	 * The opening loop's shard target and fish allowance while it or its home visit runs; the demi hunt's fish allowance;
	 * and the outer-room check done once a staff or the held upgrades make it unnecessary.
	 */
	private void planOpeningBudget(Requirements plan, Player player)
	{
		if (opening.isActive() || !attempt.isOpeningHomeFinished())
		{
			attempt.planOpeningShards(OpeningBudget.computeShardTarget(plan, gauntletMap, opening));
			if (opening.isActive())
				attempt.allowFish(OpeningBudget.computeFishAllowance(plan, attempt.getOpeningShardTarget(), gauntletMap, opening));
		}
		if (opening.isInitialized() && !opening.isActive() && !plan.getRow(Requirement.UPGRADES).isComplete())
			attempt.allowFish(OpeningBudget.computeHuntFishAllowance(plan, isHomeTile(player.getWorldLocation())));
		if (opening.isInitialized() && !opening.isActive() && !opening.hasCompletedSweep() && plan.hasStaff())
		{
			attempt.finishOpeningHome();
			attempt.markOuterRoomChecked();
		}
		// Held components/perfected weapons satisfy the search, regardless of which rooms were visited.
		if (plan.getRow(Requirement.UPGRADES).isComplete() && (!plan.isStaffRequired() || plan.hasStaffUpgrade()))
			attempt.markOuterRoomChecked();
	}

	/**
	 * The default task among the loaded objects: fill empty vials, else the post-loop home visit and outer-room search,
	 * else (with work left and no inventory action) the opening loop's or the nearest useful station or door.
	 */
	private void chooseObjectTask(Requirements plan, LocalPathfinder route, WorldView view, Player player)
	{
		// Read before the post-loop step, which may end the loop.
		boolean openingActive = opening.isActive();
		// At home, every bowl craft (perfected weapons, more vials) comes before filling vials.
		craftBeforeWater = opening.isInitialized() && isHomeTile(player.getWorldLocation()) && canCraft(plan);
		boolean claimed = fillVials(plan) || objectTask.updatePostLoop(plan, route, view, player);
		if (!claimed && !plan.isReady() && !plan.isInventoryAction())
			objectTask.chooseTarget(plan, route, view, player, openingActive);
		orientation.endInitialDoorScanIfTargeted(targeting.target);
	}

	private boolean fillVials(Requirements plan)
	{
		return isUseful(ObjectKind.WATER, plan) && routing.targetService(ObjectKind.WATER, true, "Fill empty vials");
	}

	/**
	 * Home status updates even when the plan is complete. With the room grid known, anywhere in the spawn room is home
	 * (it is 16 tiles across; the bowl is not at every edge). Before initialization, standing near the bowl is.
	 */
	private void observeHome(Player player)
	{
		if (opening.isInitialized())
		{
			attempt.observeHome(isHomeTile(player.getWorldLocation()));
			return;
		}
		LocalPoint origin = player.getLocalLocation();
		attempt.observeHome(objects.entrySet().stream().anyMatch(e -> e.getValue() == ObjectKind.BOWL
				&& e.getKey().getLocalLocation().distanceTo(origin) < 1024));
	}

	/**
	 * The door the guide shows now is the one the next update holds: the current task's, or the Next door a room tour
	 * ends at before a room is lit for a resource no known spot covers. Once the room's work is done the nearest-door
	 * choice then keeps that door instead of one a tile nearer (the tour costs whole ticks, so it can tie them).
	 */
	public void holdShownDoor()
	{
		targeting.heldDoor = getShownDoor();
	}

	private TileObject getShownDoor()
	{
		if (targeting.targetKind == ObjectKind.DOOR)
			return targeting.target;
		if (targeting.afterRoomExplore && targeting.nextKind == ObjectKind.DOOR)
			return targeting.nextTarget;
		return null;
	}

	public void add(TileObject object)
	{
		objectObserver.add(object);
	}

	public void remove(TileObject object)
	{
		objectObserver.remove(object);
	}

	public void scan()
	{
		objectObserver.scan();
	}

	public void observeGlobalMapOnly()
	{
		mapObserver.observeGlobalMapOnly();
	}

	public void observeNpc(NPC npc)
	{
		mapObserver.observeNpc(npc);
	}

	public void recordNpcDeath(NPC npc)
	{
		objectObserver.recordNpcDeath(npc);
	}

	public void recordNpcUnloaded(NPC npc)
	{
		objectObserver.recordNpcUnloaded(npc);
	}

	public void observePosition(Player player)
	{
		orientation.observePosition(player);
	}

	public void syncLoot(GroundLoot loot)
	{
		lastLoot = loot;
		if (gauntletMap.isInitialized())
			gauntletMap.updateLoot(loot.getEntries(), opening::getRoomAt);
	}

	public boolean prepareHomeSpace(Requirements plan, InventoryAdvice advice)
	{
		return priorities.homeSpace.prepareHomeSpace(plan, advice);
	}

	public void holdForHomeStorage(String instruction)
	{
		priorities.homeSpace.holdForHomeStorage(instruction);
	}

	public void applyPriorities(Requirements plan, Inventory inv, GroundLoot loot, boolean urgent)
	{
		priorities.applyPriorities(plan, inv, loot, urgent);
	}

	public void updateDemiRoomCue(Requirements plan)
	{
		demiCue.update(plan);
	}

	public void updateNextTask(Requirements plan)
	{
		next.update(plan);
	}


	public boolean isTeleportWorthwhile()
	{
		return priorities.homeSpace.isTeleportWorthwhile();
	}

	public void observeCrystalUsed(Requirements plan)
	{
		priorities.homeSpace.observeCrystalUsed(plan);
	}

	/** Target, path end, next target and loop state for the attempt recorder (GuidanceLog). */
	public void describeGuidance(AttemptFrame frame)
	{
		guidanceLog.describe(frame);
	}

	/** Whether a classified object is an open doorway or a lit door (its kind from objects). */
	public boolean isOpenPassage(TileObject object) { return isOpenPassage(object, objects.get(object)); }

	public boolean isOpenPassage(TileObject object, ObjectKind kind)
	{
		if (kind == ObjectKind.PASSAGE)
			return true;
		if (kind != ObjectKind.DOOR)
			return false;
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null || composition.getImpostorIds() == null)
			return false;
		ObjectComposition effective = composition.getImpostor();
		if (effective == null)
			return false;
		int morph = GauntletIds.object(effective.getId());
		return morph == ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_01_HM || morph == ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_02_HM;
	}

	public boolean canUnlockRoom(TileObject object)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return false;
		}
		if (composition.getImpostorIds() != null)
		{
			composition = composition.getImpostor();
		}
		return composition != null && RoomUnlock.canLight(composition.getId(), composition.getActions());
	}

	public boolean isActive(TileObject object, ObjectKind kind)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return false;
		}
		if (composition.getImpostorIds() == null)
		{
			return true;
		}
		ObjectComposition effective = composition.getImpostor();
		if (effective == null)
		{
			return false;
		}
		if (kind.resource == null)
		{
			return true;
		}
		// An exhausted resource may morph without replacing its scene object.
		return effective.getId() == object.getId();
	}

	public boolean isHomeTile(WorldPoint p)
	{
		return opening.isInitialized() && opening.getRoomAt(p).equals(GauntletMap.HOME);
	}

	public boolean isDiscoveredTile(WorldPoint p)
	{
		return orientation.isDiscoveredTile(p);
	}

	public boolean isResourceEligible(Requirement resource)
	{
		return resource != Requirement.FISH || attempt.getFishAllowance() > 0;
	}

	/** Enemy-dropped fish follow the same fish allowance as fishing spots; stored fish keep their own gate. */
	public boolean isLootAllowed(GroundLoot loot, GroundLoot.Entry entry)
	{
		return !GroundLoot.isFood(entry.id) || loot.isStoredFood(entry) || isResourceEligible(Requirement.FISH);
	}

	public boolean shouldSuppressEnemyMarkers() { return priorities.hunt.isOrdinarySuppressed(); }
	public boolean needsInitialDoorScan() { return orientation.isInitialDoorScanNeeded(); }
	public GauntletMap getGauntletMap() { return gauntletMap; }
	public OpeningRoute getOpening() { return opening; }
	public List<TileObject> getBarriers() { return barriers; }
	public PerimeterSearch getPerimeter() { return perimeter; }
	public int getFishAllowance() { return attempt.getFishAllowance(); }
	/** This update's task heads home. */
	public boolean isHomeReturn() { return attempt.isHomeReturn(); }
	public boolean isInventorySpaceReturn() { return attempt.isInventorySpaceReturn(); }
	public boolean needsStaffReturn() { return attempt.isStaffReturn(); }
	public boolean needsStaffTeleport() { return attempt.isStaffReturn() && priorities.homeSpace.isStaffWalkLong(); }
	public boolean isOutwardTrip() { return attempt.isOutwardTrip(); }
	public boolean needsTripTeleport() { return attempt.isTripTeleport(); }
	public boolean isOpeningRouteActive() { return opening.isActive(); }
	public boolean isAtHome() { return attempt.isAtHome(); }
	public boolean isSceptreRecovery() { return priorities.sceptre.isSceptreRecovery(); }
	public TileObject getToolStorage() { return toolStorage; }
	public Map<TileObject, ObjectKind> getObjects() { return Collections.unmodifiableMap(objects); }

	public String getPhaseLabel(Requirements plan)
	{
		if (plan == null)
			return "Waiting for preparation";
		if (plan.isReady())
			return "Ready for Hunllef";
		if (isReturningHome())
			return "Returning home";
		if (!opening.isInitialized())
			return "Locating starting room";
		if (attempt.isAtHome() && (attempt.isOpeningHomeFinished() || !opening.isActive()))
			return plan.getRow(Requirement.UPGRADES).isComplete() ? "Final preparation" : "Home preparation";
		if (opening.isActive())
			return "First loop (" + Math.max(1, Math.min(5, opening.getStep())) + "/5)";
		if (plan.needsOpeningStaff() && !plan.hasStaff())
			return getStaffSearchPhase();
		if (!plan.getRow(Requirement.UPGRADES).isComplete())
			return DEMI_HUNTING;
		return plan.needsExploration() ? "Finishing resources" : "Final preparation";
	}

	/** With no staff yet the phase follows the task: gathering or a home station on the way isn't the staff search. */
	private String getStaffSearchPhase()
	{
		ObjectKind kind = targeting.enemyTarget != null || targeting.groundTarget != null ? null : targeting.targetKind;
		if (kind != null && kind.resource != null)
			return "Finishing resources";
		return kind != null && kind.isHomeStation() ? "Home preparation" : "Finding staff supplies";
	}

	/**
	 * At home the pump is the current task: filling comes before cooking and storing fish
	 * (HomeSpace.prepareHomeSpace).
	 */
	public boolean shouldFillBeforeStorage() { return isAtHome() && targeting.targetKind == ObjectKind.WATER; }

	/** At home, cooking and storing fish comes before the bowl because crafts wait for slots. */
	public boolean shouldStoreBeforeCrafting() { return isAtHome() && priorities.homeSpace.shouldStoreBeforeCrafting(); }

	/** The full-inventory trip home will cook and store before the bowl. */
	public boolean isFullReturnStorageFirst() { return priorities.homeReturns.isFullReturnStorageFirst(); }

	/** Client-thread inspection accessors. */
	public GauntletRoom getCurrentMapRoom()
	{
		return mapObserver.getCurrentMapRoom();
	}

	public GauntletRoom getDestinationMapRoom()
	{
		GauntletRoom current = getCurrentMapRoom();
		if (current == null)
			return null;
		if (targeting.groundTarget != null)
			return gauntletMap.getRoom(opening.getRoomAt(targeting.groundTarget));
		if (targeting.enemyTarget != null)
			return current;
		ObjectKind kind = targeting.targetKind;
		if (targeting.target != null && (kind == null || !kind.isDoorway()))
		{
			WorldPoint at = targeting.target.getWorldLocation();
			return gauntletMap.getRoom(opening.getRoomAt(at));
		}
		if (targeting.inspectionGoal != null)
			return gauntletMap.getRoom(targeting.inspectionGoal);
		if (kind != null && kind.isDoorway() && opening.isActive())
			return gauntletMap.getRoom(orientation.isFirstRoomFocus() ? orientation.getFirstRoom() : opening.getNextRoom());
		if (targeting.targetKind == ObjectKind.PASSAGE && !targeting.path.isEmpty())
		{
			Point end = targeting.path.get(targeting.path.size() - 1);
			WorldView view = client.getTopLevelWorldView();
			if (view != null)
				return gauntletMap.getRoom(opening.getRoomAt(view.getBaseX() + end.getX(), view.getBaseY() + end.getY()));
		}
		if (targeting.targetKind == ObjectKind.DOOR && targeting.target != null)
		{
			Point relative = gauntletMap.getRelative(current);
			Point center = opening.getRoomCenter(relative);
			WorldPoint door = targeting.target.getWorldLocation();
			int dx = door.getX() - center.getX(), dy = door.getY() - center.getY();
			return gauntletMap.getRoom(new Point(relative.getX()
					+ (Math.abs(dx) > Math.abs(dy) ? Integer.signum(dx) : 0),
					relative.getY() + (Math.abs(dy) >= Math.abs(dx) ? Integer.signum(dy) : 0)));
		}
		return null;
	}

	/**
	 * Living, loaded demi-bosses whose drop is still needed for the selected loadout (same ranking as the demi search).
	 * Rendering reads this on the client thread.
	 */
	public List<NPC> getNeededDemis()
	{
		return demiCue.findNeededDemis();
	}

	WorldArea getDemiRoomArea()
	{
		return demiCue.getRoomArea();
	}

	public TileObject getTarget() { return targeting.target; }
	/** What the current target object is (null for a walk, loot or enemy target). */
	public ObjectKind getTargetKind() { return targeting.targetKind; }
	public NPC getEnemyTarget() { return targeting.enemyTarget; }
	/** Highlight-only kill cue alongside resource gathering; never routed and never the current task. */
	public NPC getSideEnemyTarget() { return targeting.sideEnemyTarget; }
	public WorldPoint getGroundTarget() { return targeting.groundTarget; }
	public TileObject getNextTarget() { return targeting.nextTarget; }
	public NPC getNextEnemy() { return targeting.nextEnemy; }
	public ObjectKind getNextKind() { return targeting.nextKind; }
	public List<Point> getPath() { return targeting.path; }
	/** Next's path from the current path's end; drawn thinner, in the Next color. */
	public List<Point> getNextPath() { return targeting.nextPath; }

	public Requirement getTargetResource()
	{
		return targeting.targetKind == null ? null : targeting.targetKind.resource;
	}

	public Requirement getNextResource()
	{
		return targeting.nextKind == null ? null : targeting.nextKind.resource;
	}

	/** Open passages are walking waypoints, never another node interaction. */
	public boolean isEnteringOpeningRoom()
	{
		return targeting.targetKind == ObjectKind.PASSAGE || (targeting.target != null && isOpenPassage(targeting.target, targeting.targetKind));
	}

	public Shape getTargetClickbox()
	{
		if (targeting.target == null || targeting.targetKind.resource != null || isEnteringOpeningRoom())
			return null;
		if (targeting.targetKind == ObjectKind.DOOR && !canUnlockRoom(targeting.target))
			return null;
		return targeting.target.getClickbox();
	}

	public LocalPoint getTargetLocation()
	{
		if (targeting.groundTarget != null)
			return LocalPoint.fromWorld(client.getTopLevelWorldView(), targeting.groundTarget);
		if (targeting.enemyTarget != null)
			return targeting.enemyTarget.isDead() ? null : targeting.enemyTarget.getLocalLocation();
		if (!isEnteringOpeningRoom())
			return targeting.target == null ? null : targeting.target.getLocalLocation();
		if (targeting.path.isEmpty())
			return null;
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
			return null;
		Point end = targeting.path.get(targeting.path.size() - 1);
		return LocalPoint.fromScene(end.getX(), end.getY(), view);
	}

	public String getInstruction()
	{
		if (tour.isOpeningResourcePathMissing() && targeting.targetKind != null && targeting.targetKind.resource != null)
			return opening.getLabel(true) + ": " + getGatherLabel(targeting.targetKind, targeting.target) + " (gather manually; path unavailable)";
		if (attempt.getInstruction() != null)
			return attempt.getInstruction();
		if (targeting.targetKind == null)
			return orientation.isAwaitingOpeningOrientation() ? orientation.openingWaitReason : null;
		return opening.isActive() && !isPondStopTarget() ? opening.getLabel(targeting.targetKind.resource != null) + ": " + targeting.targetKind.label
				: targeting.targetKind.label;
	}

	/** The task is a node outside the loop's current room: an off-loop pond stop on the way home, not a loop room's tour. */
	private boolean isPondStopTarget()
	{
		return targeting.targetKind.resource != null && targeting.target != null && targeting.target.getWorldLocation() != null
				&& !opening.getRoomAt(targeting.target.getWorldLocation()).equals(opening.getCurrentRoom());
	}

	public String getSceneInstruction()
	{
		// A resource on the way names itself, not the room trip: a grym leaf over "Check bear room for spike".
		if (targeting.enemyTarget != null)
			return "Defeat";
		if (targeting.targetKind == ObjectKind.DOOR)
			return "Light room";
		if (targeting.targetKind != null && targeting.targetKind.resource != null && !tour.isOpeningResourcePathMissing())
			return getGatherLabel(targeting.targetKind, targeting.target);
		return getInstruction();
	}

	public String getNextTaskInstruction()
	{
		String label = targeting.nextLabel;
		if (label == null && targeting.nextKind != null)
			label = targeting.nextKind.label;
		// A Next node counts its own share like the current one; the same resource's next node only what is left
		// after the current node (×4 on each of two ponds, not ×8). Trip labels keep their own "×n".
		ObjectKind kind = targeting.nextKind;
		if (label == null || kind == null || kind.resource == null || targeting.nextTarget == null || !label.equals(kind.label))
			return label;
		int before = kind == targeting.targetKind ? harvests.getGatherCount(kind, targeting.target) : 0;
		int count = harvests.getGatherCount(kind, targeting.nextTarget, before);
		return count > 0 ? kind.label + " ×" + count : kind.label;
	}

	/** The manual-gather warning a room tour gives when a needed node has no path; shown without the route line. */
	public String getRouteWarning()
	{
		return targeting.roomRouteSummary != null && targeting.roomRouteSummary.equals(PATHLESS_RESOURCE) ? targeting.roomRouteSummary : null;
	}

	/** The tour and room route summaries joined. */
	public String getTourSummary()
	{
		if (targeting.roomRouteSummary == null)
			return targeting.tourSummary;
		if (targeting.tourSummary == null)
			return targeting.roomRouteSummary;
		return targeting.roomRouteSummary + " | " + targeting.tourSummary;
	}

	boolean isUseful(ObjectKind kind, Requirements plan)
	{
		if (kind.resource != null)
		{
			return isResourceEligible(kind.resource) && plan.getRow(kind.resource).getMissing() > 0;
		}
		switch (kind)
		{
			case BOWL:
				return canCraft(plan);
			// Crafting always comes first at home: cooking is never the current or next task while a recipe is
			// available.
			case RANGE:
				return !canCraft(plan) && plan.getRow(Requirement.UPGRADES).isComplete()
						&& plan.getInventory().getHeld(ItemID.GAUNTLET_RAW_FOOD) > 0
						&& plan.getInventory().getHeld(ItemID.GAUNTLET_FOOD) < plan.getFoodTarget();
			case WATER:
				return plan.needsWater() && !craftBeforeWater;
			default:
				return false;
		}
	}

	String getEnemyReason(Requirements plan, Inventory inventory)
	{
		return (opening.isActive() || !attempt.isOpeningHomeFinished())
				? OpeningBudget.getEnemyReason(plan, inventory, attempt.getOpeningShardTarget())
				: EnemyRules.getReason(plan, inventory, EnemyRules.isGatheringFirst(plan)
				&& (hasKnownNeededNode(plan) || isUnexploredRoomReachable()));
	}

	/**
	 * An undiscovered room the map can reach by lighting doors. With a resource still short and no known node, opening
	 * rooms to find one beats a shard-only kill, even at an already scouted enemy.
	 */
	private boolean isUnexploredRoomReachable()
	{
		if (client.getLocalPlayer() == null)
			return false;
		WorldPoint here = client.getLocalPlayer().getWorldLocation();
		Point current = opening.getRoomAt(here);
		for (GauntletRoom room : gauntletMap.getRooms())
		{
			if (room.isDiscovered() || room.isHunllefRoom())
				continue;
			if (!gauntletMap.findExplorationPath(current, gauntletMap.getRelative(room), p -> true).isEmpty())
				return true;
		}
		return false;
	}

	/** A known active node of a still-needed resource, in this room or one the map can route to. */
	private boolean hasKnownNeededNode(Requirements plan)
	{
		if (client.getLocalPlayer() == null)
			return false;
		WorldPoint here = client.getLocalPlayer().getWorldLocation();
		Point current = opening.getRoomAt(here);
		for (Map.Entry<Point, ObjectKind> e : gauntletMap.findActiveNodes().entrySet())
		{
			if (e.getValue().resource == null || !isUseful(e.getValue(), plan))
				continue;
			Point room = opening.getRoomAt(e.getKey().getX(), e.getKey().getY());
			if (room.equals(current) || !gauntletMap.findPath(current, room).isEmpty())
				return true;
		}
		return false;
	}

	/** This tick's bowl state, for the plan TickHandler settled on: home crafts planned at the visit's start stay in it. */
	public void startHomeVisit(HomeVisit visit) { homeVisit = visit; }

	/** The live plan's bowl state; a plan seen for the first time starts with none. */
	public HomeVisit getHomeVisit(Requirements plan)
	{
		if (homeVisit == null || !homeVisit.isFor(plan))
			homeVisit = new HomeVisit(plan);
		return homeVisit;
	}

	/** A recipe is craftable now, by the home visit once its crafts were planned; forecast plans have no visit. */
	boolean canCraft(Requirements plan) { return homeVisit != null && homeVisit.isFor(plan) ? homeVisit.canCraft() : plan.canCraft(); }

	List<Requirements.Craft> getCrafts(Requirements plan) { return homeVisit != null && homeVisit.isFor(plan) ? homeVisit.getCrafts() : plan.getCrafts(); }

	/**
	 * Until the player leaves spawn after the first home visit (and on any home visit during the circle), crafting is
	 * the first-home plan only: staff, T1 armor, vials. Never the second weapon, bow or halberd.
	 */
	private void restrictFirstHomeCrafts(Requirements plan)
	{
		if (!opening.isInitialized() || plan.isReady())
			return;
		if (attempt.advanceFirstHome(opening.isActive()))
			getHomeVisit(plan).planCrafts();
	}

	/**
	 * A resource task's label with how many more this node gives toward the need, e.g. "Catch paddlefish ×3".
	 * The number counts down as items enter the inventory, and is the node's own share, never the room's
	 * or the plan's total. Just the label when the count is unknown or the node gives one item (grym leaves).
	 */
	String getGatherLabel(ObjectKind kind, TileObject node)
	{
		int count = harvests.getGatherCount(kind, node);
		return count > 0 ? kind.label + " ×" + count : kind.label;
	}

	boolean isReturningHome() { return attempt.isReturningHome(); }

	static boolean hasSceptre(Inventory inv)
	{
		return inv.getOwned(ItemID.GAUNTLET_SCEPTRE_HM) + inv.getOwned(ItemID.GAUNTLET_SCEPTRE) > 0;
	}

	static boolean isSceptre(int id)
	{
		return id == ItemID.GAUNTLET_SCEPTRE_HM || id == ItemID.GAUNTLET_SCEPTRE;
	}
}
