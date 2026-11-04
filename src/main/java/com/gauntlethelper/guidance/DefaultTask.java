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
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.Instructions;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * The current object task for Guidance.update: the first room and the
 * opening loop's rooms and doors, a path into an opened loop room and on to the first home station, the first home
 * visit and outer-room search after the loop, and otherwise the nearest useful station or Light door.
 */
final class DefaultTask
{
	private final Guidance guidance;
	private final GuidanceState targeting;

	DefaultTask(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	/**
	 * The current task among the loaded objects, once no water or post-loop task claimed the update: in the first
	 * room its resources, then its passage; in the opening loop the room's resources, then the loop's next door
	 * (an opened loop room is planned from here, through the doorway); otherwise the nearest useful station or
	 * Light door, keeping the door shown last update (keepHeldDoor).
	 */
	void chooseTarget(Requirements plan, LocalPathfinder route, WorldView view, Player player, boolean openingActive)
	{
		for (int pass = 0; pass < 2; pass++)
		{
			boolean firstFocus = guidance.orientation.isFirstRoomFocus();
			Candidates found = collectCandidates(plan, view, player, firstFocus, openingActive);
			Point resourceRoom = findResourceRoom(firstFocus, openingActive, player.getWorldLocation());
			LocalPathfinder.Choice choice = null;
			if (firstFocus || openingActive)
				choice = guidance.tour.chooseRoomResources(route, found.objects, found.roomResources, resourceRoom);
			if (firstFocus && choice == null && guidance.orientation.isFirstRoomEntered())
			{
				guidance.orientation.finishFirstRoom();
				continue; // Re-run normal priorities immediately for an empty/completed first room.
			}
			if (choice == null)
				choice = keepHeldDoor(route, found.objects, found.goals, route.chooseNearest(found.goals));
			if (choice != null)
				takeChoice(plan, route, view, found.objects.get(choice.index), choice.path, firstFocus, openingActive);
			break;
		}
	}

	/** How a candidate object is approached as the task. */
	private enum Approach
	{
		/** Walk through an open doorway to the next room's entry tile. */
		PASSAGE,
		/** Light a door into an unopened room. */
		UNLOCK,
		/** Use a needed resource node or station. */
		SERVICE
	}

	/** The task's candidate objects, their route goals in the same order, and the goals of the room tour's resources. */
	private static final class Candidates
	{
		final List<TileObject> objects = new ArrayList<>();
		final List<LocalPathfinder.Goal> goals = new ArrayList<>();
		final List<LocalPathfinder.Goal> roomResources = new ArrayList<>();
	}

	private Candidates collectCandidates(Requirements plan, WorldView view, Player player, boolean firstFocus, boolean openingActive)
	{
		Candidates found = new Candidates();
		Point workRoom = findResourceRoom(firstFocus, openingActive, player.getWorldLocation());
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			Approach approach = findApproach(object, kind, plan, view, firstFocus, openingActive, workRoom);
			if (approach == null)
				continue;
			int index = Routing.addCandidate(found.objects, object);
			LocalPathfinder.Goal goal = buildGoal(approach, object, kind, index, player, firstFocus, openingActive);
			found.goals.add(goal);
			if ((firstFocus || openingActive) && kind.resource != null)
				found.roomResources.add(goal);
		}
		return found;
	}

