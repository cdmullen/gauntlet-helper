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
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Room navigation for Guidance: routes to rooms, services and doorways across the known map, and the route
 * goal helpers for doors and objects. Opening and task guidance both use it.
 */
public final class Routing
{
	private final Guidance guidance;
	private final GuidanceState targeting;

	Routing(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	/** Adds object to candidates and returns its index there, the index its route goal carries. */
	static int addCandidate(List<TileObject> candidates, TileObject object)
	{
		candidates.add(object);
		return candidates.size() - 1;
	}

	/** The index of object in candidates, adding it first when it is not there yet. */
	static int findCandidateIndex(List<TileObject> candidates, TileObject object)
	{
		int index = candidates.indexOf(object);
		return index >= 0 ? index : addCandidate(candidates, object);
	}

	/** The candidate the route reaches first among goals (each goal's index is its candidate's); null when none. */
	static TileObject findNearest(LocalPathfinder route, List<LocalPathfinder.Goal> goals, List<TileObject> candidates)
	{
		LocalPathfinder.Choice choice = route.chooseNearest(goals);
		return choice == null ? null : candidates.get(choice.index);
	}

	/**
	 * Goals for the useful loaded resource nodes in room on the view's plane, of a kind whose label starts label when
	 * one is given; candidates receives the nodes in goal order.
	 */
	List<LocalPathfinder.Goal> findResourceGoals(Requirements plan, WorldView view, Point room, String label, List<TileObject> candidates)
	{
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			if (kind.resource == null || label != null && !label.startsWith(kind.label)
					|| !guidance.isUseful(kind, plan) || object.getPlane() != view.getPlane()
					|| !guidance.isActive(object, kind)
					|| !guidance.opening.getRoomAt(object.getWorldLocation()).equals(room))
				continue;
			goals.add(buildObjectGoal(object, addCandidate(candidates, object), false));
		}
		return goals;
	}

	/** Terminal interaction for the loop exit or the already chosen room-level continuation. */
	List<LocalPathfinder.Goal> findRoomExitGoals(Point room, List<TileObject> candidates)
	{
		List<LocalPathfinder.Goal> exits = new ArrayList<>();
		if (targeting.roomTerminalService != null)
		{
			for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
			{
				TileObject object = entry.getKey();
				ObjectKind kind = entry.getValue();
				WorldPoint at = object.getWorldLocation();
				if ((kind != targeting.roomTerminalService
						&& !(targeting.roomTerminalService == ObjectKind.WATER && kind == ObjectKind.FISH))
						|| !guidance.isActive(object, kind)
						|| !guidance.opening.getRoomAt(at).equals(room))
					continue;
				exits.add(buildServiceGoal(object, kind, findCandidateIndex(candidates, object)));
			}
			return exits;
		}
		// Doors the search ties on all end the tour: the planner picks by the whole trip, and the list doesn't change as the player walks.
		if (room.equals(targeting.tourExitRoom))
		{
			for (Point exit : targeting.tourExits)
				exits.addAll(findDoorwayGoals(room, exit, candidates));
			return exits;
		}
		Point next = guidance.opening.isActive() ? guidance.opening.getRoomAfter(room) : null;
		if (next == null && targeting.inspectionGoal != null && !room.equals(targeting.inspectionGoal))
		{
			List<Point> rooms = guidance.gauntletMap.findPath(room, targeting.inspectionGoal, findReachableRoomSteps(room)::contains);
			next = rooms.size() > 1 ? rooms.get(1) : SceneGeometry.getAxisStep(room, targeting.inspectionGoal);
		}
		if (next == null && targeting.afterRoomExplore)
		{
			// Every door here that lights a new room, costed as the nearest-door fallback costs it once the room's
			// work is done, so the tour's exit is the door that fallback then lights.
			for (int dx = -1; dx <= 1; dx++)
				for (int dy = -1; dy <= 1; dy++)
					if (Math.abs(dx) + Math.abs(dy) == 1)
						for (LocalPathfinder.Goal goal : findDoorwayGoals(room, new Point(room.getX() + dx, room.getY() + dy), candidates))
						{
							TileObject door = candidates.get(goal.index);
							if (!guidance.isOpenPassage(door))
								exits.add(buildUnlockGoal(door, goal.index, room, null));
						}
			return exits;
		}
		if (next == null)
			return exits;
		return findDoorwayGoals(room, next, candidates);
	}

	/** Walking tiles from the player to the nearest loaded door into a neighboring room; MAX_VALUE when unknown. */
	int countDoorSteps(Point room, Point next)
	{
		return countDoorSteps(targeting.lastRoute, room, next);
	}

	/** countDoorSteps walked from route's start tile instead of the player. */
	int countDoorSteps(LocalPathfinder route, Point room, Point next)
	{
		if (route == null || guidance.client.getTopLevelWorldView() == null)
			return Integer.MAX_VALUE;
		LocalPathfinder.Choice choice = route.chooseNearest(findDoorwayGoals(room, next, new ArrayList<>()));
		return choice == null ? Integer.MAX_VALUE : choice.path.size();
	}

