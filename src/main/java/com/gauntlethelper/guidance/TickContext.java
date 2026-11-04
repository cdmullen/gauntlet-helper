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

import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.map.OpeningRoute;
import com.gauntlethelper.map.PerimeterSearch;
import com.gauntlethelper.plan.DemiBossTracker;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.Requirements;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * One run of the task steps' inputs, read-only to the steps. It is built before the first step, once update and the
 * inventory advice have run, and again when the opening ends mid-run and update runs a second time. The player's
 * position is read once, when a step first needs it.
 */
final class TickContext
{
	final Requirements plan;
	final Inventory inv;
	final GroundLoot loot;
	final boolean urgent;
	/** Routes from the player over the loaded tiles; null while the scene isn't loaded (awaitRoute claims then). */
	final LocalPathfinder route;
	final GauntletMap map;
	final OpeningRoute opening;
	final GauntletMode mode;
	/** The guided scene objects, unmodifiable. */
	final Map<TileObject, ObjectKind> objects;
	/** The attempt's demi kills; null when none is set. */
	final DemiBossTracker demiHistory;
	/** The demi sweep, which remembers its direction and destination when selecting. */
	final PerimeterSearch perimeter;
	private final Reads reads;
	private boolean located;
	private WorldView view;
	private WorldPoint here;
	private boolean inDemiObjective;
	private boolean retrieveFood;

	/** What a step reads from the live game and the guidance predicates. */
	interface Reads
	{
		WorldView getView();
		WorldPoint getHere();
		GauntletRoom getCurrentRoom();
		boolean isAtHome();
		boolean isInDemiObjective();
		Point findOpeningWorkRoom(WorldPoint here);
		boolean isUseful(ObjectKind kind, Requirements plan);
		boolean isActive(TileObject object, ObjectKind kind);
		boolean isLootAllowed(GroundLoot loot, GroundLoot.Entry entry);
		LocalPathfinder getKnownRoute(LocalPathfinder route, WorldView view);
		Set<Point> findReachableRoomSteps(Point room);
		boolean isInventorySpaceReturn();
		HomeVisit getHomeVisit(Requirements plan);
		boolean canCraft(Requirements plan);
		boolean canNavigateTo(Point room);
		Point findMapRoomAt(WorldPoint here);
		boolean isAwaitingDemiDrops();
		boolean isStaffReturn();
		boolean isOuterRoomChecked();
		boolean isOutwardTrip();
		boolean hasTask();
		List<NPC> getNeededDemis();
		boolean isInFightWith(NPC npc);
		int countDoorSteps(LocalPathfinder route, Point room, Point next);
		boolean isTeleportWorthwhile();
		int getTourBudget();
		int getSecondsLeft();
		boolean isRoomSurelyCovering(Requirements plan, Point room);
		Decision findServiceTask(ObjectKind service, boolean includeFishingSpots);
		Decision findStationTask(ObjectKind station);
		Player getPlayer();
		boolean shouldHighlightEnemies();
		boolean isOrdinarySuppressed();
		String getEnemyReason(Requirements plan, Inventory inv);
		List<LocalPathfinder.Goal> findRoomExitGoals(Point room, List<TileObject> candidates);
		ObjectKind getTaskKind();
		boolean isResourceTask();
		boolean isHomeReturn();
		TileObject getTaskTarget();
		List<TileObject> getBarriers();
		List<Requirements.Craft> getCrafts(Requirements plan);
		List<Point> getTaskPath();
		NPC getTaskEnemy();
		WorldPoint getTaskGround();
		String getNextPickupLabel();
		boolean isDemiFight();
		Point getAfterKillRoom();
		String getAfterKillLabel();
		String getAfterKillObjective();
		NPC findVisibleDemi(Point room, Requirements plan);
		TileObject findExitToward(Point room);
		boolean isOpenPassage(TileObject object);
		TileObject findNodeOnWay(Point room);
		TileObject findDoorOnWay(Point room);
		TileObject findNodeIn(Point room, String label);
		TileObject getTourFirst();
		TileObject getTourNext();
		ObjectKind getTourNextKind();
		LocalPathfinder.Goal getTourNextGoal();
		Point getTourGoal();
		String getAfterRoomLabel();
		boolean isAfterRoomExplore();
		ObjectKind getHomeArrivalStation(HomeCraftPlan home, Requirements plan);
		List<LocalPathfinder.Goal> findStationGoals(ObjectKind station, List<TileObject> candidates);
		LocalPathfinder.Goal buildServiceGoal(TileObject object, ObjectKind kind, int index);
		boolean isTripTeleport();
		GauntletRoom getDestinationRoom();
		int getOpeningStartRoom();
		ObjectKind getNextKind();
		TileObject getToolStorage();
		boolean hasHomeShortcut(Point room);
		Map<ObjectKind, Integer> countSurelyIn(Requirements plan, Point room);