	/**
	 * Whether the object is a task candidate, and how: in the first room its resources and the passage it was entered
	 * by; in the opening loop the room's resources and the loop's next door; otherwise needed stations and nodes and,
	 * while exploring, doors to light. Null when it is none of these. In the opening loop workRoom is the room whose
	 * resources are toured: the loop's current room, or an off-loop pond stop on the way home (its ponds only).
	 */
	private Approach findApproach(TileObject object, ObjectKind kind, Requirements plan, WorldView view, boolean firstFocus, boolean openingActive,
			Point workRoom)
	{
		if (object.getPlane() != view.getPlane() || !guidance.isActive(object, kind))
			return null;
		if (guidance.orientation.isAwaitingOpeningOrientation() && kind.isDoorway())
			return null;
		WorldPoint point = object.getWorldLocation();
		if (firstFocus)
		{
			if (kind.resource == null)
			{
				boolean entryPassage = point.distanceTo2D(guidance.orientation.getFirstDoorLocation()) <= 3 && guidance.isOpenPassage(object, kind);
				return entryPassage ? Approach.PASSAGE : null;
			}
			if (!guidance.opening.getRoomAt(point).equals(guidance.orientation.getFirstRoom()))
				return null;
			return guidance.isUseful(kind, plan) ? Approach.SERVICE : null;
		}
		if (openingActive && kind.isDoorway())
		{
			if (!guidance.opening.isNextDoor(point.getX(), point.getY()))
				return null;
			if (guidance.isOpenPassage(object, kind))
				return Approach.PASSAGE;
			if (kind == ObjectKind.DOOR && guidance.canUnlockRoom(object))
				return Approach.UNLOCK;
			return guidance.isUseful(kind, plan) ? Approach.SERVICE : null;
		}
		if (openingActive && (kind.resource == null || !guidance.opening.getRoomAt(point).equals(workRoom)))
			return null;
		if (openingActive && kind != ObjectKind.FISH && !workRoom.equals(guidance.opening.getCurrentRoom()))
			return null;
		if (kind == ObjectKind.DOOR && plan.needsExploration() && guidance.canUnlockRoom(object))
			return Approach.UNLOCK;
		return guidance.isUseful(kind, plan) ? Approach.SERVICE : null;
	}

	private LocalPathfinder.Goal buildGoal(Approach approach, TileObject object, ObjectKind kind, int index, Player player, boolean firstFocus,
			boolean openingActive)
	{
		if (approach == Approach.PASSAGE)
		{
			Point destination = firstFocus
					? guidance.orientation.getFirstEntryDestination(SceneGeometry.getSceneMin(object), SceneGeometry.getSceneMax(object))
					: guidance.opening.getEntryDestination(SceneGeometry.getSceneMin(object), SceneGeometry.getSceneMax(object));
			return LocalPathfinder.walkTo(index, destination.getX(), destination.getY());
		}
		// The loop door lies between the loop's current and next rooms. The doorway threshold already counts as the
		// next room, so the player's tile there makes from == to, which would lose the inner-door cost.
		if (approach == Approach.UNLOCK)
			return guidance.routing.buildUnlockGoal(object, index, openingActive ? guidance.opening.getCurrentRoom()
					: guidance.opening.getRoomAt(player.getWorldLocation()),
					openingActive ? guidance.opening.getNextRoom() : null);
		return guidance.routing.buildServiceGoal(object, kind, index);
	}

	/**
	 * Makes the chosen object the task: latches the opening's first door, turns an open passage into a walking
	 * waypoint, and plans an opened loop room's tour from here, through the doorway.
	 */
	private void takeChoice(Requirements plan, LocalPathfinder route, WorldView view, TileObject chosen, List<Point> path, boolean firstFocus,
			boolean openingActive)
	{
		targeting.setTarget(chosen, guidance.objects.get(chosen));
		if (!guidance.orientation.isFirstRoomOpened() && !guidance.orientation.isFirstRoomFinished()
				&& openingActive && guidance.opening.getStep() == 0 && targeting.targetKind == ObjectKind.DOOR)
			guidance.orientation.latchFirstDoor(chosen.getWorldLocation(), guidance.opening.getNextRoom());
		if ((openingActive || firstFocus) && guidance.isOpenPassage(targeting.target, targeting.targetKind))
		{
			guidance.orientation.expectPassageArrival(firstFocus ? guidance.orientation.getFirstRoom() : guidance.opening.getNextRoom());
			targeting.setPassageWaypoint();
			targeting.enemyTarget = null;
		}
		targeting.path = path;
		// The next loop room is already open: plan its resource tour from here, through the doorway, instead of
		// walking to the entry point first. Arrival still counts on entry.
		// The first room too: its passage is only chosen when none of its resources are routable.
		if (openingActive && targeting.targetKind == ObjectKind.PASSAGE && guidance.orientation.getPendingPassageRoom() != null)
			previewOpenedRoom(plan, route, view, guidance.orientation.getPendingPassageRoom());
		describeStationOverStaffSearch(plan);
	}

