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
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.Simulation;
import com.gauntlethelper.plan.TripPlanner;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;

/**
 * The optimized trip for TaskPriorities: the bounded trip forecast's first
 * move and this room's tour end, the fallback to known needed resources, and the search of the ring or middle layer for
 * gathered resources still short after the demis. Each step decides from the TickContext. Where this room's tour ends and
 * the forecast's summary go up at decision time through the context, because the room tour reads them and they stay when
 * the step doesn't claim.
 */
final class OptimizedTrip
{
	/** Travel-time estimate that limits an optimized tour against the remaining prep time. */
	static final int ROUTE_SECONDS_PER_ROOM = 12;
	/** The tour budget while the prep timer hasn't been read. */
	static final int NO_TOUR_LIMIT = 100000;
	/**
	 * A known needed node in a lit edge room beats that search only when it is at least this many rooms closer than the
	 * nearest unopened room it looks for.
	 */
	static final int RING_EDGE_MARGIN_ROOMS = 3;

	private final TripPlanner tripPlanner = new TripPlanner();
	private final KillObjective kills;

	OptimizedTrip(KillObjective kills)
	{
		this.kills = kills;
	}

	void reset()
	{
		tripPlanner.reset();
	}

	/**
	 * After the outer room is checked: plan the trip, decide where this room's tour ends, do this room's work (its
	 * resources, an eligible kill, a useful demi), then take the trip's first move.
	 */
	Decision followOptimizedTrip(TickContext context)
	{
		if (!context.isOuterRoomChecked() || context.opening.isActive() || context.isOutwardTrip() || context.isAtHome() && context.canCraft())
			return null;
		Requirements plan = context.plan;
		Point current = context.opening.getRoomAt(context.getHere());
		GauntletRoom currentRoom = context.map.getRoom(current);
		DemiBossPlan demi = new DemiBossPlan(plan, context.demiHistory);
		Set<Point> reachable = context.findReachableRoomSteps(current);
		TripPlanner.Result forecast = tripPlanner.plan(
			context.map, current, plan, context.inv, context.loot, context.demiHistory, reachable, context.getTourBudget(), true);
		boolean edgeTrip = planRoomTourEnd(context, forecast, current, demi.isUseful(currentRoom));
		Decision work = workThisRoom(context, demi, currentRoom);
		return work != null ? work : takeFirstMove(context, forecast, current, edgeTrip);
	}

	/**
	 * Where this room's tour ends: at a door to light for a resource no known spot covers (afterRoomExplore), toward
	 * the forecast's next room, or at the home station it uses next. True when the forecast's next gather is in an edge
	 * room while resources are looked for in the ring or middle layer.
	 */
	static boolean planRoomTourEnd(TickContext context, TripPlanner.Result forecast, Point current, boolean usefulDemiHere)
	{
		Requirements plan = context.plan;
		// A resource short after the demis with a ring (or, materials only, middle-layer) room unopened: the tour ends at a
		// door toward it, not at a known node in another edge room.
		Map<Integer, Integer> gathers = findRoomGathers(forecast, current);
		Point ring = findRingFrontier(context, current, gathers);
		boolean edgeTrip = ring != null && isEdgeGather(context, forecast, current);
		boolean explore = !usefulDemiHere && plan.needsExploration()
				&& (edgeTrip || TripPlanner.isGatherShortAfterRoom(forecast, plan, context.inv, current));
		context.setAfterRoomExplore(explore);
		if (ring != null && explore)
		{
			context.setTourGoal(ring);
			Set<Point> tied = findTiedFirstSteps(context, current, gathers);
			if (tied.size() > 1)
				context.setTourExits(current, tied);
		}
		if (!usefulDemiHere && !explore)
			planTourTerminal(context, forecast, current);
		return edgeTrip;
	}

	/**
	 * The forecast's first step past this room's work: a teleport home (its Next text, once this room surely covers
	 * the rest), another room (the tour heads there), or the bowl, pump or range here (the tour ends at it).
	 */
	private static void planTourTerminal(TickContext context, TripPlanner.Result forecast, Point current)
	{
		for (TripPlanner.Step step : forecast.steps)
		{
			if (step.kind == Simulation.Kind.TELEPORT)
			{
				// Only previewed once this room's nodes surely cover the rest, so Next doesn't flip to a door.
				if (context.isRoomSurelyCovering(current))
					context.setAfterRoomLabel(context.isTeleportWorthwhile() ? step.label : "Return home to finish preparation");
				return;
			}
			if (!step.room.equals(current))
			{
				context.setTourGoal(step.room);
				return;
			}
			if (step.kind == Simulation.Kind.CRAFT)
			{
				context.setRoomTerminalService(ObjectKind.BOWL);
				return;
			}
			if (step.kind == Simulation.Kind.FILL)
			{
				context.setRoomTerminalService(ObjectKind.WATER);
				return;
			}
			if (step.kind == Simulation.Kind.COOK)
			{
				if (!context.canCraft())
					context.setRoomTerminalService(ObjectKind.RANGE);
				return;
			}
		}
	}