		// Hand-overs from a converted step to classes not converted yet, which write GuidanceState themselves.
		void setTourGoal(Point room);
		void setAfterRoomExplore(boolean explore);
		void setTourExits(Point room, Set<Point> exits);
		void setAfterRoomLabel(String label);
		void setRoomTerminalService(ObjectKind service);
		void setTourSummary(String summary);
		ObjectKind gatherCurrentRoom(Requirements plan, WorldView view, WorldPoint here);
	}

	TickContext(Requirements plan, Inventory inv, GroundLoot loot, boolean urgent, LocalPathfinder route, GauntletMap map, OpeningRoute opening,
		GauntletMode mode, Map<TileObject, ObjectKind> objects, DemiBossTracker demiHistory, PerimeterSearch perimeter, Reads reads)
	{
		this.plan = plan;
		this.inv = inv;
		this.loot = loot;
		this.urgent = urgent;
		this.route = route;
		this.map = map;
		this.opening = opening;
		this.mode = mode;
		this.objects = objects;
		this.demiHistory = demiHistory;
		this.perimeter = perimeter;
		this.reads = reads;
	}

	/** The context for this run of the task steps, reading through guidance. */
	static TickContext of(Guidance guidance, Requirements plan, Inventory inv, GroundLoot loot, boolean urgent)
	{
		return new TickContext(plan, inv, loot, urgent, guidance.targeting.lastRoute, guidance.gauntletMap, guidance.opening, guidance.getMode(),
			guidance.getObjects(), guidance.demiHistory, guidance.perimeter, new GuidanceReads(guidance));
	}

	/**
	 * A context for the Next pass, and for asking a converted step's helper from outside the step list (HomeSpace's
	 * Next preview). They have no loot or urgency to hand and read neither.
	 */
	static TickContext forPreview(Guidance guidance, Requirements plan, Inventory inv)
	{
		return of(guidance, plan, inv, null, false);
	}

	private void locatePlayer()
	{
		if (located)
			return;
		located = true;
		view = reads.getView();
		here = reads.getHere();
		inDemiObjective = reads.isInDemiObjective();
		retrieveFood = plan.isReadyForStoredFood();
	}

	WorldView getView()
	{
		locatePlayer();
		return view;
	}

	WorldPoint getHere()
	{
		locatePlayer();
		return here;
	}

	/** The player is in the active demi room objective's room. */
	boolean isInDemiObjective()
	{
		locatePlayer();
		return inDemiObjective;
	}

	/** The final stored-fish pickup is open (Requirements.isReadyForStoredFood). */
	boolean retrieveFood()
	{
		locatePlayer();
		return retrieveFood;
	}

