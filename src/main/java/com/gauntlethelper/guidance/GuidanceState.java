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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/**
 * The current guidance target Guidance chooses each update: the routed task, its path, the next and side cues and
 * the room route text. Guidance owns it and clears it; Routing, TaskPriorities and GuidanceLog receive it.
 */
public final class GuidanceState
{
	TileObject target;
	ObjectKind targetKind;
	/** The door lit last update; the other door of its doorway must be clearly nearer to take over. */
	TileObject heldDoor;
	NPC enemyTarget;
	// Side kill cue: an eligible enemy in the room while a needed resource stays the routed primary task.
	NPC sideEnemyTarget;
	WorldPoint groundTarget;
	Point inspectionGoal;
	List<Point> path = Collections.emptyList();
	LocalPathfinder lastRoute;
	/** The route over known rooms built from knownRouteBase, this tick's until clearRouteCache. */
	LocalPathfinder knownRoute, knownRouteBase;
	/** Each asked-for room's neighbors reachable this tick, until clearRouteCache. */
	final Map<Point, Set<Point>> reachableSteps = new HashMap<>();
	TileObject nextTarget;
	List<Point> nextPath = Collections.emptyList();
	ObjectKind nextKind;
	/** Next text in place of nextKind's label (the after-kill preview). */
	String nextLabel;
	/** Next as a visible demi instead of an object: the after-kill move is a demi already in the scene. */
	NPC nextEnemy;
	/**
	 * Set by the demi fight cue: Next previews the move after the kill instead of the nearest loaded object.
	 * afterKillRoom is where that move goes (null: no door to show); afterKillLabel is null when it is not certain.
	 */
	boolean demiFight;
	Point afterKillRoom;
	String afterKillLabel;
	String afterKillObjective;
	/** Next text for another needed pickup after the current one ("Pick up 3 raw fish"). */
	String nextPickupLabel;
	/** Next text for the trip's move after this room's work when the room tour has no exit (a teleport home). */
	String afterRoomLabel;
	/** The trip lights a room after this room's work (a resource no known spot covers): its doors end the tour. */
	boolean afterRoomExplore;
	/** Rooms next to tourExitRoom the search ties on, each a door that may end that room's tour; empty with no tie. */
	Set<Point> tourExits = Collections.emptySet();
	Point tourExitRoom;
	ObjectKind roomPlanNextKind, roomTerminalService;
	String roomRouteSummary;
	String tourSummary;

	/**
	 * Clears the routed task: target, kill cues, inspection goal, path and the room route plan. Guidance
	 * calls it from reset, scan, pauseGuidance and update; each then clears its own extra fields.
	 */
	void clearTask()
	{
		target = null;
		targetKind = null;
		enemyTarget = null;
		sideEnemyTarget = null;
		inspectionGoal = null;
		path = Collections.emptyList();
		roomPlanNextKind = null;
		roomTerminalService = null;
		roomRouteSummary = null;
		demiFight = false;
		afterKillRoom = null;
		afterKillLabel = null;
		afterKillObjective = null;
		nextPickupLabel = null;
		afterRoomLabel = null;
		afterRoomExplore = false;
		tourExits = Collections.emptySet();
		tourExitRoom = null;
	}

	/** Forgets the routes worked out from the map, the objects and lastRoute; those change between ticks. */
	void clearRouteCache()
	{
		knownRoute = null;
		knownRouteBase = null;
		reachableSteps.clear();
	}

	/** Sets the routed task: the object to use (null for a walking waypoint), its kind and the path to it. */
	void setTask(TileObject object, ObjectKind kind, List<Point> path)
	{
		target = object;
		targetKind = kind;
		this.path = path;
	}

	/** Sets the routed object and its kind; the path is set separately (or kept). */
	void setTarget(TileObject object, ObjectKind kind)
	{
		target = object;
		targetKind = kind;
	}

	/** The task walks through an open doorway: a floor waypoint with no object to click (no clickbox). */
	void setPassageWaypoint()
	{
		target = null;
		targetKind = ObjectKind.PASSAGE;
	}

	/** Sets the Next object and its kind; the Next path and label are set separately. */
	void setNext(TileObject object, ObjectKind kind)
	{
		nextTarget = object;
		nextKind = kind;
	}

	void clearNext()
	{
		nextTarget = null;
		nextPath = Collections.emptyList();
		nextKind = null;
		nextLabel = null;
		nextEnemy = null;
	}
}
