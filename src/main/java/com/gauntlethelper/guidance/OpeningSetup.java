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
import com.gauntlethelper.map.MapCoordinates;
import com.gauntlethelper.plan.OpeningBudget;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opening setup and orientation for Guidance: locating spawn, the physical Hunllef side and map orientation, the
 * first-room latch, and opening-loop position observation.
 */
final class OpeningSetup
{
	private static final Logger log = LoggerFactory.getLogger(OpeningSetup.class);
	private final Guidance guidance;
	private boolean awaitingOpeningOrientation;
	private boolean initialDoorScanNeeded;
	int openingStartRoom = -1; // World-oriented start encoding (from the physical Hunllef side).
	private int mapStartRoom = -1; // Raw GAUNTLET_START varbit.
	private Point mapBoss;
	private Point worldBoss;
	private MapCoordinates.Orientation mapOrientation = MapCoordinates.Orientation.IDENTITY;
	String openingWaitReason;
	// The first-room latch: private, each change has a method named for it (finishFirstRoom, latchFirstDoor, ...).
	private Point pendingPassageRoom;
	private WorldPoint firstDoorLocation;
	private Point firstRoom;
	private boolean firstRoomOpened;
	private boolean firstRoomEntered;
	private boolean firstRoomFinished;

	OpeningSetup(Guidance guidance)
	{
		this.guidance = guidance;
	}

	boolean isAwaitingOpeningOrientation() { return awaitingOpeningOrientation; }

	boolean isInitialDoorScanNeeded() { return initialDoorScanNeeded; }

	Point getPendingPassageRoom() { return pendingPassageRoom; }

	WorldPoint getFirstDoorLocation() { return firstDoorLocation; }

	Point getFirstRoom() { return firstRoom; }

	boolean isFirstRoomOpened() { return firstRoomOpened; }

	boolean isFirstRoomEntered() { return firstRoomEntered; }

	boolean isFirstRoomFinished() { return firstRoomFinished; }

	/** The first room is open and its work not yet finished: guidance stays on it. */
	boolean isFirstRoomFocus()
	{
		return firstRoomOpened && !firstRoomFinished;
	}

	/** The scene objects were cleared, so the opening no longer waits on the old scan's orientation. */
	void clearOpeningOrientationWait()
	{
		awaitingOpeningOrientation = false;
	}

	/**
	 * At the start of each update: the first door is still to be found while the first room is unopened and the
	 * opening has not moved past its first step.
	 */
	void beginUpdate()
	{
		initialDoorScanNeeded = !firstRoomOpened
				&& !firstRoomFinished
				&& (!guidance.opening.isInitialized() || guidance.opening.getStep() == 0);
	}

	/** After the object task: a chosen target ends this update's initial door scan. */
	void endInitialDoorScanIfTargeted(TileObject target)
	{
		if (target != null)
			initialDoorScanNeeded = false;
	}

	/** The first room has nothing left to do (or was left): normal priorities take over. */
	void finishFirstRoom()
	{
		firstRoomFinished = true;
	}

	/** The opening's first Light door is chosen: remember where it is and which room it lights. */
	void latchFirstDoor(WorldPoint door, Point room)
	{
		firstDoorLocation = door;
		firstRoom = room;
	}

	/** The task is to walk through an open passage into `room`; arrival there is confirmed by observePosition. */
	void expectPassageArrival(Point room)
	{
		pendingPassageRoom = room;
	}

	/** Guidance's share of an attempt reset (awaitingOpeningOrientation is cleared with the scene objects). */
	void reset()
	{
		initialDoorScanNeeded = false;
		firstDoorLocation = null;
		firstRoom = null;
		firstRoomOpened = false;
		firstRoomEntered = false;
		firstRoomFinished = false;
		openingStartRoom = -1;
		mapStartRoom = -1;
		mapBoss = null;
		worldBoss = null;
		mapOrientation = MapCoordinates.Orientation.IDENTITY;
		openingWaitReason = null;
		pendingPassageRoom = null;
	}