	GauntletRoom getCurrentRoom() { return reads.getCurrentRoom(); }
	boolean isAtHome() { return reads.isAtHome(); }
	/** The room the opening's work is in; the player's own room once the opening is over. */
	Point getOpeningWorkRoom() { return reads.findOpeningWorkRoom(getHere()); }
	boolean isUseful(ObjectKind kind) { return reads.isUseful(kind, plan); }
	boolean isActive(TileObject object, ObjectKind kind) { return reads.isActive(object, kind); }
	boolean isLootAllowed(GroundLoot.Entry entry) { return reads.isLootAllowed(loot, entry); }
	/** The route restricted to known rooms, worked out once a tick. */
	LocalPathfinder getKnownRoute() { return reads.getKnownRoute(route, getView()); }
	/** The room's neighbors reachable this tick, worked out once a tick for each room. */
	Set<Point> findReachableRoomSteps(Point room) { return reads.findReachableRoomSteps(room); }
	/** The full-inventory return is latched from an earlier tick. */
	boolean isInventorySpaceReturn() { return reads.isInventorySpaceReturn(); }
	/** The live plan's bowl state; asking starts one for a plan seen for the first time. */
	HomeVisit getHomeVisit() { return reads.getHomeVisit(plan); }
	/** A recipe is craftable now, by the home visit once its crafts were planned. */
	boolean canCraft() { return reads.canCraft(plan); }
	/** Publishing Decision.walkTo for this room will build a route: a room route to it exists. */
	boolean canWalkTo(Point room) { return reads.canNavigateTo(room); }
	/** The player's room for the hunt: a doorway tile of an unlit room counts as the lit room it opens from. */
	Point getPlayerMapRoom() { return reads.findMapRoomAt(getHere()); }
	/** The demi room objective here still waits for its drops. */
	boolean isAwaitingDemiDrops() { return reads.isAwaitingDemiDrops(); }
	boolean isStaffReturn() { return reads.isStaffReturn(); }
	boolean isOuterRoomChecked() { return reads.isOuterRoomChecked(); }
	boolean isOutwardTrip() { return reads.isOutwardTrip(); }
	/** A target, enemy, ground pile or path is already up this tick. */
	boolean hasTask() { return reads.hasTask(); }
	/** Living, loaded demis whose drop is still needed. */
	List<NPC> getNeededDemis() { return reads.getNeededDemis(); }
	/** The player is targeting this NPC or it is targeting the player. */
	boolean isInFightWith(NPC npc) { return reads.isInFightWith(npc); }
	/** Walking tiles along route to the nearest loaded door into a neighboring room; MAX_VALUE when unknown. */
	int countDoorSteps(LocalPathfinder route, Point room, Point next) { return reads.countDoorSteps(route, room, next); }
	boolean isTeleportWorthwhile() { return reads.isTeleportWorthwhile(); }
	/** Rooms an optimized tour may cross before the return-home reserve of prep time runs out. */
	int getTourBudget() { return reads.getTourBudget(); }