	/** The staff search's text goes up before the target is chosen; a home station chosen as nearest says its own work. */
	private void describeStationOverStaffSearch(Requirements plan)
	{
		ObjectKind kind = targeting.targetKind;
		if (kind == null || !kind.isHomeStation() || !Instructions.FIND_STAFF_FRAME.equals(guidance.attempt.getInstruction()))
			return;
		Requirements.Craft craft = kind == ObjectKind.BOWL ? guidance.getHomeVisit(plan).planCrafts().getNextCraft() : null;
		guidance.attempt.setInstruction(craft == null ? kind.label : kind.label + ": " + craft.label);
	}

	/** The room whose resources the first room or opening loop tours; null when the room grid is not known yet. */
	private Point findResourceRoom(boolean firstFocus, boolean openingActive, WorldPoint here)
	{
		if (firstFocus)
			return guidance.orientation.getFirstRoom();
		if (openingActive)
			return guidance.orientation.findOpeningWorkRoom(here);
		if (guidance.opening.isInitialized())
			return guidance.opening.getRoomAt(here);
		return null;
	}

	/**
	 * The nearest door, measured afresh from the player every update, would keep changing on the way to it whenever
	 * another door became a tile or two nearer. Keep the door shown last update while it is still a candidate, unless
	 * the new choice is not a door or is Routing.DOOR_KEEP_MARGIN tiles nearer. The other door of the same doorway
	 * keeps its own smaller DOOR_SWITCH_COST.
	 */
	private LocalPathfinder.Choice keepHeldDoor(LocalPathfinder route, List<TileObject> candidates, List<LocalPathfinder.Goal> goals,
		LocalPathfinder.Choice choice)
	{
		TileObject held = targeting.heldDoor;
		if (choice == null || held == null)
			return choice;
		TileObject chosen = candidates.get(choice.index);
		if (chosen == held || guidance.objects.get(chosen) != ObjectKind.DOOR || Routing.isDoorSwitch(held, chosen))
			return choice;
		int index = candidates.indexOf(held);
		if (index < 0)
			return choice;
		LocalPathfinder.Choice kept = route.chooseNearest(Collections.singletonList(goals.get(index)));
		return kept != null && kept.path.size() < choice.path.size() + Routing.DOOR_KEEP_MARGIN ? kept : choice;
	}

