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

import com.gauntlethelper.map.GauntletRoom;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Tile geometry for Routing and the other scene classes: object footprints, the tile to stand on beside a
 * station, room-boundary directions and doorway crossings. Pure functions of their arguments; no scene state.
 */
public final class SceneGeometry
{
	/** The last scene tile a route can stand on going north or east: the collision map blocks the outer five. */
	private static final int LAST_OPEN_SCENE_TILE = Constants.SCENE_SIZE - 6;

	private SceneGeometry()
	{
	}

	/** First regain the spawn axis, then proceed straight outward (or back home): the next room without a map route. */
	static Point getAxisStep(Point current, Point destination)
	{
		boolean adjustY = destination.getY() == 0 && current.getY() != 0
				|| !(destination.getX() == 0 && current.getX() != 0) && current.getX() == destination.getX();
		return current.equals(destination) ? current : adjustY
				? new Point(current.getX(), current.getY() + Integer.signum(destination.getY() - current.getY()))
				: new Point(current.getX() + Integer.signum(destination.getX() - current.getX()), current.getY());
	}

	/** The collision flags of the current plane, indexed [x][y] by scene tile; null when none are loaded. */
	static int[][] readCollisionFlags(WorldView view)
	{
		CollisionData[] maps = view.getCollisionMaps();
		CollisionData map = maps == null ? null : maps[view.getPlane()];
		return map == null ? null : map.getFlags();
	}

	/**
	 * One fixed tile beside the range, the same on every visit: of the side tiles not touching a wall, the
	 * one nearest the room center and the pump together; nearer the center on a tie. Blocked neighbors that are the
	 * range or the pump themselves are not walls. With no collision data, nearest the center and pump.
	 */
	public static Point findRangeTile(LocalPathfinder.Goal range, int cx, int cy, Point[] pump, int[][] flags)
	{
		List<Point> sides = new ArrayList<>();
		for (int x = range.minX; x <= range.maxX; x++)
		{
			sides.add(new Point(x, range.maxY + 1));
			sides.add(new Point(x, range.minY - 1));
		}
		for (int y = range.minY; y <= range.maxY; y++)
		{
			sides.add(new Point(range.maxX + 1, y));
			sides.add(new Point(range.minX - 1, y));
		}
		Point best = null, fallback = null;
		long bestScore = Long.MAX_VALUE, fallbackScore = Long.MAX_VALUE;
		for (Point side : sides)
		{
			long score = getSquaredDistance(side, cx, cy) * 1000L;
			if (pump != null)
				score += (long) getSquaredDistance(side, (pump[0].getX() + pump[1].getX()) / 2, (pump[0].getY() + pump[1].getY()) / 2) * 1000L;
			// Ties go nearer the center.
			score += getSquaredDistance(side, cx, cy);
			if (flags != null && isBlocked(flags, side.getX(), side.getY()))
				continue;
			if (score < fallbackScore)
			{
				fallbackScore = score;
				fallback = side;
			}
			if ((flags == null || !isTouchingWall(flags, side, range, pump)) && score < bestScore)
			{
				bestScore = score;
				best = side;
			}
		}
		return best != null ? best : fallback;
	}