	/** The prep timer's seconds left; negative while it hasn't been read. */
	int getSecondsLeft() { return reads.getSecondsLeft(); }
	/** This room's nodes surely cover what the plan still needs of the resources they give. */
	boolean isRoomSurelyCovering(Point room) { return reads.isRoomSurelyCovering(plan, room); }
	/** The nearest active service object in reach as a task; null when none. */
	Decision findServiceTask(ObjectKind service, boolean includeFishingSpots) { return reads.findServiceTask(service, includeFishingSpots); }
	/** The nearest active station on the player's plane as a task; null when none is in reach. */
	Decision findStationTask(ObjectKind station) { return reads.findStationTask(station); }
	Player getPlayer() { return reads.getPlayer(); }
	/** Kill guidance is on (the setting; off with no config yet). */
	boolean shouldHighlightEnemies() { return reads.shouldHighlightEnemies(); }
	/** Forecast demi shards cover the shortfall, so ordinary kill cues are off (DemiHunt). */
	boolean isOrdinarySuppressed() { return reads.isOrdinarySuppressed(); }
	/** What an ordinary kill is for right now; null when none is needed. */
	String getEnemyReason() { return reads.getEnemyReason(plan, inv); }
	/** The goals that end a tour of this room, adding new objects to candidates. */
	List<LocalPathfinder.Goal> findRoomExitGoals(Point room, List<TileObject> candidates) { return reads.findRoomExitGoals(room, candidates); }
	/** The kind of the task published so far this tick, null when there is none. */
	ObjectKind getTaskKind() { return reads.getTaskKind(); }
	/** The task published so far this tick is a resource node, with no enemy or ground pile over it. */
	boolean isResourceTask() { return reads.isResourceTask(); }
	/** The task published so far this tick heads home. */
	boolean isHomeReturn() { return reads.isHomeReturn(); }
	/** The object of the task published so far this tick, null when there is none. */
	TileObject getTaskTarget() { return reads.getTaskTarget(); }
	/** The loaded barriers into Hunllef's room. */
	List<TileObject> getBarriers() { return reads.getBarriers(); }
	/** The live plan's recipes: its home visit's once they are planned, else its own. */
	List<Requirements.Craft> getCrafts() { return reads.getCrafts(plan); }
	/** The path of the task published so far this tick; empty when there is none. */
	List<Point> getTaskPath() { return reads.getTaskPath(); }
	NPC getTaskEnemy() { return reads.getTaskEnemy(); }
	WorldPoint getTaskGround() { return reads.getTaskGround(); }
	/** Next text for another needed pickup after the current one; null when none. */
	String getNextPickupLabel() { return reads.getNextPickupLabel(); }
	/** The demi fight is on, so Next is the move after the kill. */
	boolean isDemiFight() { return reads.isDemiFight(); }
	/** Where the move after the kill goes; null with no door to show. */
	Point getAfterKillRoom() { return reads.getAfterKillRoom(); }
	/** The move after the kill; null when it is not certain. */
	String getAfterKillLabel() { return reads.getAfterKillLabel(); }
	/** The cue for a demi in the move's room as the forecast sees it, kill and drop counted; null when it has none. */
	String getAfterKillObjective() { return reads.getAfterKillObjective(); }
	/** The demi the plan wants from this room, when it is in the scene. */
	NPC findVisibleDemi(Point room) { return reads.findVisibleDemi(room, plan); }
	/** The door or doorway out of the player's room on the way to this room. */
	TileObject findExitToward(Point room) { return reads.findExitToward(room); }
	boolean isOpenPassage(TileObject object) { return reads.isOpenPassage(object); }
	/** A needed node in a lit room on the way to this room. */
	TileObject findNodeOnWay(Point room) { return reads.findNodeOnWay(room); }
	/** The door to light on the way to this room. */
	TileObject findDoorOnWay(Point room) { return reads.findDoorOnWay(room); }
	/** The node for this move's text in the room it heads for. */
	TileObject findNodeIn(Point room, String label) { return reads.findNodeIn(room, label); }
	/** The room tour's first stop. */
	TileObject getTourFirst() { return reads.getTourFirst(); }
	/** The room tour's stop after its first; null when it has none. */
	TileObject getTourNext() { return reads.getTourNext(); }
	ObjectKind getTourNextKind() { return reads.getTourNextKind(); }
	/** The tour's own goal for its next stop; null when it planned none. */
	LocalPathfinder.Goal getTourNextGoal() { return reads.getTourNextGoal(); }
	/** The room setTourGoal aims at. */
	Point getTourGoal() { return reads.getTourGoal(); }
	/** The Next text once this room's tour is done. */
	String getAfterRoomLabel() { return reads.getAfterRoomLabel(); }
	/** This room's tour ends at a door lighting a new room. */
	boolean isAfterRoomExplore() { return reads.isAfterRoomExplore(); }
	/** The first home station on arrival for these crafts; null when none. */
	ObjectKind getHomeArrivalStation(HomeCraftPlan home) { return reads.getHomeArrivalStation(home, plan); }
	/** Goals for this station's active objects, adding them to candidates. */
	List<LocalPathfinder.Goal> findStationGoals(ObjectKind station, List<TileObject> candidates) { return reads.findStationGoals(station, candidates); }
	/** The object's interaction goal. */
	LocalPathfinder.Goal buildServiceGoal(TileObject object, ObjectKind kind, int index) { return reads.buildServiceGoal(object, kind, index); }
	boolean isTripTeleport() { return reads.isTripTeleport(); }
	/** The room the current task leads into; null when there is none. */
	GauntletRoom getDestinationRoom() { return reads.getDestinationRoom(); }
	/** The opening's start encoding; negative before it is known. */
	int getOpeningStartRoom() { return reads.getOpeningStartRoom(); }
	/** The kind of the Next published so far this tick, null when there is none. */
	ObjectKind getNextKind() { return reads.getNextKind(); }
	/** The spawn room's tool storage; null until it has loaded. */
	TileObject getToolStorage() { return reads.getToolStorage(); }
	/** A route home from this room would light a door. */
	boolean hasHomeShortcut(Point room) { return reads.hasHomeShortcut(room); }
	/** What this room's useful nodes surely still give, by kind; the caller may add to the map. */
	Map<ObjectKind, Integer> countSurelyIn(Point room) { return reads.countSurelyIn(plan, room); }