	/** Exploration destinations only: no NPC lookup or combat-position selection. */
	boolean updatePostLoop(Requirements plan, LocalPathfinder route, WorldView view, Player player)
	{
		if (!guidance.opening.hasCompletedSweep() || plan.isReady() || plan.isInventoryAction())
			return false;
		if (!guidance.attempt.isOpeningHomeFinished())
		{
			HomeCraftPlan home = guidance.getHomeVisit(plan).planCrafts();
			// Armor alone never starts the first home trip: without a frame the visit can't make the staff, so the
			// room's resources and kills come first.
			// Already at home, the armor is still made before leaving to find the frame.
			if (home.getInstruction() != null && (!plan.needsOpeningStaff() || plan.isStaffInHand() || guidance.isHomeTile(player.getWorldLocation())))
			{
				boolean storageFirst = home.getNextCraft() != null && HomeReturns.isStorageFirst(plan, guidance.getHomeVisit(plan));
				guidance.attempt.setInstruction(storageFirst ? "Cook and store fish, then craft at home" : home.getInstruction());
				guidance.attempt.startStaffReturn();
				if (home.getNextCraft() == null)
					return true; // Space must be made before selecting the recipe.
				return guidance.routing.routeToRoom(route, view, player, GauntletMap.HOME, storageFirst ? ObjectKind.RANGE : ObjectKind.BOWL, null);
			}
			// A basic staff starts the demi hunt; no shard roaming or home trip only for the attuned upgrade.
			if (plan.needsOpeningStaff() && !plan.hasStaff())
			{
				guidance.attempt.setInstruction(Instructions.getStaffInstruction(plan));
				return false;
			}
			guidance.attempt.finishOpeningHome();
		}
		if (guidance.attempt.isOuterRoomChecked())
			return false;
		Point outer = guidance.opening.getOuterRoom(guidance.orientation.openingStartRoom);
		WorldPoint position = player.getWorldLocation();
		Point current = guidance.opening.getRoomAt(position);
		GauntletMap.SearchTarget known = guidance.perimeter.findKnownShortcut(guidance.gauntletMap, current, outer,
				guidance.routing.findReachableRoomSteps(current)::contains, plan, guidance.demiHistory);
		if (known != null)
		{
			guidance.attempt.markOuterRoomChecked();
			guidance.perimeter.prefer(guidance.gauntletMap.getRelative(known.objective), plan);
			targeting.inspectionGoal = guidance.gauntletMap.getRelative(known.objective);
			if (guidance.tour.gatherCurrentRoom(plan, route, view, position))
			{
				guidance.attempt.setInstruction("Demi hunting: " + targeting.targetKind.label);
				return true;
			}
			return guidance.routing.navigateRoom(known.waypoint, "Demi hunting: " + known.getLabel());
		}
		targeting.inspectionGoal = outer;
		WorldPoint here = player.getWorldLocation();
		if (guidance.tour.gatherCurrentRoom(plan, route, view, here))
		{
			guidance.attempt.startOutwardTrip();
			guidance.attempt.setInstruction("Demi search: " + targeting.targetKind.label);
			return true;
		}
		if (guidance.opening.isRoomInterior(outer, here.getX(), here.getY()))
		{
			guidance.attempt.markOuterRoomChecked();
			guidance.attempt.setInstruction("Outer room reached: check for a demi-boss");
			return true;
		}
		guidance.attempt.startOutwardTrip();
		guidance.attempt.setInstruction("Demi search: head away from Hunllef to the outermost room");
		guidance.routing.routeToRoom(route, view, player, outer, null, null);
		return true;
	}

	/** Target the first planned needed resource of an opened, not yet entered loop room (path crosses the doorway). */
	private void previewOpenedRoom(Requirements plan, LocalPathfinder route, WorldView view, Point room)
	{
		if (room.equals(GauntletMap.HOME))
		{
			previewHomeArrival(plan, route);
			return;
		}
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = guidance.routing.findResourceGoals(plan, view, room, null, candidates);
		if (goals.isEmpty())
		{
			previewRoomExit(plan, route, room);
			return;
		}
		LocalPathfinder.Choice choice = guidance.tour.chooseRoomResources(route, candidates, goals, room);
		if (choice == null || choice.path.isEmpty() || guidance.tour.isOpeningResourcePathMissing())
		{
			guidance.tour.clearOpeningResourcePathMissing();
			return;
		}
		TileObject chosen = candidates.get(choice.index);
		targeting.setTask(chosen, guidance.objects.get(chosen), choice.path);
		guidance.attempt.setInstruction("Opening loop " + Math.min(5, guidance.opening.getStep() + 1) + "/5: " + targeting.targetKind.label);
	}