	/** Whether the tile is outside the scene or fully blocks movement. */
	private static boolean isBlocked(int[][] flags, int x, int y)
	{
		return x < 0 || y < 0 || x >= flags.length || y >= flags[x].length || (flags[x][y] & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0;
	}

	/** Whether a tile has a wall edge on it or a blocked neighbor that is neither the range nor the pump. */
	private static boolean isTouchingWall(int[][] flags, Point p, LocalPathfinder.Goal range, Point[] pump)
	{
		// Wall edges on the tile itself (the low eight direction bits).
		if ((flags[p.getX()][p.getY()] & 0xFF) != 0)
			return true;
		int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
		for (int[] d : steps)
		{
			int x = p.getX() + d[0], y = p.getY() + d[1];
			boolean inFacility = x >= range.minX && x <= range.maxX && y >= range.minY && y <= range.maxY
					|| pump != null && x >= pump[0].getX() && x <= pump[1].getX() && y >= pump[0].getY()
					&& y <= pump[1].getY();
			if (!inFacility && isBlocked(flags, x, y))
				return true;
		}
		return false;
	}

	/**
	 * Of the footprint's sides facing (cx, cy), the tile nearest the door (doorX, doorY), nearer the center on a tie,
	 * so the home bowl is used from the center-facing side on the side the opening loop comes home from. The
	 * home bowl sits north-west of the center, so this is its east or south tile.
	 */
	static Point findFinishSideTile(int minX, int minY, int maxX, int maxY, int cx, int cy, int doorX, int doorY)
	{
		int dx = 2 * cx - (minX + maxX), dy = 2 * cy - (minY + maxY);
		if (dx == 0 || dy == 0)
			return findFacingTile(minX, minY, maxX, maxY, cx, cy);
		Point across = new Point(dx > 0 ? maxX + 1 : minX - 1, Math.max(minY, Math.min(maxY, cy)));
		Point along = new Point(Math.max(minX, Math.min(maxX, cx)), dy > 0 ? maxY + 1 : minY - 1);
		int a = getChebyshevDistance(across, doorX, doorY), b = getChebyshevDistance(along, doorX, doorY);
		if (a != b)
			return a < b ? across : along;
		return getSquaredDistance(across, cx, cy) <= getSquaredDistance(along, cx, cy) ? across : along;
	}

	/** Larger of the two axis distances from the tile to (x, y). */
	private static int getChebyshevDistance(Point p, int x, int y)
	{
		return Math.max(Math.abs(p.getX() - x), Math.abs(p.getY() - y));
	}

	/** Squared straight-line distance from the tile to (x, y). */
	private static int getSquaredDistance(Point p, int x, int y)
	{
		return (p.getX() - x) * (p.getX() - x) + (p.getY() - y) * (p.getY() - y);
	}

	/** The tile beside a footprint on its side facing (cx, cy), level with that point where the side allows. */
	static Point findFacingTile(int minX, int minY, int maxX, int maxY, int cx, int cy)
	{
		// Doubled coordinates keep the footprint's middle exact for even sizes.
		int dx = 2 * cx - (minX + maxX), dy = 2 * cy - (minY + maxY);
		if (Math.abs(dx) >= Math.abs(dy))
			return new Point(dx >= 0 ? maxX + 1 : minX - 1, Math.max(minY, Math.min(maxY, cy)));
		return new Point(Math.max(minX, Math.min(maxX, cx)), dy >= 0 ? maxY + 1 : minY - 1);
	}

	/** South-west scene tile of an object: a GameObject's full extent, otherwise its single tile. */
	public static Point getSceneMin(TileObject object)
	{
		return object instanceof GameObject ? ((GameObject) object).getSceneMinLocation()
				: new Point(object.getLocalLocation().getSceneX(), object.getLocalLocation().getSceneY());
	}

	/** North-east scene tile of an object (the same tile as getSceneMin for single-tile objects). */
	public static Point getSceneMax(TileObject object)
	{
		return object instanceof GameObject ? ((GameObject) object).getSceneMaxLocation() : getSceneMin(object);
	}

	/** A door or passage object belongs to the wall at this room boundary point when it is within three tiles of it. */
	static boolean isNearBoundary(WorldPoint at, Point boundary)
	{
		return Math.abs(at.getX() - boundary.getX()) <= 3 && Math.abs(at.getY() - boundary.getY()) <= 3;
	}

	/** The wall of room `from` that faces the adjacent room `to`. */
	static GauntletRoom.Direction getDirection(Point from, Point to)
	{
		if (to.getX() > from.getX())
			return GauntletRoom.Direction.EAST;
		if (to.getX() < from.getX())
			return GauntletRoom.Direction.WEST;
		if (to.getY() > from.getY())
			return GauntletRoom.Direction.NORTH;
		return GauntletRoom.Direction.SOUTH;
	}

	/**
	 * One axis of the floor tile two past a doorway footprint (min..max) in the direction of travel: the far side
	 * when moving along that axis, otherwise the doorway's middle. Going north or east it stops at the last open tile of
	 * the scene: the door is still open past there, and walking toward it reloads the scene.
	 */
	static int getCrossingCoordinate(int min, int max, int direction)
	{
		if (direction > 0)
			return Math.min(max + 2, LAST_OPEN_SCENE_TILE);
		if (direction < 0)
			return min - 2;
		return (min + max) / 2;
	}

	/** Walk through an open doorway object to the tile two past its footprint, toward (dx, dy). */
	static LocalPathfinder.Goal buildCrossingGoal(int index, TileObject doorway, int dx, int dy)
	{
		return buildCrossingGoal(index, getSceneMin(doorway), getSceneMax(doorway), dx, dy);
	}

	/** Walk through an open doorway to the tile two past its footprint, toward (dx, dy). */
	static LocalPathfinder.Goal buildCrossingGoal(int index, Point min, Point max, int dx, int dy)
	{
		return LocalPathfinder.walkTo(index, getCrossingCoordinate(min.getX(), max.getX(), dx), getCrossingCoordinate(min.getY(), max.getY(), dy));
	}
}