	/** Tours and exits this tick aim at this room and the map shows it, until a pause clears it. */
	void setTourGoal(Point room) { reads.setTourGoal(room); }
	/** This room's tour ends at a door lighting a new room. */
	void setAfterRoomExplore(boolean explore) { reads.setAfterRoomExplore(explore); }

	/** The neighbors of room whose doors may each end its tour. */
	void setTourExits(Point room, Set<Point> exits) { reads.setTourExits(room, exits); }
	/** The Next text once this room's tour is done. */
	void setAfterRoomLabel(String label) { reads.setAfterRoomLabel(label); }
	/** This room's tour ends at this home station. */
	void setRoomTerminalService(ObjectKind service) { reads.setRoomTerminalService(service); }
	/** The trip forecast line shown with the route summary. */
	void setTourSummary(String summary) { reads.setTourSummary(summary); }
	/** This room's tour becomes the task: the kind of its first stop, null when the room has no needed node. */
	ObjectKind gatherCurrentRoom(WorldPoint at) { return reads.gatherCurrentRoom(plan, getView(), at); }

	private static final class GuidanceReads implements Reads
	{
		private final Guidance guidance;

		GuidanceReads(Guidance guidance)
		{
			this.guidance = guidance;
		}

		@Override
		public WorldView getView() { return guidance.client.getTopLevelWorldView(); }

		@Override
		public WorldPoint getHere() { return guidance.client.getLocalPlayer().getWorldLocation(); }

		@Override
		public GauntletRoom getCurrentRoom() { return guidance.getCurrentMapRoom(); }

		@Override
		public boolean isAtHome() { return guidance.isAtHome(); }

		@Override
		public boolean isInDemiObjective()
		{
			GauntletRoom objective = guidance.demiCue.demiRoom.getRoom();
			return objective != null && objective == guidance.getCurrentMapRoom();
		}

		@Override
		public Point findOpeningWorkRoom(WorldPoint here) { return guidance.orientation.findOpeningWorkRoom(here); }

		@Override
		public boolean isUseful(ObjectKind kind, Requirements plan) { return guidance.isUseful(kind, plan); }

		@Override
		public boolean isActive(TileObject object, ObjectKind kind) { return guidance.isActive(object, kind); }

		@Override
		public boolean isLootAllowed(GroundLoot loot, GroundLoot.Entry entry) { return guidance.isLootAllowed(loot, entry); }

		@Override
		public LocalPathfinder getKnownRoute(LocalPathfinder route, WorldView view) { return guidance.routing.getKnownRoute(route, view); }

		@Override
		public Set<Point> findReachableRoomSteps(Point room) { return guidance.routing.findReachableRoomSteps(room); }

		@Override
		public boolean isInventorySpaceReturn() { return guidance.attempt.isInventorySpaceReturn(); }

		@Override
		public HomeVisit getHomeVisit(Requirements plan) { return guidance.getHomeVisit(plan); }

		@Override
		public boolean canCraft(Requirements plan) { return guidance.canCraft(plan); }

		@Override
		public boolean canNavigateTo(Point room) { return guidance.routing.canNavigateTo(room); }

		@Override
		public Point findMapRoomAt(WorldPoint here) { return guidance.mapObserver.getRoomAt(here); }