	boolean isDiscoveredTile(WorldPoint p)
	{
		if (!guidance.opening.isInitialized())
			return false;
		Point room = guidance.opening.getRoomAt(p);
		int col = openingStartRoom % GauntletMap.SIZE + room.getX();
		int row = openingStartRoom / GauntletMap.SIZE - room.getY();
		GauntletRoom known = guidance.gauntletMap.getRoom(room);
		return known != null && (known.isDiscovered() || hasFoundBit(col, row));
	}

	/** Map "room found" bit for a world-oriented grid square (converted to the game's map square). */
	private boolean hasFoundBit(int worldColumn, int worldRow)
	{
		if (worldColumn < 0 || worldColumn >= GauntletMap.SIZE || worldRow < 0 || worldRow >= GauntletMap.SIZE)
			return false;
		int[] m = MapCoordinates.worldToMapSquare(mapOrientation, worldColumn, worldRow);
		return m[0] >= 0 && m[0] < GauntletMap.SIZE && m[1] >= 0 && m[1] < GauntletMap.SIZE
				&& guidance.client.getVarbitValue(VarbitID.GAUNTLET_ROOM_0_FOUND + m[1] * GauntletMap.SIZE + m[0]) == 1;
	}

	/** Spawn-relative world room for a game map square. */
	Point getWorldRoomOfMapSquare(int mapColumn, int mapRow)
	{
		int[] w = MapCoordinates.mapToWorldSquare(mapOrientation, mapColumn, mapRow);
		return MapCoordinates.getRelativeRoom(openingStartRoom, w[0], w[1]);
	}

	/**
	 * Hunllef's side of spawn from the room itself: at the start of an attempt only the boss wall has lit doorway
	 * objects (the three exits are unlit Light doors). Null when that is not unambiguous (e.g. enabled mid-run).
	 */
	private Point findPhysicalBossDirection(WorldPoint home)
	{
		List<TileObject> lit = new ArrayList<>();
		List<WorldPoint> all = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
		{
			if (!e.getValue().isDoorway())
				continue;
			WorldPoint at = e.getKey().getWorldLocation();
			if (at.getPlane() != home.getPlane() || at.distanceTo2D(home) > 13)
				continue;
			all.add(at);
			if (guidance.isOpenPassage(e.getKey(), e.getValue()))
				lit.add(e.getKey());
		}
		if (lit.isEmpty())
			return null;
		// Wall objects are two tiles thick across their wall: N/S walls extend in y, E/W walls in x (live logs).
		// Hunllef is on the far side of that lit doorway from the bowl; no room center or neighboring
		// room is involved.
		int across = 0, along = 0;
		double sumX = 0, sumY = 0;
		for (TileObject object : lit)
		{
			WorldPoint at = object.getWorldLocation();
			int spanX = 0, spanY = 0;
			if (object instanceof GameObject)
			{
				GameObject gameObject = (GameObject) object;
				spanX = gameObject.getSceneMaxLocation().getX() - gameObject.getSceneMinLocation().getX();
				spanY = gameObject.getSceneMaxLocation().getY() - gameObject.getSceneMinLocation().getY();
			}
			if (spanY > spanX)
				across++;
			else if (spanX > spanY)
				along++;
			sumX += at.getX() + spanX / 2.0;
			sumY += at.getY() + spanY / 2.0;
		}
		double meanX = sumX / lit.size(), meanY = sumY / lit.size();
		if (across > 0 && along == 0)
			return new Point(0, meanY > home.getY() ? 1 : -1);
		if (along > 0 && across == 0)
			return new Point(meanX > home.getX() ? 1 : -1, 0);
		if (across > 0)
			return null; // Lit doorways on both kinds of wall: not the untouched start-of-attempt spawn room.
		// Objects without a footprint (not seen live): fall back to the middle of the box spanning the nearby doors.
		double cx = (all.stream().mapToInt(WorldPoint::getX).min().orElse(0) + all.stream().mapToInt(WorldPoint::getX).max().orElse(0)) / 2.0;
		double cy = (all.stream().mapToInt(WorldPoint::getY).min().orElse(0) + all.stream().mapToInt(WorldPoint::getY).max().orElse(0)) / 2.0;
		Point direction = null;
		for (TileObject object : lit)
		{
			WorldPoint at = object.getWorldLocation();
			double dx = at.getX() - cx, dy = at.getY() - cy;
			if (Math.max(Math.abs(dx), Math.abs(dy)) < Math.min(Math.abs(dx), Math.abs(dy)) + 3)
				return null;
			Point side = Math.abs(dx) > Math.abs(dy) ? new Point((int) Math.signum(dx), 0) : new Point(0, (int) Math.signum(dy));
			if (direction == null)
				direction = side;
			else if (!direction.equals(side))
				return null;
		}
		return direction;
	}