	/** A demi here whose drop is needed, else this room's needed resources, else an eligible kill. */
	private Decision workThisRoom(TickContext context, DemiBossPlan demi, GauntletRoom currentRoom)
	{
		// The room's nodes wait until its targeted demi is dead.
		if (demi.isUseful(currentRoom))
			return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction(demi.getObjective(currentRoom));
		ObjectKind kind = context.gatherCurrentRoom(context.getHere());
		if (kind != null)
			return Decision.CLAIM_ONLY.withInstruction(kind.label);
		return kills.fightOrdinaryEnemy(context);
	}

	/**
	 * The forecast's first move: a teleport or walk home, the walk to another room, or the step here (search, craft,
	 * fill, cook, gather). Null when there is none, or when it is a home trip that would leave a gathered resource
	 * short (lighting a room comes first; time-low guidance still sends the player home first).
	 */
	static Decision takeFirstMove(TickContext context, TripPlanner.Result forecast, Point current, boolean edgeTrip)
	{
		TripPlanner.Step first = forecast.getNextTask();
		if (first == null)
			return null;
		// No known spot in reach for a missing fish, grym leaf or other gathered resource: fall through to lighting a
		// room (steered home by inspectionGoal) instead of a home trip that leaves it short.
		if (!first.room.equals(current) && (edgeTrip || TripPlanner.isGatherShortHomeTrip(forecast, context.plan, context.inv)))
			return null;
		context.setTourSummary(forecast.buildSummary());
		if (first.kind == Simulation.Kind.TELEPORT)
			return teleportOrWalkHome(context, first);
		if (!first.room.equals(current))
			return walkOrNull(context, first.room, first.label);
		return takeStepHere(context, first, current);
	}

	private static Decision teleportOrWalkHome(TickContext context, TripPlanner.Step first)
	{
		if (context.isTeleportWorthwhile())
			return Decision.CLAIM_ONLY.withGuidancePaused().withTripTeleport().withInstruction(first.label);
		return context.canWalkTo(GauntletMap.HOME) ? Decision.walkTo(GauntletMap.HOME, "Return home to finish preparation") : null;
	}

	/** The walk to room under instruction, touring lit rooms on the way; null with no room route. */
	private static Decision walkOrNull(TickContext context, Point room, String instruction)
	{
		return context.canWalkTo(room) ? Decision.walkTo(room, instruction).withGatherPrefix("") : null;
	}

	/** A first move in this room: its station or node is the task, else its text alone. */
	private static Decision takeStepHere(TickContext context, TripPlanner.Step first, Point current)
	{
		if (first.kind == Simulation.Kind.CRAFT)
			return craftHere(context, first, current);
		if (first.kind == Simulation.Kind.FILL)
			return useServiceOrNull(context.findServiceTask(ObjectKind.WATER, true), first.label);
		if (first.kind == Simulation.Kind.COOK && !context.canCraft())
			return useServiceOrNull(context.findServiceTask(ObjectKind.RANGE, false), first.label);
		if (first.kind == Simulation.Kind.GATHER)
			return context.gatherCurrentRoom(context.getHere()) != null ? Decision.CLAIM_ONLY : null;
		return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction(first.label);
	}

	/** The bowl on this plane is the task; with none in reach the step still claims and its text says it waits. */
	private static Decision craftHere(TickContext context, TripPlanner.Step first, Point current)
	{
		context.setTourGoal(current);
		Decision bowl = context.findStationTask(ObjectKind.BOWL);
		return bowl != null ? bowl.withInstruction(first.label)
			: Decision.CLAIM_ONLY.withInstruction(first.label + Routing.describeWaiting(ObjectKind.BOWL));
	}

	private static Decision useServiceOrNull(Decision service, String instruction)
	{
		return service == null ? null : service.withInstruction(instruction);
	}

	/** Rooms an optimized tour may cross before the return-home reserve of prep time runs out. */
	static int computeTourBudget(int planningSeconds, int returnWarningSeconds)
	{
		return Math.max(0, (planningSeconds - Math.max(0, returnWarningSeconds)) / ROUTE_SECONDS_PER_ROOM);
	}