		@Override
		public boolean isAwaitingDemiDrops() { return guidance.demiCue.demiRoom.isAwaitingLoot(guidance.getCurrentMapRoom()); }

		@Override
		public boolean isStaffReturn() { return guidance.attempt.isStaffReturn(); }

		@Override
		public boolean isOuterRoomChecked() { return guidance.attempt.isOuterRoomChecked(); }

		@Override
		public boolean isOutwardTrip() { return guidance.isOutwardTrip(); }

		@Override
		public boolean hasTask()
		{
			GuidanceState targeting = guidance.targeting;
			return targeting.target != null || targeting.targetKind != null || targeting.enemyTarget != null || targeting.groundTarget != null
				|| !targeting.path.isEmpty();
		}

		@Override
		public List<NPC> getNeededDemis() { return guidance.getNeededDemis(); }

		@Override
		public boolean isInFightWith(NPC npc)
		{
			Player player = guidance.client.getLocalPlayer();
			return player.getInteracting() == npc || npc.getInteracting() == player;
		}

		@Override
		public int countDoorSteps(LocalPathfinder route, Point room, Point next) { return guidance.routing.countDoorSteps(route, room, next); }

		@Override
		public boolean isTeleportWorthwhile() { return guidance.isTeleportWorthwhile(); }

		@Override
		public int getTourBudget()
		{
			if (guidance.planningSeconds < 0)
				return OptimizedTrip.NO_TOUR_LIMIT;
			return OptimizedTrip.computeTourBudget(guidance.planningSeconds, guidance.config.returnWarningSeconds());
		}

		@Override
		public int getSecondsLeft() { return guidance.planningSeconds; }

		@Override
		public boolean isRoomSurelyCovering(Requirements plan, Point room) { return guidance.harvests.isRoomSurelyCovering(plan, room); }

		@Override
		public Decision findServiceTask(ObjectKind service, boolean includeFishingSpots)
		{
			return guidance.routing.findServiceTask(service, includeFishingSpots);
		}

		@Override
		public Decision findStationTask(ObjectKind station) { return guidance.routing.findStationTask(station); }

		@Override
		public void setTourGoal(Point room) { guidance.targeting.inspectionGoal = room; }

		@Override
		public void setAfterRoomExplore(boolean explore) { guidance.targeting.afterRoomExplore = explore; }

		@Override
		public void setTourExits(Point room, Set<Point> exits)
		{
			guidance.targeting.tourExitRoom = room;
			guidance.targeting.tourExits = exits;
		}

		@Override
		public void setAfterRoomLabel(String label) { guidance.targeting.afterRoomLabel = label; }

		@Override
		public void setRoomTerminalService(ObjectKind service) { guidance.targeting.roomTerminalService = service; }

		@Override
		public void setTourSummary(String summary) { guidance.targeting.tourSummary = summary; }

		@Override
		public ObjectKind gatherCurrentRoom(Requirements plan, WorldView view, WorldPoint here)
		{
			GuidanceState targeting = guidance.targeting;
			return guidance.tour.gatherCurrentRoom(plan, targeting.lastRoute, view, here) ? targeting.targetKind : null;
		}

		@Override
		public Player getPlayer() { return guidance.client.getLocalPlayer(); }

		@Override
		public boolean shouldHighlightEnemies() { return guidance.config != null && guidance.config.highlightTargetedEnemies(); }

		@Override
		public boolean isOrdinarySuppressed() { return guidance.priorities.hunt.isOrdinarySuppressed(); }

		@Override
		public String getEnemyReason(Requirements plan, Inventory inv) { return guidance.getEnemyReason(plan, inv); }

		@Override
		public List<LocalPathfinder.Goal> findRoomExitGoals(Point room, List<TileObject> candidates)
		{
			return guidance.routing.findRoomExitGoals(room, candidates);
		}

		@Override
		public ObjectKind getTaskKind() { return guidance.targeting.targetKind; }

