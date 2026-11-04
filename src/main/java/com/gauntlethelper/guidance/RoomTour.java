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
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * Room resource tours for Guidance: the order of a room's needed
 * nodes, ending at its exit or an eligible kill (RoomRoutePlanner), and the tour's first and next stops, which the
 * Next preview (NextTask) reads. gatherRoom and gatherCurrentRoom make a room's tour the current task.
 */
final class RoomTour
{
	private final Guidance guidance;
	private final GuidanceState targeting;
	private final RoomRoutePlanner roomPlanner = new RoomRoutePlanner();
	TileObject roomPlanFirst, roomPlanNext;
	/** The tour's goal for roomPlanNext (a door's Light-side goal), so the Next path ends where the tour would. */
	LocalPathfinder.Goal roomPlanNextGoal;
	/** A needed node in the room has no path; the task names it with "gather manually". */
	private boolean openingResourcePathMissing;

	RoomTour(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	boolean isOpeningResourcePathMissing() { return openingResourcePathMissing; }

	/** The opened-room preview gave up on the pathless node, so the task no longer names it as manual. */
	void clearOpeningResourcePathMissing()
	{
		openingResourcePathMissing = false;
	}

	/** Forgets the tour and the planner's cache (scan, attempt reset, pauseGuidance). */
	void clear()
	{
		roomPlanner.reset();
		beginUpdate();
	}

	/** Forgets last update's tour stops; the planner keeps its cache (start of Guidance.update). */
	void beginUpdate()
	{
		roomPlanFirst = null;
		roomPlanNext = null;
		openingResourcePathMissing = false;
	}

	/** Finish useful reachable nodes in this room before advancing along the search route. */
	boolean gatherCurrentRoom(Requirements plan, LocalPathfinder route, WorldView view, WorldPoint here)
	{
		Point room = guidance.orientation.findOpeningWorkRoom(here);
		// An off-loop pond stop on the way to the loop's home visit tours the room's ponds only.
		boolean pondStop = guidance.opening.isActive() && !room.equals(guidance.opening.getCurrentRoom());
		return gatherRoom(plan, route, view, room, pondStop ? ObjectKind.FISH.label : null);
	}

	/** Target the room tour of useful reachable nodes in resourceRoom, which may be a lit room ahead on the route. */
	boolean gatherRoom(Requirements plan, LocalPathfinder route, WorldView view, Point resourceRoom)
	{
		return gatherRoom(plan, route, view, resourceRoom, null);
	}

	/** As gatherRoom, limited to the nodes whose label starts with label when it is given. */
	private boolean gatherRoom(Requirements plan, LocalPathfinder route, WorldView view, Point resourceRoom, String label)
	{
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = guidance.routing.findResourceGoals(plan, view, resourceRoom, label, candidates);
		LocalPathfinder.Choice choice = chooseRoomResources(route, candidates, goals, resourceRoom);
		if (choice == null)
			return false;
		TileObject chosen = candidates.get(choice.index);
		targeting.setTask(chosen, guidance.objects.get(chosen), choice.path);
		return true;
	}

	LocalPathfinder.Choice chooseRoomResources(LocalPathfinder route, List<TileObject> candidates, List<LocalPathfinder.Goal> resources, Point room)
	{
		if (resources.isEmpty())
			return null;
		openingResourcePathMissing = false;
		List<LocalPathfinder.Goal> exits = guidance.routing.findRoomExitGoals(room, candidates);
		Map<Integer, Integer> combatTail = new HashMap<>();
		List<LocalPathfinder.Goal> terminal = findRoomCombatGoals(room, route, exits, combatTail);
		// One T1 ore/bark/linum tirinum node normally covers the whole 3-item need: plan only the forecast node count per
		// resource, so a redundant duplicate is neither a tour stop nor the Next preview. If the chosen node depletes
		// short, the remaining deficit brings the other node straight back on the next update.
		Map<Integer, Integer> groupOf = new HashMap<>(), quota = new HashMap<>();
		Requirements plan = guidance.attempt.getNavigationPlan();
		if (plan != null)
			for (LocalPathfinder.Goal goal : resources)
			{
				ObjectKind kind = guidance.objects.get(candidates.get(goal.index));
				if (kind == null || kind.resource == null)
					continue;
				int needed = plan.getRow(kind.resource).getMissing();
				if (kind.resource == Requirement.FISH)
					needed = Math.min(needed, guidance.attempt.getFishAllowance());
				if (needed <= 0)
					continue;
				groupOf.put(goal.index, kind.ordinal());
				quota.put(kind.ordinal(), guidance.harvests.countNodesFor(kind, needed, candidates));
			}
		RoomRoutePlanner.Result tour = roomPlanner.plan(route, resources, terminal.isEmpty() ? exits : terminal, combatTail, groupOf, quota);
		if (tour == null)
		{
			LocalPathfinder.Choice reachable = route.chooseNearest(resources);
			if (reachable != null)
				return reachable;
			Player player = guidance.client.getLocalPlayer();
			WorldPoint at = player == null ? null : player.getWorldLocation();
			if (guidance.opening.isActive() && at != null && room != null && guidance.opening.isRoomInterior(room, at.getX(), at.getY()))
			{
				// A missing route is not evidence that a visibly active needed node was gathered.
				LocalPathfinder.Goal nearest = null;
				int best = Integer.MAX_VALUE;
				LocalPoint local = player.getLocalLocation();
				for (LocalPathfinder.Goal goal : resources)
				{
					int dx = Math.max(goal.minX - local.getSceneX(), Math.max(0, local.getSceneX() - goal.maxX));
					int dy = Math.max(goal.minY - local.getSceneY(), Math.max(0, local.getSceneY() - goal.maxY));
					int distance = Math.max(dx, dy);
					if (distance < best || distance == best
							&& (nearest == null || goal.minX < nearest.minX || goal.minX == nearest.minX
							&& goal.minY < nearest.minY))
					{
						nearest = goal;
						best = distance;
					}
				}
				openingResourcePathMissing = true;
				targeting.roomRouteSummary = Guidance.PATHLESS_RESOURCE;
				return new LocalPathfinder.Choice(nearest.index, Collections.emptyList());
			}
			return null;
		}
		roomPlanFirst = candidates.get(tour.order.get(0));
		int next = tour.order.size() > 1 ? tour.order.get(1) : tour.exitIndex;
		roomPlanNext = next < 0 || next >= candidates.size() ? null : candidates.get(next);
		roomPlanNextGoal = null;
		// A resource stop ends on the side the tour planned for it (facing the stop or exit after it), the tile the
		// path ends on once that stop is current; the exit keeps its own goal below.
		Point side = tour.order.size() > 1 ? roomPlanner.getPlannedSecondStop() : null;
		if (side != null)
			roomPlanNextGoal = LocalPathfinder.walkTo(next, side.getX(), side.getY());
		for (List<LocalPathfinder.Goal> list : Arrays.asList(resources, terminal.isEmpty() ? exits : terminal))
			for (LocalPathfinder.Goal goal : list)
				if (goal.index == next && roomPlanNextGoal == null)
					roomPlanNextGoal = goal;
		targeting.roomPlanNextKind = roomPlanNext == null ? null
				: next == tour.exitIndex && targeting.roomTerminalService != null ? targeting.roomTerminalService
				: guidance.objects.get(roomPlanNext);
		if (roomPlanNext != null && guidance.isOpenPassage(roomPlanNext))
			roomPlanNext = null;
		StringJoiner order = new StringJoiner(" → ");
		for (int index : tour.order)
			order.add(guidance.objects.get(candidates.get(index)).label);
		// One exit wording: "Light exit" for a door still to light, else "Exit"; a trip that ends the room by going
		// home names that instead.
		if (tour.exitIndex >= candidates.size())
			order.add("Defeat enemy → Exit");
		else if (tour.exitIndex >= 0)
			order.add(targeting.roomTerminalService != null ? targeting.roomTerminalService.label
					: guidance.isOpenPassage(candidates.get(tour.exitIndex))
					? "Exit" : "Light exit");
		else if (targeting.afterRoomLabel != null)
			order.add(targeting.afterRoomLabel.startsWith("Use teleport") ? "Teleport home" : "Return home");
		targeting.roomRouteSummary = "Room route: " + order + " (" + tour.tiles + " path tiles)";
		return new LocalPathfinder.Choice(tour.order.get(0), tour.path);
	}

	List<LocalPathfinder.Goal> findRoomCombatGoals(Point room, LocalPathfinder route, List<LocalPathfinder.Goal> exits, Map<Integer, Integer> tail)
	{
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		Requirements plan = guidance.attempt.getNavigationPlan();
		if (plan == null || !guidance.config.highlightTargetedEnemies() || guidance.priorities.hunt.isOrdinarySuppressed()
				|| guidance.getEnemyReason(plan, plan.getInventory()) == null)
			return goals;
		GauntletRoom knownRoom = guidance.gauntletMap.getRoom(room);
		if (knownRoom != null && knownRoom.hasLivingDemiBoss())
			return goals;
		WorldView view = guidance.client.getTopLevelWorldView();
		if (view.npcs() == null)
			return goals;
		for (NPC npc : view.npcs())
		{
			if (npc.isDead() || !EnemyRules.isOrdinary(npc.getId()) || !guidance.opening.getRoomAt(npc.getWorldLocation()).equals(room))
				continue;
			WorldArea area = npc.getWorldArea();
			LocalPoint npcTile = area == null ? npc.getLocalLocation() : LocalPoint.fromWorld(view, area.toWorldPoint());
			if (npcTile == null)
				continue;
			int size = area == null ? Math.max(1, npc.getFootprintSize()) : Math.max(area.getWidth(), area.getHeight());
			LocalPathfinder.Goal mob = new LocalPathfinder.Goal(0, npcTile.getSceneX(), npcTile.getSceneY(),
					npcTile.getSceneX() + size - 1, npcTile.getSceneY() + size - 1, false);
			for (Point contact : route.findContacts(mob))
			{
				int index = 1000000 + goals.size();
				LocalPathfinder.Choice exit = route.routeFrom(contact).chooseNearest(exits);
				if (!exits.isEmpty() && exit == null)
					continue;
				goals.add(LocalPathfinder.walkTo(index, contact.getX(), contact.getY()));
				tail.put(index, exit == null ? 0 : exit.path.size() - 1);
			}
		}
		return goals;
	}
}
