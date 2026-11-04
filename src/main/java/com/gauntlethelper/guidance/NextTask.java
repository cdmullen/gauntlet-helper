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

import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/**
 * The Next preview for Guidance: `update` chooses Next with the ordered `NEXT_STEPS` list (the first NextDecision
 * wins) and `publish` writes it to GuidanceState, with Next's path from where the current path ends. The home tasks whose
 * Next is fixed call previewStation, previewNext and previewDeparture, which publish the same way.
 */
final class NextTask
{
	/** One way to choose Next: its NextDecision, so the steps after it don't run, else null. */
	private interface NextStep
	{
		NextDecision decide(TickContext context);
	}

	/** How Next is chosen, highest priority first. */
	private static final List<NextStep> NEXT_STEPS = Arrays.asList(
			NextTask::previewPickup,
			NextTask::previewAfterDemiKill,
			NextTask::previewAfterRoomTour,
			NextTask::previewNearestWork,
			NextTask::previewOpeningRouteDoor);

	private final Guidance guidance;
	private final GuidanceState targeting;

	NextTask(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	/** Chooses Next, then publishes it with its path from where the current path ends. */
	void update(Requirements plan)
	{
		targeting.clearNext();
		TickContext context = buildContext(plan);
		NextDecision decision = chooseDecision(context);
		if (decision != null)
			publish(decision, context);
	}

	private TickContext buildContext(Requirements plan)
	{
		return TickContext.forPreview(guidance, plan, plan.getInventory());
	}

	/** The first step's NextDecision that isn't null; null when none decides or Next isn't shown this tick. */
	static NextDecision chooseDecision(TickContext context)
	{
		if (context.isInventorySpaceReturn() || context.route == null || context.plan.isReady() || context.getPlayer() == null)
			return null;
		for (NextStep step : NEXT_STEPS)
		{
			NextDecision decision = step.decide(context);
			if (decision != null)
				return decision;
		}
		return null;
	}

	/** The one place a NextDecision reaches GuidanceState. */
	private void publish(NextDecision decision, TickContext context)
	{
		if (decision.target != null)
			targeting.setNext(decision.target, decision.kind);
		if (decision.label != null)
			targeting.nextLabel = decision.label;
		if (decision.enemy != null)
			targeting.nextEnemy = decision.enemy;
		if (decision.target != null)
			targeting.nextPath = findNextPath(context, decision.target, decision.kind);
	}

	/**
	 * Next's walking path, continuing from the current path's last tile (from the player when there is none). Empty when
	 * unreachable from that tile.
	 */
	static List<Point> findNextPath(TickContext context, TileObject next, ObjectKind kind)
	{
		if (context.route == null || next.getLocalLocation() == null)
			return Collections.emptyList();
		LocalPathfinder.Goal goal = findNextGoal(context, next, kind);
		LocalPathfinder from = getRouteFromTaskEnd(context);
		LocalPathfinder.Choice choice = from.chooseNearest(Collections.singletonList(goal));
		// The tour's planned side of a resource can be unreachable from where the current path ends: any side then.
		if (choice == null && goal.walkTo && goal == context.getTourNextGoal())
			choice = from.chooseNearest(Collections.singletonList(findObjectGoal(context, next, kind)));
		return choice == null ? Collections.emptyList() : choice.path;
	}

	private static LocalPathfinder getRouteFromTaskEnd(TickContext context)
	{
		List<Point> path = context.getTaskPath();
		return path.isEmpty() ? context.route : context.route.routeFrom(path.get(path.size() - 1));
	}

	/**
	 * Where Next's path ends: the room tour's own goal for its next stop (the side it planned for a resource, a door's
	 * Light-side goal), else the object's interaction goal.
	 */
	private static LocalPathfinder.Goal findNextGoal(TickContext context, TileObject next, ObjectKind kind)
	{
		if (next == context.getTourNext() && context.getTourNextGoal() != null)
			return context.getTourNextGoal();
		return findObjectGoal(context, next, kind);
	}

	/** The object's own interaction goal, any side of it. */
	private static LocalPathfinder.Goal findObjectGoal(TickContext context, TileObject next, ObjectKind kind)
	{
		if (kind == null)
			return Routing.buildObjectGoal(next, 0, false);
		return context.buildServiceGoal(next, kind, 0);
	}

	/**
	 * Another pile still to pick up comes first.
	 */
	static NextDecision previewPickup(TickContext context)
	{
		String label = context.getNextPickupLabel();
		return label == null ? null : NextDecision.text(label);
	}

	/**
	 * Demi fight: Next is the move after the kill and its exit door, never the nearest object in the loaded scene
	 * (such as the home bowl).
	 */
	static NextDecision previewAfterDemiKill(TickContext context)
	{
		if (!context.isDemiFight())
			return null;
		String label = context.getAfterKillLabel();
		Point room = context.getAfterKillRoom();
		if (label == null || room == null)
			return NextDecision.text(label);
		// The next demi already in the scene is Next itself, not its room's door.
		NPC demi = context.findVisibleDemi(room);
		if (demi != null)
		{
			String objective = context.getAfterKillObjective();
			if (objective != null)
				return NextDecision.fight(demi, objective);
			return NextDecision.fight(demi, new DemiBossPlan(context.plan, context.demiHistory).getObjective(context.map.getRoom(room)));
		}
		TileObject exit = context.findExitToward(room);
		if (exit == null)
			return NextDecision.text(label);
		if (!context.isOpenPassage(exit))
			return NextDecision.object(exit, context.objects.get(exit)).withLabel(label);
		// Never the open doorway itself: the task steps tour a lit room on the way first, so its node is Next, else the door
		// to light past that doorway, else the move's own node in its room, else the text alone.
		TileObject onWay = findWorkPastDoorway(context, room);
		if (onWay != null)
		{
			ObjectKind kind = context.objects.get(onWay);
			return NextDecision.object(onWay, kind).withLabel(kind.label);
		}
		TileObject node = context.findNodeIn(room, label);
		return node == null ? NextDecision.text(label) : NextDecision.object(node, context.objects.get(node)).withLabel(label);
	}

	/** Past an open doorway toward room: a needed node in a lit room on the way, else the door to light; null if neither. */
	private static TileObject findWorkPastDoorway(TickContext context, Point room)
	{
		TileObject node = context.findNodeOnWay(room);
		if (node != null)
			return node;
		TileObject door = context.findDoorOnWay(room);
		return door != null && !context.isOpenPassage(door) ? door : null;
	}

	/** The current task is the room tour's first stop: Next is where the tour goes once this room's work is done. */
	static NextDecision previewAfterRoomTour(TickContext context)
	{
		if (context.getTourFirst() == null || context.getTourFirst() != context.getTaskTarget())
			return null;
		NextDecision move = findTourMove(context);
		// A tour toward the rooms around Hunllef's that leaves through an open doorway: Next is the door to light
		// past it.
		if (move.target == null && context.isAfterRoomExplore() && context.getTourGoal() != null)
		{
			TileObject door = context.findDoorOnWay(context.getTourGoal());
			if (door != null && !context.isOpenPassage(door))
				return NextDecision.object(door, context.objects.get(door)).withLabel(move.label);
		}
		return move;
	}

	/** The move once this room's work is done: the tour's next stop, the first home station, else the trip's text. */
	private static NextDecision findTourMove(TickContext context)
	{
		TileObject next = context.getTourNext();
		if (next != null)
		{
			// The tour's exit door is Next too, so the last resource already shows where to go, not only once the node
			// depletes.
			ObjectKind kind = context.getTourNextKind();
			boolean shown = !isOpeningHomeStation(context, kind) && context.isActive(next, context.objects.get(next));
			return shown ? NextDecision.object(next, kind) : NextDecision.NOTHING;
		}
		if (GauntletMap.HOME.equals(context.opening.getNextRoom()))
			return previewHomeStation(context);
		return NextDecision.text(context.getAfterRoomLabel());
	}

	/** The nearest other useful work object in the loaded scene (in the opening, a resource first). */
	static NextDecision previewNearestWork(TickContext context)
	{
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		List<LocalPathfinder.Goal> resources = new ArrayList<>();
		Set<Point> huntRooms = findHuntNextRooms(context);
		boolean ringSearch = OptimizedTrip.shouldSearchRing(context);
		Point here = context.opening.isInitialized() ? context.opening.getRoomAt(context.getHere()) : null;
		for (Map.Entry<TileObject, ObjectKind> entry : context.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			if (!isNextWork(context, object, kind))
				continue;
			WorldPoint point = object.getWorldLocation();
			if (huntRooms != null && !huntRooms.contains(context.opening.getRoomAt(point)))
				continue;
			// While resources are looked for in the ring or middle layer, another edge room's node is not Next.
			if (ringSearch && kind.resource != null && !context.opening.getRoomAt(point).equals(here)
					&& context.map.getRoom(context.opening.getRoomAt(point)).isPerimeter())
				continue;
			// Doors and passages are never Next work, so every goal is work rather than exploration.
			LocalPathfinder.Goal goal = context.buildServiceGoal(object, kind, Routing.addCandidate(candidates, object));
			goals.add(goal);
			if (kind.resource != null)
				resources.add(goal);
		}
		LocalPathfinder.Choice choice = context.opening.isActive() ? context.route.chooseNearest(resources) : null;
		if (choice == null)
			choice = context.route.chooseNearest(goals);
		if (choice == null)
			return null;
		TileObject chosen = candidates.get(choice.index);
		return NextDecision.object(chosen, context.objects.get(chosen));
	}

	/**
	 * Another active, useful object that isn't the current task, a doorway or a home station out of turn; in the
	 * opening, a resource only in the loop's work room.
	 */
	private static boolean isNextWork(TickContext context, TileObject object, ObjectKind kind)
	{
		if (object == context.getTaskTarget() || kind == context.getTaskKind() || !context.isActive(object, kind) || kind.isDoorway())
			return false;
		if (!context.isUseful(kind) || isOpeningHomeStation(context, kind) || isAwayHomeStation(context, kind))
			return false;
		if (!context.opening.isActive() || kind.resource == null)
			return true;
		return context.opening.getRoomAt(object.getWorldLocation()).equals(context.getOpeningWorkRoom());
	}

	/**
	 * An opening kill or pickup after the room's resources: Next is the loop's next door.
	 */
	static NextDecision previewOpeningRouteDoor(TickContext context)
	{
		Point loopRoom = context.opening.isActive() ? context.opening.getNextRoom() : null;
		if (loopRoom == null || context.getTaskEnemy() == null && context.getTaskGround() == null)
			return null;
		TileObject exit = context.findExitToward(loopRoom);
		// From the spawn room, a rat held in the loop room has the door past that room's open doorway.
		if (exit != null && context.isOpenPassage(exit) && context.getTaskEnemy() != null)
			exit = context.findDoorOnWay(loopRoom);
		if (exit == null || context.isOpenPassage(exit))
			return null;
		return NextDecision.object(exit, context.objects.get(exit));
	}

	/**
	 * The fifth loop room's last resource, its exit an open doorway home: Next is the first home station, with its path
	 * through the doorway.
	 */
	private static NextDecision previewHomeStation(TickContext context)
	{
		HomeCraftPlan home = context.getHomeVisit().previewCrafts();
		ObjectKind station = context.getHomeArrivalStation(home);
		NextDecision nearest = station == null ? null : findNearestStation(context, station);
		if (nearest == null)
			return NextDecision.NOTHING;
		return station == ObjectKind.BOWL ? nearest.withLabel(home.getInstruction()) : nearest;
	}

	/** The nearest active station of this kind from the current path's end; null when none is reachable. */
	static NextDecision findNearestStation(TickContext context, ObjectKind station)
	{
		List<TileObject> candidates = new ArrayList<>();
		TileObject nearest = Routing.findNearest(getRouteFromTaskEnd(context), context.findStationGoals(station, candidates), candidates);
		return nearest == null ? null : NextDecision.object(nearest, station);
	}

	/**
	 * A home task whose following station is fixed (the range after the pump) previews that station as Next, with its
	 * path from the current one. prepareHomeSpace calls it in place of updateNextTask.
	 */
	void previewStation(Requirements plan, ObjectKind station)
	{
		targeting.clearNext();
		if (guidance.config == null || targeting.lastRoute == null)
			return;
		TickContext context = buildContext(plan);
		NextDecision decision = findNearestStation(context, station);
		if (decision != null)
			publish(decision, context);
	}

	/** Shows object as Next, with its path from where the current path ends. */
	void previewNext(Requirements plan, TileObject object, ObjectKind kind)
	{
		publish(NextDecision.object(object, kind), buildContext(plan));
	}

	/**
	 * Cooking and storing at the range is the last home task before the hunt leaves: Next is the first door to light
	 * toward the outward room, with its path from the range.
	 */
	void previewDeparture(Requirements plan)
	{
		targeting.clearNext();
		if (guidance.config == null)
			return;
		TickContext context = buildContext(plan);
		NextDecision decision = findDepartureDoor(context);
		if (decision != null)
			publish(decision, context);
	}

	/** The first door to light toward the outward room. Only before that room is checked; later departures keep no preview. */
	static NextDecision findDepartureDoor(TickContext context)
	{
		Requirements plan = context.plan;
		if (context.route == null || plan.isReady() || plan.getRow(Requirement.UPGRADES).isComplete() || context.isOuterRoomChecked()
				|| !context.opening.hasCompletedSweep() || context.getOpeningStartRoom() < 0)
			return null;
		Point outer = context.opening.getOuterRoom(context.getOpeningStartRoom());
		TileObject door = context.findDoorOnWay(outer);
		if (door == null || context.isOpenPassage(door))
			return null;
		return NextDecision.object(door, context.objects.get(door));
	}

	/**
	 * While the demi hunt still lacks an upgrade, Next stays on the hunt's way: the player's room and the room the
	 * current task leads into, not a fish spot rooms away. Null (no limit) outside the hunt or
	 * on a home trip, and before the map places the spawn room (no rooms to limit to). At home the limit is the
	 * departure door, so a fish spot through the other home door isn't Next.
	 */
	private static Set<Point> findHuntNextRooms(TickContext context)
	{
		if (!context.opening.isInitialized() || context.opening.isActive() || context.plan.getRow(Requirement.UPGRADES).isComplete()
				|| context.isHomeReturn()
				|| context.isStaffReturn() || context.isTripTeleport()
				|| context.isAtHome() && context.getTaskKind() != ObjectKind.DOOR)
			return null;
		Set<Point> rooms = new HashSet<>();
		rooms.add(context.opening.getRoomAt(context.getHere()));
		GauntletRoom destination = context.getDestinationRoom();
		if (destination != null)
			rooms.add(context.map.getRelative(destination));
		return rooms;
	}

	/**
	 * Bowl, range and water wait for the home visit after the fifth room, so they never preview as Next while the
	 * opening loop runs.
	 */
	private static boolean isOpeningHomeStation(TickContext context, ObjectKind kind)
	{
		return context.opening.isActive() && kind != null && kind.isHomeStation();
	}

	/**
	 * Away from home, bowl, range and water are Next only on the way home: a hunt or trip task otherwise previewed the
	 * home bowl in view across the rooms.
	 */
	private static boolean isAwayHomeStation(TickContext context, ObjectKind kind)
	{
		return kind != null && kind.isHomeStation() && !context.isAtHome()
				&& !context.isHomeReturn() && !context.isStaffReturn() && !context.isTripTeleport()
				&& !isTaskAtHome(context);
	}

	/**
	 * The current task is in the spawn room, so the player is walking home for it.
	 */
	private static boolean isTaskAtHome(TickContext context)
	{
		if (!context.opening.isInitialized())
			return false;
		if (context.getTaskGround() != null)
			return isHomeTile(context, context.getTaskGround());
		return context.getTaskTarget() != null && isHomeTile(context, context.getTaskTarget().getWorldLocation());
	}

	private static boolean isHomeTile(TickContext context, WorldPoint point)
	{
		return context.opening.getRoomAt(point).equals(GauntletMap.HOME);
	}
}