	/**
	 * A known missing resource is preferable to opening unrelated rooms: this room's, else the nearest room with a
	 * useful node (outside the edge while the ring or middle layer is searched), else the next room toward it.
	 */
	Decision gatherKnownResources(TickContext context)
	{
		ObjectKind published = context.getTaskKind();
		if (context.opening.isActive() || context.isOutwardTrip() || published != ObjectKind.DOOR && published != null)
			return null;
		WorldPoint here = context.getHere();
		ObjectKind kind = context.gatherCurrentRoom(here);
		if (kind != null)
			return Decision.CLAIM_ONLY.withInstruction(kind.label);
		Point current = context.opening.getRoomAt(here);
		Point ring = findRingFrontier(context, current, Collections.emptyMap());
		Point chosen = findNearestNodeRoom(context, current, ring != null);
		if (chosen != null && context.canWalkTo(chosen))
			return Decision.walkTo(chosen, "Return to known needed resources").withGatherPrefix("");
		return ring == null ? null : walkOrNull(context, ring, "Light room");
	}

	/** The nearest other room, by the room path, with a known useful node; edge rooms are left out while skipEdge. Null when none. */
	private static Point findNearestNodeRoom(TickContext context, Point current, boolean skipEdge)
	{
		Point chosen = null;
		int distance = Integer.MAX_VALUE;
		for (Map.Entry<Point, ObjectKind> e : context.map.findActiveNodes().entrySet())
		{
			if (!context.isUseful(e.getValue()))
				continue;
			Point room = context.opening.getRoomAt(e.getKey().getX(), e.getKey().getY());
			if (room.equals(current) || skipEdge && context.map.getRoom(room).isPerimeter())
				continue;
			List<Point> route = context.map.findPath(current, room);
			if (!route.isEmpty() && route.size() < distance)
			{
				distance = route.size();
				chosen = room;
			}
		}
		return chosen;
	}

	/**
	 * The first room to light (or walk into) toward the nearest unopened room where short resources are searched: the ring
	 * around Hunllef's room (GauntletMap.findRingPath) or the ring and middle layer (GauntletMap.findInteriorPath), as
	 * findSearchPath chooses. gathered counts the items this room's planned gathers will add. Null
	 * in any other state, once no such room can be reached without lighting an edge room, or when a known needed node
	 * in another lit edge room is at least RING_EDGE_MARGIN_ROOMS rooms closer than that room.
	 */
	private static Point findRingFrontier(TickContext context, Point current, Map<Integer, Integer> gathered)
	{
		if (!isGatherShortAfterDemis(context.plan, gathered))
			return null;
		Predicate<Point> firstStep = context.findReachableRoomSteps(current)::contains;
		ToIntFunction<Point> firstTiles = neighbor -> context.countDoorSteps(context.route, current, neighbor);
		List<Point> path = findSearchPath(context, gathered, current, firstStep, firstTiles);
		if (path.size() < 2 || findNearestEdgeNodeRooms(context, current) + RING_EDGE_MARGIN_ROOMS <= path.size() - 1)
			return null;
		return context.map.findFrontier(path);
	}

	/**
	 * The search path for gathered resources still short. The Corrupted Gauntlet: the ring with fish short (it holds
	 * the guaranteed fish room), else the ring or middle layer alike. The regular Gauntlet, whose ring has no fish
	 * room: the ring while a short material's guaranteed ring room hasn't been seen (it is surely in an undiscovered
	 * ring room), else, fish included, the ring or middle layer alike.
	 */
	private static List<Point> findSearchPath(TickContext context, Map<Integer, Integer> gathered, Point current, Predicate<Point> firstStep,
			ToIntFunction<Point> firstTiles)
	{
		return chooseSearch(context, gathered, current, firstStep).find(current, firstStep, firstTiles);
	}

	private static RoomSearch chooseSearch(TickContext context, Map<Integer, Integer> gathered, Point current, Predicate<Point> firstStep)
	{
		if (context.mode != GauntletMode.REGULAR)
			return isFishShort(context.plan, gathered) ? context.map::findRingPath : context.map::findInteriorPath;
		boolean ring = isGuaranteedMaterialShort(context.plan, gathered, context.map) && context.map.findRingPath(current, firstStep, room -> 0).size() >= 2;
		return ring ? context.map::findRingPath : context.map::findInteriorPath;
	}

	/** One of GauntletMap's searches for an unopened room. */
	private interface RoomSearch
	{
		List<Point> find(Point from, Predicate<Point> firstStep, ToIntFunction<Point> firstTiles);
	}