	Point findOpeningWorkRoom(WorldPoint here)
	{
		if (guidance.opening.isActive())
		{
			if (isFirstRoomFocus())
				return firstRoom;
			Point stop = findPondStop(here);
			return stop != null ? stop : guidance.opening.getCurrentRoom();
		}
		return guidance.opening.getRoomAt(here);
	}

	/**
	 * The room the player stands in, outside the loop's current room, as a stop for its fish on the way to the loop's
	 * home visit (OpeningBudget.isHomeVisitNext); null otherwise. Only its ponds are toured there, up to the opening
	 * fish allowance, so the stop never reaches other nodes or a room the player isn't in.
	 */
	Point findPondStop(WorldPoint here)
	{
		if (!guidance.opening.isActive() || guidance.opening.getStep() == 0 || isFirstRoomFocus())
			return null;
		Point at = guidance.opening.getRoomAt(here);
		// Inside the room only: a doorway threshold counts as the next room and would flip the stop on and off.
		if (at.equals(guidance.opening.getCurrentRoom()) || at.equals(GauntletMap.HOME)
				|| !guidance.opening.isRoomInterior(at, here.getX(), here.getY()))
			return null;
		Requirements plan = guidance.attempt.getNavigationPlan();
		return plan != null && OpeningBudget.isHomeVisitNext(plan, guidance.gauntletMap, guidance.opening) ? at : null;
	}

	Point getFirstEntryDestination(Point min, Point max)
	{
		return new Point(SceneGeometry.getCrossingCoordinate(min.getX(), max.getX(), firstRoom.getX()),
				SceneGeometry.getCrossingCoordinate(min.getY(), max.getY(), firstRoom.getY()));
	}

	void refreshFirstRoom(Player player)
	{
		if (firstRoom == null || firstRoomFinished)
			return;
		WorldPoint current = player.getWorldLocation();
		boolean inside = guidance.opening.isRoomInterior(firstRoom, current.getX(), current.getY());
		// Physical/latched entry is stronger evidence than a delayed door morph or discovery bit.
		if (inside || guidance.opening.hasEntered(firstRoom))
		{
			firstRoomOpened = true;
			firstRoomEntered = true;
		}
		if (!firstRoomOpened)
		{
			for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
			{
				if (entry.getKey().getWorldLocation().distanceTo2D(firstDoorLocation) <= 3 && guidance.isOpenPassage(entry.getKey(), entry.getValue()))
				{
					firstRoomOpened = true;
					break;
				}
			}
			int column = openingStartRoom % GauntletMap.SIZE + firstRoom.getX();
			int row = openingStartRoom / GauntletMap.SIZE - firstRoom.getY();
			if (hasFoundBit(column, row))
				firstRoomOpened = true;
		}
		if (!firstRoomOpened)
			return;
		Point room = guidance.opening.getRoomAt(current);
		if (!room.equals(firstRoom) && (!guidance.opening.isActive() || !firstRoom.equals(guidance.opening.getCurrentRoom()))
				&& (firstRoomEntered || !room.equals(GauntletMap.HOME)))
			firstRoomFinished = true;
	}