	/** Light or walk-through goals for the doorway between neighboring rooms, adding new objects to candidates. */
	List<LocalPathfinder.Goal> findDoorwayGoals(Point room, Point next, List<TileObject> candidates)
	{
		List<LocalPathfinder.Goal> exits = new ArrayList<>();
		Point boundary = guidance.opening.getBoundary(room, next);
		int dx = next.getX() - room.getX(), dy = next.getY() - room.getY();
		WorldView view = guidance.client.getTopLevelWorldView();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			if (!kind.isDoorway() || object.getPlane() != view.getPlane() || !guidance.isActive(object, kind))
				continue;
			if (!SceneGeometry.isNearBoundary(object.getWorldLocation(), boundary))
				continue;
			boolean passage = guidance.isOpenPassage(object, kind);
			if (!passage && !guidance.canUnlockRoom(object))
				continue;
			int index = findCandidateIndex(candidates, object);
			exits.add(passage ? SceneGeometry.buildCrossingGoal(index, object, dx, dy) : buildUnlockGoal(object, index, room, next));
		}
		return exits;
	}

	/**
	 * Route toward destination's room, ending at service there when one is given. Without a route across known rooms,
	 * the doorway toward step (navigateRoom's next room on the map route), or the axis step when step is null.
	 */
	boolean routeToRoom(LocalPathfinder route, WorldView view, Player player, Point destination, ObjectKind service, Point step)
	{
		targeting.inspectionGoal = destination;
		WorldPoint here = player.getWorldLocation();
		Point current = guidance.opening.getRoomAt(here);
		if (!current.equals(destination)
				&& (routeKnownDestination(route, view, destination, service)
				|| routeKnownFrontier(route, view, current, destination)))
			return true;
		Point next = step != null ? step : SceneGeometry.getAxisStep(current, destination);
		Point boundary = guidance.opening.getBoundary(current, next);
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		List<Boolean> passages = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			if (object.getPlane() != view.getPlane() || !guidance.isActive(object, kind))
				continue;
			boolean passage = false;
			if (current.equals(destination))
			{
				if (service == null || kind != service)
					continue;
			}
			else
			{
				if (!kind.isDoorway() || !SceneGeometry.isNearBoundary(object.getWorldLocation(), boundary))
					continue;
				passage = guidance.isOpenPassage(object, kind);
				if (!passage && !guidance.canUnlockRoom(object))
					continue;
			}
			int index = addCandidate(candidates, object);
			passages.add(passage);
			if (passage)
				goals.add(SceneGeometry.buildCrossingGoal(index, object, next.getX() - current.getX(), next.getY() - current.getY()));
			else
				goals.add(kind == ObjectKind.DOOR ? buildUnlockGoal(object, index, current, next) : buildServiceGoal(object, kind, index));
		}
		LocalPathfinder.Choice choice = route.chooseNearest(goals);
		if (choice == null)
		{
			if (!targetDoorBeside(route, view, here, current))
				guidance.attempt.appendInstruction(describeWaiting(service));
			return true;
		}
		TileObject chosen = candidates.get(choice.index);
		targeting.setTask(chosen, guidance.objects.get(chosen), choice.path);
		if (passages.get(choice.index))
		{
			targeting.setPassageWaypoint();
			continueThrough(route, view, next, destination);
		}
		return true;
	}

	/**
	 * A doorway's tiles count as the room beyond it, so a player standing in an unlit doorway is in a room not discovered
	 * yet, with no way out of it to route. The door beside them is still the way on: it stays the task. False in any other room.
	 */
	private boolean targetDoorBeside(LocalPathfinder route, WorldView view, WorldPoint here, Point current)
	{
		GauntletRoom room = guidance.gauntletMap.getRoom(current);
		if (room == null || room.isDiscovered())
			return false;
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			if (entry.getValue() != ObjectKind.DOOR || object.getPlane() != view.getPlane() || !guidance.isActive(object, ObjectKind.DOOR)
					|| !guidance.canUnlockRoom(object) || guidance.isOpenPassage(object, ObjectKind.DOOR)
					|| !SceneGeometry.isNearBoundary(object.getWorldLocation(), new Point(here.getX(), here.getY())))
				continue;
			goals.add(buildObjectGoal(object, addCandidate(candidates, object), true));
		}
		LocalPathfinder.Choice choice = route.chooseNearest(goals);
		if (choice == null)
			return false;
		targeting.setTask(candidates.get(choice.index), ObjectKind.DOOR, choice.path);
		return true;
	}

	/** What the instruction adds while routeToRoom has no doorway or service in reach. */
	static String describeWaiting(ObjectKind service)
	{
		if (service == ObjectKind.BOWL)
			return " (waiting for a reachable doorway or singing bowl)";
		return service == ObjectKind.RANGE ? " (waiting for a reachable doorway or range)" : " (waiting for a reachable doorway)";
	}

	/** Rooms a crossing chain may run through before the path stops (the 7x7 grid is at most 12 rooms across). */
	private static final int MAX_CROSSINGS = 12;

	/**
	 * A doorway crossing ends two tiles into `room`. Carry the path on to that room's next stop instead:
	 * its doorway onward toward the destination (lit doorways are crossed and the chain continues), or an enemy stop
	 * once it is the destination. The path stays as it was when nothing further is reachable.
	 */
	private void continueThrough(LocalPathfinder route, WorldView view, Point room, Point destination)
	{
		for (int i = 0; i < MAX_CROSSINGS; i++)
		{
			if (room.equals(destination))
			{
				LocalPathfinder.Choice stop = chooseRoomEnemyStop(route, view, room);
				if (stop != null)
					targeting.path = stop.path;
				return;
			}
			Point onward = findOnwardRoom(room, destination);
			List<TileObject> candidates = new ArrayList<>();
			LocalPathfinder.Choice choice = route.chooseNearest(findDoorwayGoals(room, onward, candidates));
			if (choice == null || choice.path.isEmpty())
				return;
			targeting.path = choice.path;
			TileObject exit = candidates.get(choice.index);
			if (!guidance.isOpenPassage(exit))
			{
				targeting.setTarget(exit, ObjectKind.DOOR);
				return;
			}
			room = onward;
		}
	}

	/** The next room toward destination: the known map's route when it has one, else along the spawn axis. */
	private Point findOnwardRoom(Point room, Point destination)
	{
		List<Point> rooms = guidance.gauntletMap.findPath(room, destination);
		return rooms.size() > 1 ? rooms.get(1) : SceneGeometry.getAxisStep(room, destination);
	}

	/**
	 * Where a path into a room with no needed node ends: the nearest loaded eligible kill there, or a living demi whose
	 * drop is still needed, rather than two tiles inside the doorway. Path only; the enemy cue
	 * takes over on entry. Null when neither is loaded and reachable.
	 */
	LocalPathfinder.Choice chooseRoomEnemyStop(LocalPathfinder route, WorldView view, Point room)
	{
		Requirements plan = guidance.attempt.getNavigationPlan();
		if (plan == null || view == null || view.npcs() == null)
			return null;
		List<NPC> demis = guidance.getNeededDemis();
		GauntletRoom known = guidance.gauntletMap.getRoom(room);
		boolean killsAllowed = (known == null || !known.hasLivingDemiBoss()) && guidance.config != null
				&& guidance.config.highlightTargetedEnemies() && !guidance.priorities.hunt.isOrdinarySuppressed()
				&& guidance.getEnemyReason(plan, plan.getInventory()) != null;
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (NPC npc : view.npcs())
		{
			if (npc == null || npc.isDead() || !demis.contains(npc) && !(killsAllowed && EnemyRules.isEligible(npc.getId(), plan)))
				continue;
			WorldPoint at = npc.getWorldLocation();
			if (at == null || at.getPlane() != view.getPlane() || !guidance.opening.getRoomAt(at).equals(room))
				continue;
			LocalPathfinder.Goal goal = buildNpcGoal(npc, view, goals.size());
			if (goal != null)
				goals.add(goal);
		}
		LocalPathfinder.Choice choice = route.chooseNearest(goals);
		return choice == null || choice.path.isEmpty() ? null : choice;
	}

	/** Interaction goal over an NPC's scene footprint; null when it is outside the scene. */
	static LocalPathfinder.Goal buildNpcGoal(NPC npc, WorldView view, int index)
	{
		WorldArea area = npc.getWorldArea();
		LocalPoint local = area == null ? npc.getLocalLocation() : LocalPoint.fromWorld(view, area.toWorldPoint());
		if (local == null)
			return null;
		int size = area == null ? Math.max(1, npc.getFootprintSize()) : Math.max(area.getWidth(), area.getHeight());
		return new LocalPathfinder.Goal(index, local.getSceneX(), local.getSceneY(), local.getSceneX() + size - 1, local.getSceneY() + size - 1, false);
	}

	/**
	 * Light the door into an unknown room, or a revealed one still behind an unlit door, from across known rooms. A
	 * just-lit empty room is crossed to its far door without first stepping inside.
	 */
	private boolean routeKnownFrontier(LocalPathfinder route, WorldView view, Point current, Point destination)
	{
		GauntletRoom end = guidance.gauntletMap.getRoom(destination);
		if (end == null)
			return false;
		List<Point> rooms = guidance.gauntletMap.findPath(current, destination, findReachableRoomSteps(current)::contains);
		if (rooms.size() < 2)
			return false;
		Point before = rooms.get(rooms.size() - 2);
		Point boundary = guidance.opening.getBoundary(before, destination);
		GauntletRoom.Direction toward = SceneGeometry.getDirection(before, destination);
		if (end.isDiscovered() && guidance.gauntletMap.getRoom(before).getConnection(toward) != GauntletRoom.Connection.UNLIT)
			return false;
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
		{
			TileObject object = e.getKey();
			if (e.getValue() != ObjectKind.DOOR || !guidance.canUnlockRoom(object)
					|| guidance.isOpenPassage(object, e.getValue())
					|| !SceneGeometry.isNearBoundary(object.getWorldLocation(), boundary))
				continue;
			goals.add(buildUnlockGoal(object, addCandidate(candidates, object), before, destination));
		}
		LocalPathfinder known = end.canHaveDemiSpawn() ? getKnownRoute(route, view) : buildDoorwayRoute(route, view, boundary);
		LocalPathfinder.Choice choice = known.chooseNearest(goals);
		if (choice == null)
			return false;
		targeting.setTask(candidates.get(choice.index), ObjectKind.DOOR, choice.path);
		return true;
	}

	/**
	 * The route over known rooms plus the doorway at boundary. Part of a doorway belongs to the room still dark, and
	 * without it a door with no demi possible behind it could only be lit from the tile outside.
	 */
	private LocalPathfinder buildDoorwayRoute(LocalPathfinder route, WorldView view, Point boundary)
	{
		return route.restrict((x, y) -> isKnownTile(view, x, y)
				|| SceneGeometry.isNearBoundary(new WorldPoint(view.getBaseX() + x, view.getBaseY() + y, view.getPlane()), boundary));
	}

	/** A single collision route across discovered rooms; no artificial center waypoints. */
	private boolean routeKnownDestination(LocalPathfinder route, WorldView view, Point destination, ObjectKind service)
	{
		GauntletRoom room = guidance.gauntletMap.getRoom(destination);
		if (room == null || !room.isDiscovered() || room.isHunllefRoom())
			return false;
		LocalPathfinder known = getKnownRoute(route, view);
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
		{
			TileObject object = e.getKey();
			ObjectKind kind = e.getValue();
			WorldPoint at = object.getWorldLocation();
			if (at.getPlane() != view.getPlane() || !guidance.opening.getRoomAt(at).equals(destination) || !guidance.isActive(object, kind))
				continue;
			if (service != null ? kind != service : kind.resource == null || guidance.attempt.getNavigationPlan() == null
					|| !guidance.isUseful(kind, guidance.attempt.getNavigationPlan()))
				continue;
			goals.add(buildServiceGoal(object, kind, addCandidate(candidates, object)));
		}
		LocalPathfinder.Choice choice = known.chooseNearest(goals);
		if (choice != null)
		{
			TileObject chosen = candidates.get(choice.index);
			targeting.setTask(chosen, guidance.objects.get(chosen), choice.path);
			return true;
		}
		if (service != null)
			return false;
		// No needed node there: end at the room's enemy objective when one is loaded.
		LocalPathfinder.Choice stop = chooseRoomEnemyStop(known, view, destination);
		if (stop != null)
		{
			targeting.setTask(null, ObjectKind.PASSAGE, stop.path);
			return true;
		}
		// Nothing loaded to work on there: step just inside the doorway, never walk to the room's center tile.
		// Entry still counts as arrival; the next task takes over from there.
		Point center = guidance.opening.getRoomCenter(destination);
		int inset = guidance.opening.getRoomPitch() / 2 - 2;
		int cx = center.getX() - view.getBaseX();
		int cy = center.getY() - view.getBaseY();
		List<Point> direct = known.findPathIntoArea(cx - inset, cy - inset, cx + inset, cy + inset);
		if (direct.isEmpty())
			return false;
		targeting.setTask(null, ObjectKind.PASSAGE, direct);
		return true;
	}

	/** The route over known rooms only, built once a tick. */
	LocalPathfinder getKnownRoute(LocalPathfinder route, WorldView view)
	{
		if (targeting.knownRouteBase != route)
		{
			targeting.knownRoute = buildKnownRoute(route, view);
			targeting.knownRouteBase = route;
		}
		return targeting.knownRoute;
	}

	private LocalPathfinder buildKnownRoute(LocalPathfinder route, WorldView view)
	{
		return route.restrict((x, y) -> isKnownTile(view, x, y));
	}

	/** A scene tile of a discovered room other than Hunllef's, leaving out its west and south edges until they open. */
	private boolean isKnownTile(WorldView view, int x, int y)
	{
		Point relative = guidance.opening.getRoomAt(view.getBaseX() + x, view.getBaseY() + y);
		GauntletRoom room = guidance.gauntletMap.getRoom(relative);
		if (room == null || !room.isDiscovered() || room.isHunllefRoom())
			return false;
		Point center = guidance.opening.getRoomCenter(relative);
		int half = guidance.opening.getRoomPitch() / 2;
		if (view.getBaseX() + x == center.getX() - half && room.getConnection(GauntletRoom.Direction.WEST) != GauntletRoom.Connection.OPEN)
			return false;
		return view.getBaseY() + y != center.getY() - half || room.getConnection(GauntletRoom.Direction.SOUTH) == GauntletRoom.Connection.OPEN;
	}

	boolean navigateRoom(Point destination, String instruction)
	{
		return navigateRoom(destination, instruction, null);
	}

	/**
	 * Route toward a room. With gatherPrefix set (the caller gathers a room's needed nodes on entry), a lit room on the
	 * way that has them is toured as soon as it is open, before entry, so its far door and its nodes don't swap
	 * places as the room is entered. The instruction is then gatherPrefix plus
	 * the resource label.
	 */
	boolean navigateRoom(Point destination, String instruction, String gatherPrefix)
	{
		if (targeting.lastRoute == null || !guidance.opening.isInitialized())
			return false;
		Player player = guidance.client.getLocalPlayer();
		WorldPoint at = player.getWorldLocation();
		Point current = guidance.opening.getRoomAt(at);
		Set<Point> reachable = findReachableRoomSteps(current);
		List<Point> rooms = guidance.gauntletMap.findPath(current, destination, reachable::contains);
		boolean home = destination.equals(GauntletMap.HOME);
		Point goal = destination;
		Point shortcut = home ? findHomeShortcut(current, rooms, reachable) : null;
		if (shortcut != null)
		{
			goal = shortcut;
			rooms = guidance.gauntletMap.findPath(current, shortcut, reachable::contains);
		}
		if (rooms.isEmpty())
			return false;
		guidance.pauseGuidance();
		guidance.attempt.setInstruction(instruction);
		if (home)
			guidance.attempt.startHomeReturn();
		if (gatherPrefix != null && gatherOnWay(rooms, destination, gatherPrefix))
			return true;
		routeToRoom(targeting.lastRoute, guidance.client.getTopLevelWorldView(), player, goal,
				home && shortcut == null ? getHomeFacility(guidance.attempt.getNavigationPlan()) : null,
				rooms.size() > 1 ? rooms.get(1) : null);
		return true;
	}

	/**
	 * The doorway out of the current room on navigateRoom's route to destination (a door to light or an open
	 * doorway), for a Next preview. Changes no routing state. Null when no route or loaded doorway is reachable.
	 */
	TileObject findExitToward(Point destination)
	{
		List<Point> rooms = findRoomsToward(destination);
		if (rooms.size() < 2)
			return null;
		List<TileObject> candidates = new ArrayList<>();
		return findNearest(targeting.lastRoute, findDoorwayGoals(rooms.get(0), rooms.get(1), candidates), candidates);
	}

	/** navigateRoom would walk toward destination: its room route exists. Changes no routing state. */
	boolean canNavigateTo(Point destination)
	{
		return !findRoomsToward(destination).isEmpty();
	}

	/** navigateRoom's room route from the current room to destination, home shortcut included; empty when none. */
	private List<Point> findRoomsToward(Point destination)
	{
		if (targeting.lastRoute == null || !guidance.opening.isInitialized())
			return Collections.emptyList();
		WorldPoint at = guidance.client.getLocalPlayer().getWorldLocation();
		Point current = guidance.opening.getRoomAt(at);
		Set<Point> reachable = findReachableRoomSteps(current);
		List<Point> rooms = guidance.gauntletMap.findPath(current, destination, reachable::contains);
		Point shortcut = destination.equals(GauntletMap.HOME) ? findHomeShortcut(current, rooms, reachable) : null;
		return shortcut == null ? rooms : guidance.gauntletMap.findPath(current, shortcut, reachable::contains);
	}

	/**
	 * The node gatherOnWay would tour first on the way to destination, for a Next preview: the nearest useful loaded
	 * node in the first lit room before destination, reached through open doorways only, that has one. Changes no
	 * routing state. Null when there is none.
	 */
	TileObject findNodeOnWay(Point destination)
	{
		Requirements plan = guidance.attempt.getNavigationPlan();
		WorldView view = guidance.client.getTopLevelWorldView();
		List<Point> rooms = findRoomsToward(destination);
		if (plan == null || view == null)
			return null;
		for (int i = 1; i < rooms.size() - 1; i++)
		{
			Point room = rooms.get(i);
			if (!isOpenInto(rooms.get(i - 1), room))
				return null;
			TileObject node = findNearestNode(plan, view, room, null);
			if (node != null)
				return node;
		}
		return null;
	}

	/**
	 * The move's own node in destination, for a Next preview when every doorway there is open: the nearest useful
	 * loaded node whose kind label starts the move's text ("Catch paddlefish ×1"). Changes no routing state. Null when
	 * the route crosses a closed doorway or no such node is loaded.
	 */
	TileObject findNodeIn(Point destination, String label)
	{
		Requirements plan = guidance.attempt.getNavigationPlan();
		WorldView view = guidance.client.getTopLevelWorldView();
		List<Point> rooms = findRoomsToward(destination);
		if (plan == null || view == null || label == null || rooms.size() < 2)
			return null;
		for (int i = 1; i < rooms.size(); i++)
		{
			if (!isOpenInto(rooms.get(i - 1), rooms.get(i)))
				return null;
		}
		return findNearestNode(plan, view, rooms.get(rooms.size() - 1), label);
	}

	/** The room is discovered and the doorway into it from the neighboring room `from` is open. */
	private boolean isOpenInto(Point from, Point room)
	{
		GauntletRoom entered = guidance.gauntletMap.getRoom(room);
		return entered != null && entered.isDiscovered()
				&& guidance.gauntletMap.getRoom(from).getConnection(SceneGeometry.getDirection(from, room))
				== GauntletRoom.Connection.OPEN;
	}

	/** The nearest useful loaded resource node in room, of a kind whose label starts label when one is given. */
	private TileObject findNearestNode(Requirements plan, WorldView view, Point room, String label)
	{
		List<TileObject> candidates = new ArrayList<>();
		return findNearest(targeting.lastRoute, findResourceGoals(plan, view, room, label, candidates), candidates);
	}

	/**
	 * The first door still to light on the way to destination, past open doorways, for a Next preview: what the task steps
	 * asks for once the player walks through them. Changes no routing state. Null when the route has no closed doorway
	 * or its door is not loaded and reachable.
	 */
	TileObject findDoorOnWay(Point destination)
	{
		List<Point> rooms = findRoomsToward(destination);
		if (rooms.size() < 2 && targeting.lastRoute != null && guidance.opening.isInitialized())
		{
			// No map route yet: routeToRoom then takes the axis step, so preview its doorway.
			WorldPoint at = guidance.client.getLocalPlayer().getWorldLocation();
			Point current = guidance.opening.getRoomAt(at);
			rooms = current.equals(destination) ? Collections.emptyList() : Arrays.asList(current, SceneGeometry.getAxisStep(current, destination));
		}
		for (int i = 1; i < rooms.size(); i++)
		{
			Point from = rooms.get(i - 1), room = rooms.get(i);
			GauntletRoom here = guidance.gauntletMap.getRoom(from);
			if (here != null && here.getConnection(SceneGeometry.getDirection(from, room)) == GauntletRoom.Connection.OPEN)
				continue;
			List<TileObject> candidates = new ArrayList<>();
			return findNearest(targeting.lastRoute, findDoorwayGoals(from, room, candidates), candidates);
		}
		return null;
	}

	/** A route home from current would light a door (findHomeShortcut). */
	boolean hasHomeShortcut(Point current)
	{
		Set<Point> reachable = findReachableRoomSteps(current);
		List<Point> known = guidance.gauntletMap.findPath(current, GauntletMap.HOME, reachable::contains);
		return findHomeShortcut(current, known, reachable) != null;
	}

	/**
	 * The first room to light on a route home that crosses fewer rooms than the known open route. Null when the
	 * known route is as short, no sceptre can light the door (canLightDoor), or the shortcut runs through an
	 * unseen room that may hold a demi-boss.
	 */
	private Point findHomeShortcut(Point current, List<Point> known, Set<Point> reachable)
	{
		Requirements plan = guidance.attempt.getNavigationPlan();
		if (plan == null || !canLightDoor(plan.getInventory()))
			return null;
		Point home = GauntletMap.HOME;
		List<Point> explored = guidance.gauntletMap.findExplorationPath(current, home, reachable::contains);
		if (explored.size() < 2 || !known.isEmpty() && explored.size() >= known.size())
			return null;
		for (Point room : explored)
		{
			GauntletRoom seen = guidance.gauntletMap.getRoom(room);
			if (!seen.isDiscovered() && seen.canHaveDemiSpawn())
				return null;
		}
		Point frontier = guidance.gauntletMap.findFrontier(explored);
		return frontier.equals(home) ? null : frontier;
	}

	/**
	 * A door on the way can be lit: a sceptre is held or worn, or one lies dropped within reach with a slot to pick it
	 * up (Sceptre.findNearbySceptre), which the sceptre recovery then does first. Otherwise a dropped sceptre would
	 * send the walk home the long way round while it lies a few tiles away.
	 */
	boolean canLightDoor(Inventory inv)
	{
		if (Guidance.hasSceptre(inv))
			return true;
		return Sceptre.findNearbySceptre(guidance.lastLoot, inv, guidance.client.getTopLevelWorldView(), targeting.lastRoute) != null;
	}

	/**
	 * Tour the first room passed through on the way, reached through open doorways only, that has useful loaded
	 * nodes. The tour's exit points on toward destination. The destination itself keeps routeKnownDestination's
	 * direct route and its Next preview.
	 */
	private boolean gatherOnWay(List<Point> rooms, Point destination, String gatherPrefix)
	{
		Requirements plan = guidance.attempt.getNavigationPlan();
		WorldView view = guidance.client.getTopLevelWorldView();
		if (plan == null || view == null)
			return false;
		for (int i = 1; i < rooms.size() - 1; i++)
		{
			Point room = rooms.get(i);
			if (!isOpenInto(rooms.get(i - 1), room))
				return false;
			targeting.inspectionGoal = destination;
			if (guidance.tour.gatherRoom(plan, targeting.lastRoute, view, room))
			{
				guidance.attempt.setInstruction(gatherPrefix + targeting.targetKind.label);
				return true;
			}
		}
		return false;
	}

	/**
	 * Where a trip home ends: the bowl while a recipe is available, else the range when fish are held to cook or store
	 * (never the bowl under a cooking instruction or with nothing to craft), and the range when storage goes before the
	 * bowl. With neither, the trip ends just inside home and the next task takes over.
	 */
	private ObjectKind getHomeFacility(Requirements plan)
	{
		if (plan == null)
			return ObjectKind.BOWL;
		if (guidance.canCraft(plan))
			return HomeReturns.isStorageFirst(plan, guidance.getHomeVisit(plan)) ? ObjectKind.RANGE : ObjectKind.BOWL;
		return plan.hasFish() ? ObjectKind.RANGE : null;
	}

	boolean targetService(ObjectKind service, boolean includeFishingSpots, String instruction)
	{
		Decision task = findServiceTask(service, includeFishingSpots);
		if (task == null)
			return false;
		targeting.setTask(task.target, task.kind, task.path);
		guidance.attempt.setInstruction(instruction);
		return true;
	}

	/** The nearest active service object in reach as a task of that kind; a fishing spot counts when asked. Null when none. */
	Decision findServiceTask(ObjectKind service, boolean includeFishingSpots)
	{
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
			if (e.getValue() == service || includeFishingSpots && e.getValue() == ObjectKind.FISH)
			{
				TileObject object = e.getKey();
				if (!guidance.isActive(object, e.getValue()))
					continue;
				goals.add(buildServiceGoal(object, e.getValue(), addCandidate(candidates, object)));
			}
		LocalPathfinder.Choice choice = targeting.lastRoute.chooseNearest(goals);
		return choice == null ? null : Decision.task(candidates.get(choice.index), service, choice.path);
	}

	/** The nearest active station of this kind on the player's plane as a task. Null when none is in reach. */
	Decision findStationTask(ObjectKind station)
	{
		WorldView view = guidance.client.getTopLevelWorldView();
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
		{
			TileObject object = e.getKey();
			if (e.getValue() != station || object.getPlane() != view.getPlane() || !guidance.isActive(object, station))
				continue;
			goals.add(buildServiceGoal(object, station, addCandidate(candidates, object)));
		}
		LocalPathfinder.Choice choice = targeting.lastRoute.chooseNearest(goals);
		return choice == null ? null : Decision.task(candidates.get(choice.index), station, choice.path);
	}

	/** The rooms next to current with a doorway this tick's route reaches, worked out once a tick. Read-only. */
	Set<Point> findReachableRoomSteps(Point current)
	{
		Set<Point> known = targeting.reachableSteps.get(current);
		if (known != null)
			return known;
		Set<Point> reachable = Collections.unmodifiableSet(computeReachableRoomSteps(current));
		targeting.reachableSteps.put(current, reachable);
		return reachable;
	}

	private Set<Point> computeReachableRoomSteps(Point current)
	{
		Set<Point> reachable = new HashSet<>();
		for (int[] delta : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
		{
			Point room = new Point(current.getX() + delta[0], current.getY() + delta[1]);
			Point boundary = guidance.opening.getBoundary(current, room);
			for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
			{
				TileObject object = entry.getKey();
				ObjectKind kind = entry.getValue();
				if (!kind.isDoorway() || !SceneGeometry.isNearBoundary(object.getWorldLocation(), boundary))
					continue;
				boolean passage = guidance.isOpenPassage(object, kind);
				if (!passage && !guidance.canUnlockRoom(object))
					continue;
				LocalPathfinder.Goal goal = passage ? SceneGeometry.buildCrossingGoal(0, object, delta[0], delta[1])
						: buildObjectGoal(object, 0, true);
				if (targeting.lastRoute.chooseNearest(Collections.singletonList(goal)) != null)
				{
					reachable.add(room);
					break;
				}
			}
		}
		return reachable;
	}

	LocalPathfinder.Goal buildUnlockGoal(TileObject object, int index, Point from, Point to)
	{
		LocalPathfinder.Goal goal = buildObjectGoal(object, index, true);
		if (to == null)
		{
			Point center = guidance.opening.getRoomCenter(from);
			WorldPoint at = object.getWorldLocation();
			int dx = at.getX() - center.getX(), dy = at.getY() - center.getY();
			to = Math.abs(dx) > Math.abs(dy) ? new Point(from.getX() + Integer.signum(dx), from.getY())
					: new Point(from.getX(), from.getY() + Integer.signum(dy));
		}
		int enterX = SceneGeometry.getCrossingCoordinate(goal.minX, goal.maxX, to.getX() - from.getX());
		int enterY = SceneGeometry.getCrossingCoordinate(goal.minY, goal.maxY, to.getY() - from.getY());
		// A possible demi behind the door: stay on the outer tile when lighting (out of reach once the room opens).
		GauntletRoom entered = guidance.gauntletMap.getRoom(to);
		LocalPathfinder.Goal unlock = entered != null && entered.canHaveDemiSpawn()
				? LocalPathfinder.unlockToExposed(index, goal.minX, goal.minY, goal.maxX, goal.maxY, enterX, enterY)
				: LocalPathfinder.unlockTo(index, goal.minX, goal.minY, goal.maxX, goal.maxY, enterX, enterY);
		if (isDoorSwitch(targeting.heldDoor, object))
			unlock.switchCost = DOOR_SWITCH_COST;
		if (isOuterLoopDoor(object, from, to))
			unlock.switchCost += OUTER_DOOR_COST;
		return unlock;
	}

	/**
	 * Tiles added to a doorway's door further from spawn during the opening loop. The doors of one doorway are 3 tiles
	 * apart, so the inner one is always lit (the outer one opens the same doorway but looks like a detour).
	 */
	static final int OUTER_DOOR_COST = 4;

	/**
	 * During the opening loop, true when the doorway's other door is nearer spawn along the wall. Doorways of the
	 * spawn room itself are left to the nearer door.
	 */
	private boolean isOuterLoopDoor(TileObject door, Point from, Point to)
	{
		Point home = GauntletMap.HOME;
		if (!guidance.opening.isActive() || from.equals(home) || to.equals(home))
			return false;
		WorldPoint at = door.getWorldLocation();
		if (guidance.opening.getRoomAt(at).equals(home))
			return false;
		Point center = guidance.opening.getRoomCenter(home);
		// A north/south doorway sits in a wall running east-west, so its two doors differ in x.
		boolean alongX = from.getX() == to.getX();
		int offset = alongX ? Math.abs(at.getX() - center.getX()) : Math.abs(at.getY() - center.getY());
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject other = entry.getKey();
			if (entry.getValue() != ObjectKind.DOOR || !isDoorSwitch(door, other))
				continue;
			WorldPoint otherAt = other.getWorldLocation();
			if ((alongX ? Math.abs(otherAt.getX() - center.getX()) : Math.abs(otherAt.getY() - center.getY())) < offset)
				return true;
		}
		return false;
	}

	/**
	 * Tiles the other door of a doorway must save before the highlight moves off the door lit last update. Diagonal
	 * steps make the doorway's two doors tie often, so the highlight would flicker between them.
	 */
	static final int DOOR_SWITCH_COST = 2;

	/**
	 * Tiles another door must save before exploration's highlight moves off the door shown last update (half a room;
	 * DefaultTask.keepHeldDoor). Walking off toward a different door still moves it within a tick or two.
	 */
	static final int DOOR_KEEP_MARGIN = 8;

	/** True when door is a different door of the same doorway as held (the doors of one doorway are side by side). */
	static boolean isDoorSwitch(TileObject held, TileObject door)
	{
		if (held == null || door == held)
			return false;
		WorldPoint heldAt = held.getWorldLocation(), doorAt = door.getWorldLocation();
		return heldAt.getPlane() == doorAt.getPlane() && heldAt.distanceTo2D(doorAt) <= 3;
	}

	/**
	 * Interaction goal for a kind of object. The bowl and the water pump are used from their side facing the room center
	 * (the bowl from the middle of that side; at home, once the loop's sides are known, the one nearer the loop's
	 * finishing door, findFinishSideTile); other kinds from any side.
	 */
	LocalPathfinder.Goal buildServiceGoal(TileObject object, ObjectKind kind, int index)
	{
		LocalPathfinder.Goal goal = buildObjectGoal(object, index, false);
		WorldView view = guidance.client.getTopLevelWorldView();
		if (kind == null || !kind.isHomeStation() || view == null || !guidance.opening.isInitialized())
			return goal;
		WorldPoint at = object.getWorldLocation();
		Point room = guidance.opening.getRoomAt(at);
		Point center = guidance.opening.getRoomCenter(room);
		int cx = center.getX() - view.getBaseX(), cy = center.getY() - view.getBaseY();
		if (kind == ObjectKind.RANGE)
		{
			goal.facing = findStandingSide(goal, view);
			if (goal.facing == null)
				goal.facing = SceneGeometry.findRangeTile(goal, cx, cy, findPump(room), SceneGeometry.readCollisionFlags(view));
			return goal;
		}
		Point finish = guidance.opening.getFinishRoom();
		if (kind == ObjectKind.BOWL && room.equals(GauntletMap.HOME) && finish != null)
		{
			Point door = guidance.opening.getBoundary(room, finish);
			int doorX = door.getX() - view.getBaseX(), doorY = door.getY() - view.getBaseY();
			goal.facing = SceneGeometry.findFinishSideTile(goal.minX, goal.minY, goal.maxX, goal.maxY, cx, cy, doorX, doorY);
		}
		else
			goal.facing = SceneGeometry.findFacingTile(goal.minX, goal.minY, goal.maxX, goal.maxY, cx, cy);
		return goal;
	}

	/** The player's tile when it is a side of the goal (the station is usable from here, so no walk); else null. */
	private Point findStandingSide(LocalPathfinder.Goal goal, WorldView view)
	{
		Player player = guidance.client.getLocalPlayer();
		WorldPoint here = player == null ? null : player.getWorldLocation();
		if (here == null)
			return null;
		int x = here.getX() - view.getBaseX(), y = here.getY() - view.getBaseY();
		return LocalPathfinder.isSideContact(goal, x, y) ? new Point(x, y) : null;
	}

	/** Scene footprint of the loaded water pump in a room, as {min, max}; null when none is loaded. */
	private Point[] findPump(Point room)
	{
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
		{
			WorldPoint at = e.getKey().getWorldLocation();
			if (e.getValue() == ObjectKind.WATER && at != null && guidance.opening.getRoomAt(at).equals(room))
				return new Point[]{SceneGeometry.getSceneMin(e.getKey()), SceneGeometry.getSceneMax(e.getKey())};
		}
		return null;
	}

	/** Interaction goal over an object's scene footprint. */
	static LocalPathfinder.Goal buildObjectGoal(TileObject object, int index, boolean exploration)
	{
		Point min = SceneGeometry.getSceneMin(object), max = SceneGeometry.getSceneMax(object);
		return new LocalPathfinder.Goal(index, min.getX(), min.getY(), max.getX(), max.getY(), exploration);
	}
}