	/**
	 * The rooms next to current that start a search path as cheap as any, leaving the walk to their doors out: which of
	 * them is nearest changes as the player crosses the room, so the walk can't settle where this room's tour ends.
	 */
	private static Set<Point> findTiedFirstSteps(TickContext context, Point current, Map<Integer, Integer> gathered)
	{
		Set<Point> reachable = context.findReachableRoomSteps(current);
		RoomSearch search = chooseSearch(context, gathered, current, reachable::contains);
		Set<Point> tied = new HashSet<>();
		int best = Integer.MAX_VALUE;
		for (Point step : reachable)
		{
			List<Point> path = search.find(current, step::equals, room -> 0);
			if (path.size() < 2)
				continue;
			int cost = context.map.countInnerPathCost(path);
			if (cost < best)
			{
				best = cost;
				tied.clear();
			}
			if (cost == best)
				tied.add(step);
		}
		return tied;
	}

	/**
	 * Regular Gauntlet: ore, bark or linum tirinum is still short once gathered is added, and no ring room has been seen
	 * with enough of it to be its guaranteed room (GauntletMap.hasRingGuaranteeRoom).
	 */
	static boolean isGuaranteedMaterialShort(Requirements plan, Map<Integer, Integer> gathered, GauntletMap map)
	{
		for (Requirement resource : Requirement.MATERIALS)
			if (plan.getRow(resource).getMissing() > gathered.getOrDefault(resource.getItemId(), 0) && !map.hasRingGuaranteeRoom(resource))
				return true;
		return false;
	}

	/** Rooms to the nearest known useful node in another edge room, through open rooms; MAX_VALUE / 2 when none. */
	private static int findNearestEdgeNodeRooms(TickContext context, Point current)
	{
		int nearest = Integer.MAX_VALUE / 2;
		for (Map.Entry<Point, ObjectKind> e : context.map.findActiveNodes().entrySet())
		{
			if (!context.isUseful(e.getValue()))
				continue;
			Point room = context.opening.getRoomAt(e.getKey().getX(), e.getKey().getY());
			if (room.equals(current) || !context.map.getRoom(room).isPerimeter())
				continue;
			List<Point> route = context.map.findPath(current, room);
			if (!route.isEmpty())
				nearest = Math.min(nearest, route.size() - 1);
		}
		return nearest;
	}

	/** Resources are being looked for in the ring or middle layer (findRingFrontier): other edge rooms' nodes are not Next. */
	static boolean shouldSearchRing(TickContext context)
	{
		return context.opening.isInitialized() && !context.opening.isActive() && context.isOuterRoomChecked()
				&& findRingFrontier(context, context.opening.getRoomAt(context.getHere()), Collections.emptyMap()) != null;
	}

	/**
	 * The upgrades are held and a gathered resource (ore, bark, linum tirinum, Grym leaves, fish) is still short once
	 * gathered (item id to quantity) is added.
	 */
	static boolean isGatherShortAfterDemis(Requirements plan, Map<Integer, Integer> gathered)
	{
		if (!plan.getRow(Requirement.UPGRADES).isComplete())
			return false;
		for (Requirement resource : Requirement.GATHERED)
			if (plan.getRow(resource).getMissing() > gathered.getOrDefault(resource.getItemId(), 0))
				return true;
		return false;
	}

	/** Fish is still short once gathered (item id to quantity) is added. */
	static boolean isFishShort(Requirements plan, Map<Integer, Integer> gathered)
	{
		return plan.getRow(Requirement.FISH).getMissing() > gathered.getOrDefault(Requirement.FISH.getItemId(), 0);
	}

	/** What the forecast's leading steps in room gather, by item id. */
	private static Map<Integer, Integer> findRoomGathers(TripPlanner.Result forecast, Point room)
	{
		Map<Integer, Integer> gathered = new HashMap<>();
		for (TripPlanner.Step step : forecast.steps)
		{
			if (!step.room.equals(room))
				break;
			if (step.kind == Simulation.Kind.GATHER)
				gathered.merge(step.itemId, step.quantity, Integer::sum);
		}
		return gathered;
	}

	/** The forecast's first step outside current gathers in an edge or corner room. */
	private static boolean isEdgeGather(TickContext context, TripPlanner.Result forecast, Point current)
	{
		for (TripPlanner.Step step : forecast.steps)
			if (!step.room.equals(current))
				return step.kind == Simulation.Kind.GATHER && context.map.getRoom(step.room).isPerimeter();
		return false;
	}
}