	void observePosition(Player player)
	{
		if (!guidance.opening.isActive())
			return;
		WorldPoint current = player.getWorldLocation();
		if (pendingPassageRoom != null)
		{
			if (!pendingPassageRoom.equals(guidance.opening.getNextRoom()))
			{
				pendingPassageRoom = null;
			}
			else if (guidance.opening.isRoomInterior(pendingPassageRoom, current.getX(), current.getY()))
			{
				guidance.opening.confirmPassageArrival(pendingPassageRoom);
				pendingPassageRoom = null;
				return;
			}
		}
		int column = guidance.client.getVarbitValue(VarbitID.GAUNTLET_CURRENT_ROOM_X);
		int row = guidance.client.getVarbitValue(VarbitID.GAUNTLET_CURRENT_ROOM_Z);
		Point physical = guidance.opening.getRoomAt(current);
		confirmOrientation(column, row, physical, current);
		Point mapped = column < 0 || column > 6 || row < 0 || row > 6 ? null : getWorldRoomOfMapSquare(column, row);
		// Map coordinates can lag movement. A discovered spawn value is not proof we're still there.
		if (mapped != null && mapped.equals(physical)
				&& guidance.opening.isRoomInterior(mapped, current.getX(), current.getY())
				&& guidance.client.getVarbitValue(VarbitID.GAUNTLET_ROOM_0_FOUND + row * GauntletMap.SIZE + column) == 1)
		{
			int[] w = MapCoordinates.mapToWorldSquare(mapOrientation, column, row);
			guidance.opening.observeMapRoom(openingStartRoom, w[0], w[1]);
		}
		guidance.opening.observe(current.getX(), current.getY());
	}

	/** First non-spawn room seen both physically and on the map settles which of the two map orientations is real. */
	private void confirmOrientation(int column, int row, Point physical, WorldPoint current)
	{
		if (mapOrientation.isConfirmed() || mapBoss == null || worldBoss == null || physical.equals(GauntletMap.HOME)
				|| column < 0 || column >= GauntletMap.SIZE || row < 0 || row >= GauntletMap.SIZE || column == mapStartRoom % GauntletMap.SIZE
				&& row == mapStartRoom / GauntletMap.SIZE
				|| !guidance.opening.isRoomInterior(physical, current.getX(), current.getY())
				|| guidance.client.getVarbitValue(VarbitID.GAUNTLET_ROOM_0_FOUND + row * GauntletMap.SIZE + column) != 1)
			return;
		Point mapRoom = new Point(column - mapStartRoom % GauntletMap.SIZE, mapStartRoom / GauntletMap.SIZE - row);
		MapCoordinates.Orientation confirmed = MapCoordinates.Orientation.confirm(mapBoss, worldBoss, mapRoom, physical);
		if (confirmed == null)
			return; // Lagging map coordinates; try again next tick.
		boolean changed = !confirmed.toWorld(new Point(1, 0)).equals(mapOrientation.toWorld(new Point(1, 0)))
				|| !confirmed.toWorld(new Point(0, 1)).equals(mapOrientation.toWorld(new Point(0, 1)));
		log.debug("Gauntlet map orientation: map room {} is world room {} -> {} (was {}{})", mapRoom, physical, confirmed,
				mapOrientation, changed ? "; rebuilding room map" : "");
		mapOrientation = confirmed;
		// Discovery flags recorded under the wrong guess would be misplaced; the loaded scene is re-observed every
		// tick.
		if (changed)
		{
			guidance.gauntletMap.reset();
			guidance.gauntletMap.initialize(openingStartRoom);
		}
	}