	/**
	 * An opened loop room with no needed node: path through it to its next stop, an eligible kill there, else its exit
	 * (the next loop door, or home through spawn's doorway), instead of ending two tiles inside the doorway.
	 * A kill stop is path only; its Defeat cue takes over on entry. Arrival still counts on entry.
	 */
	private void previewRoomExit(Requirements plan, LocalPathfinder route, Point room)
	{
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> exits = guidance.routing.findRoomExitGoals(room, candidates);
		Map<Integer, Integer> tail = new HashMap<>();
		LocalPathfinder.Choice kill = null;
		int best = Integer.MAX_VALUE;
		for (LocalPathfinder.Goal goal : guidance.tour.findRoomCombatGoals(room, route, exits, tail))
		{
			LocalPathfinder.Choice choice = route.chooseNearest(Collections.singletonList(goal));
			if (choice == null || choice.path.isEmpty())
				continue;
			int cost = choice.path.size() + tail.getOrDefault(goal.index, 0);
			if (cost < best)
			{
				best = cost;
				kill = choice;
			}
		}
		if (kill != null)
		{
			targeting.path = kill.path;
			return;
		}
		if (GauntletMap.HOME.equals(guidance.opening.getRoomAfter(room)))
		{
			// A home visit came first, so the player stands where this room's exit leads: nothing is left to enter it for.
			if (isPlayerInsideHome())
			{
				guidance.opening.skipRoom(room);
				return;
			}
			previewHomeArrival(plan, route);
			if (targeting.target != null)
				return;
		}
		LocalPathfinder.Choice choice = route.chooseNearest(exits);
		if (choice == null || choice.path.isEmpty())
			return;
		targeting.path = choice.path;
		TileObject exit = candidates.get(choice.index);
		if (guidance.isOpenPassage(exit))
			return;
		targeting.setTarget(exit, ObjectKind.DOOR);
		// The door lights the room after the one being entered, numbered as once inside (OpeningRoute.getLabel).
		guidance.attempt.setInstruction("Opening loop " + Math.min(5, guidance.opening.getStep() + 2) + "/5: " + targeting.targetKind.label);
	}

	private boolean isPlayerInsideHome()
	{
		WorldPoint here = guidance.client.getLocalPlayer().getWorldLocation();
		return guidance.opening.isRoomInterior(GauntletMap.HOME, here.getX(), here.getY());
	}

	/**
	 * Returning home at the end of the loop: path through the doorway straight to the first home station, the bowl when
	 * a first-home craft is ready, else the range when cooking is due, rather than stopping just inside spawn.
	 * With space to make first, the doorway path stays. Arrival still counts on entry.
	 */
	private void previewHomeArrival(Requirements plan, LocalPathfinder route)
	{
		HomeCraftPlan home = guidance.getHomeVisit(plan).previewCrafts();
		ObjectKind station = getHomeArrivalStation(home, plan);
		if (station == null)
			return;
		List<TileObject> candidates = new ArrayList<>();
		LocalPathfinder.Choice choice = route.chooseNearest(findStationGoals(station, candidates));
		if (choice == null || choice.path.isEmpty())
			return;
		targeting.setTask(candidates.get(choice.index), station, choice.path);
		guidance.attempt.setInstruction(station == ObjectKind.BOWL ? home.getInstruction() : station.label);
	}

	/**
	 * The first home station after the loop: the bowl for a ready craft, else the range when cooking is due. The range
	 * also comes first when storing fish makes room for crafts waiting for slots.
	 */
	ObjectKind getHomeArrivalStation(HomeCraftPlan home, Requirements plan)
	{
		if (home.getNextCraft() != null && HomeReturns.isStorageFirst(plan, guidance.getHomeVisit(plan)))
			return ObjectKind.RANGE;
		return home.getNextCraft() != null ? ObjectKind.BOWL
				: home.getInstruction() == null && guidance.isUseful(ObjectKind.RANGE, plan) ? ObjectKind.RANGE : null;
	}

	/** Service goals for every active loaded station of this kind; candidates receives the objects in goal order. */
	List<LocalPathfinder.Goal> findStationGoals(ObjectKind station, List<TileObject> candidates)
	{
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			if (entry.getValue() != station || !guidance.isActive(entry.getKey(), station))
				continue;
			goals.add(guidance.routing.buildServiceGoal(entry.getKey(), station, Routing.addCandidate(candidates, entry.getKey())));
		}
		return goals;
	}
}