		@Override
		public boolean isResourceTask()
		{
			GuidanceState targeting = guidance.targeting;
			return targeting.enemyTarget == null && targeting.groundTarget == null && targeting.target != null && targeting.targetKind != null
				&& targeting.targetKind.resource != null;
		}

		@Override
		public boolean isHomeReturn() { return guidance.attempt.isHomeReturn(); }

		@Override
		public TileObject getTaskTarget() { return guidance.targeting.target; }

		@Override
		public List<TileObject> getBarriers() { return guidance.barriers; }

		@Override
		public List<Requirements.Craft> getCrafts(Requirements plan) { return guidance.getCrafts(plan); }

		@Override
		public List<Point> getTaskPath() { return guidance.targeting.path; }

		@Override
		public NPC getTaskEnemy() { return guidance.targeting.enemyTarget; }

		@Override
		public WorldPoint getTaskGround() { return guidance.targeting.groundTarget; }

		@Override
		public String getNextPickupLabel() { return guidance.targeting.nextPickupLabel; }

		@Override
		public boolean isDemiFight() { return guidance.targeting.demiFight; }

		@Override
		public Point getAfterKillRoom() { return guidance.targeting.afterKillRoom; }

		@Override
		public String getAfterKillLabel() { return guidance.targeting.afterKillLabel; }

		@Override
		public String getAfterKillObjective() { return guidance.targeting.afterKillObjective; }

		@Override
		public NPC findVisibleDemi(Point room, Requirements plan) { return guidance.demiCue.findVisibleDemi(room, plan); }

		@Override
		public TileObject findExitToward(Point room) { return guidance.routing.findExitToward(room); }

		@Override
		public boolean isOpenPassage(TileObject object) { return guidance.isOpenPassage(object); }

		@Override
		public TileObject findNodeOnWay(Point room) { return guidance.routing.findNodeOnWay(room); }

		@Override
		public TileObject findDoorOnWay(Point room) { return guidance.routing.findDoorOnWay(room); }

		@Override
		public TileObject findNodeIn(Point room, String label) { return guidance.routing.findNodeIn(room, label); }

		@Override
		public TileObject getTourFirst() { return guidance.tour.roomPlanFirst; }

		@Override
		public TileObject getTourNext() { return guidance.tour.roomPlanNext; }

		@Override
		public ObjectKind getTourNextKind() { return guidance.targeting.roomPlanNextKind; }

		@Override
		public LocalPathfinder.Goal getTourNextGoal() { return guidance.tour.roomPlanNextGoal; }

		@Override
		public Point getTourGoal() { return guidance.targeting.inspectionGoal; }

		@Override
		public String getAfterRoomLabel() { return guidance.targeting.afterRoomLabel; }

		@Override
		public boolean isAfterRoomExplore() { return guidance.targeting.afterRoomExplore; }

		@Override
		public ObjectKind getHomeArrivalStation(HomeCraftPlan home, Requirements plan) { return guidance.objectTask.getHomeArrivalStation(home, plan); }

		@Override
		public List<LocalPathfinder.Goal> findStationGoals(ObjectKind station, List<TileObject> candidates)
		{
			return guidance.objectTask.findStationGoals(station, candidates);
		}

		@Override
		public LocalPathfinder.Goal buildServiceGoal(TileObject object, ObjectKind kind, int index)
		{
			return guidance.routing.buildServiceGoal(object, kind, index);
		}

		@Override
		public boolean isTripTeleport() { return guidance.attempt.isTripTeleport(); }

		@Override
		public GauntletRoom getDestinationRoom() { return guidance.getDestinationMapRoom(); }

		@Override
		public int getOpeningStartRoom() { return guidance.orientation.openingStartRoom; }

		@Override
		public ObjectKind getNextKind() { return guidance.targeting.nextKind; }

		@Override
		public TileObject getToolStorage() { return guidance.toolStorage; }

		@Override
		public boolean hasHomeShortcut(Point room) { return guidance.routing.hasHomeShortcut(room); }

		@Override
		public Map<ObjectKind, Integer> countSurelyIn(Requirements plan, Point room) { return guidance.harvests.countSurelyIn(plan, room); }
	}
}