	void updateOpening(LocalPathfinder route, Player player)
	{
		awaitingOpeningOrientation = !guidance.opening.isInitialized();
		// Mapping remains active even when the opening and route optimizer are disabled.
		if (!guidance.opening.isInitialized())
		{
			TileObject bowl = guidance.objects.entrySet().stream().filter(e -> e.getValue() == ObjectKind.BOWL).map(Map.Entry::getKey).findFirst().orElse(null);
			int startRoom = guidance.client.getVarbitValue(VarbitID.GAUNTLET_START);
			Point mapDirection = MapCoordinates.getBossDirection(startRoom);
			if (mapDirection == null)
			{
				openingWaitReason = "Opening loop: waiting for game map data (start=" + startRoom + ")";
				return;
			}
			int column = guidance.client.getVarbitValue(VarbitID.GAUNTLET_CURRENT_ROOM_X);
			int row = guidance.client.getVarbitValue(VarbitID.GAUNTLET_CURRENT_ROOM_Z);
			if (bowl == null && (column != startRoom % GauntletMap.SIZE || row != startRoom / GauntletMap.SIZE))
			{
				openingWaitReason = "Opening loop: return to spawn to initialize";
				return;
			}
			WorldPoint home = bowl == null ? player.getWorldLocation() : bowl.getWorldLocation();
			List<Point> exits = new ArrayList<>();
			for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
			{
				if (!entry.getValue().isDoorway())
					continue;
				WorldPoint point = entry.getKey().getWorldLocation();
				if (point.getPlane() == home.getPlane() && point.distanceTo2D(home) <= 20)
					exits.add(new Point(point.getX(), point.getY()));
			}
			// The physical Hunllef wall decides the compass; the map widget's spawn marker is only a fallback.
			Point physicalDirection = findPhysicalBossDirection(home);
			// Without a readable lit wall, use the map with north/south swapped, which every observed run matched.
			Point assumed = physicalDirection != null ? physicalDirection : new Point(mapDirection.getX(), -mapDirection.getY());
			int worldStart = MapCoordinates.getStartRoomFor(assumed);
			guidance.opening.initializeFromMap(exits, new Point(home.getX(), home.getY()), worldStart);
			guidance.guidanceLog.logInitialization(player, bowl, home, startRoom, column, row);
			if (guidance.opening.isInitialized())
			{
				mapStartRoom = startRoom;
				mapBoss = mapDirection;
				worldBoss = assumed;
				mapOrientation = MapCoordinates.Orientation.guess(mapBoss, worldBoss);
				openingStartRoom = worldStart;
				guidance.gauntletMap.initialize(worldStart);
				log.debug("Gauntlet map orientation: map start={} (Hunllef {}) physical Hunllef side={} -> world start={} "
						+ "orientation {}",
						startRoom, mapDirection, physicalDirection == null
						? "not readable (using map with north/south swapped)" : physicalDirection, worldStart,
						mapOrientation);
			}
			else
				openingWaitReason = "Opening loop: locating spawn exits (map start=" + startRoom + ", nodes=" + exits.size() + ")";
			awaitingOpeningOrientation = !guidance.opening.isInitialized();
		}
		if (!guidance.opening.isActive())
			return;
		if (guidance.opening.getStep() == 0 && !firstRoomOpened)
		{
			List<LocalPathfinder.Goal> sides = new ArrayList<>();
			for (int side = 0; side < 2; side++)
			{
				guidance.opening.chooseSide(side == 1);
				for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
				{
					if (!entry.getValue().isDoorway())
						continue;
					TileObject object = entry.getKey();
					WorldPoint point = object.getWorldLocation();
					if (!guidance.opening.isNextDoor(point.getX(), point.getY()))
						continue;
					if (entry.getValue() == ObjectKind.DOOR && !guidance.canUnlockRoom(object) && !guidance.isOpenPassage(object, entry.getValue()))
						continue;
					sides.add(guidance.routing.buildUnlockGoal(object, side, GauntletMap.HOME, guidance.opening.getNextRoom()));
				}
			}
			LocalPathfinder.Choice choice = route.chooseNearest(sides);
			guidance.opening.chooseSide(choice != null && choice.index == 1);
		}
		observePosition(player);
	}
}
