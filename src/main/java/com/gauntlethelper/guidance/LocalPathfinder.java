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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import net.runelite.api.Point;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_EAST;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_FULL;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST;
import static net.runelite.api.CollisionDataFlag.BLOCK_MOVEMENT_WEST;

/**
 * Bounded eight-direction BFS with the game's movement rules: a diagonal step costs one tile like a straight one and is
 * allowed only when both straight two-step routes around its corner are open. Walls are never treated as
 * walkable doors.
 */
public final class LocalPathfinder
{
	private final int[][] flags;
	private final boolean[][] loaded;
	/** What routing reads, kept as a copy: each loaded tile's collision flags, -1 for a tile not loaded. */
	private final int[][] grid;
	private final int[][] parent;
	private final int[][] distance;
	private final int width;
	private final int height;
	private final int startX;
	private final int startY;
	private final Map<Integer, LocalPathfinder> fromRoutes = new HashMap<>();

	public LocalPathfinder(int[][] flags, boolean[][] loaded, int startX, int startY)
	{
		this(flags, loaded, copyGrid(flags, loaded), startX, startY);
	}

	/** The same walkable grid from another start: the grid copy is shared, not copied again. */
	private LocalPathfinder(int[][] flags, boolean[][] loaded, int[][] grid, int startX, int startY)
	{
		this.flags = flags;
		this.loaded = loaded;
		this.grid = grid;
		width = flags.length;
		height = width == 0 ? 0 : flags[0].length;
		this.startX = startX;
		this.startY = startY;
		parent = new int[width][height];
		distance = new int[width][height];
		for (int x = 0; x < width; x++)
		{
			Arrays.fill(distance[x], -1);
		}
		if (!isInside(startX, startY))
		{
			return;
		}
		// Straight steps first, so a straight route wins ties against an equally short diagonal one.
		int[] dx = {1, -1, 0, 0, 1, -1, 1, -1};
		int[] dy = {0, 0, 1, -1, 1, 1, -1, -1};
		// Tiles are queued as x * height + y; each tile enters the queue at most once.
		int[] queue = new int[width * height];
		int head = 0;
		int tail = 0;
		queue[tail++] = startX * height + startY;
		distance[startX][startY] = 0;
		while (head < tail)
		{
			int px = queue[head] / height;
			int py = queue[head] % height;
			head++;
			for (int i = 0; i < 8; i++)
			{
				int x = px + dx[i];
				int y = py + dy[i];
				if (!isInside(x, y) || distance[x][y] >= 0)
				{
					continue;
				}
				boolean canStep = dx[i] == 0 || dy[i] == 0 ? canStep(px, py, x, y)
						: canStep(px, py, x, py) && canStep(x, py, x, y) && canStep(px, py, px, y) && canStep(px, y, x, y)
						&& (flags[px][py] & getCorner(dx[i], dy[i])) == 0 && (flags[x][y] & getCorner(-dx[i], -dy[i])) == 0;
				if (!canStep)
				{
					continue;
				}
				distance[x][y] = distance[px][py] + 1;
				parent[x][y] = px * height + py;
				queue[tail++] = x * height + y;
			}
		}
	}

	/** The wall-corner flag on the (dx, dy) corner of a tile. */
	private static int getCorner(int dx, int dy)
	{
		return dx > 0 ? (dy > 0 ? BLOCK_MOVEMENT_NORTH_EAST : BLOCK_MOVEMENT_SOUTH_EAST) : dy > 0 ? BLOCK_MOVEMENT_NORTH_WEST : BLOCK_MOVEMENT_SOUTH_WEST;
	}

	/** One straight step between neighboring tiles: the target loaded and not solid, no wall between them. */
	private boolean canStep(int fromX, int fromY, int toX, int toY)
	{
		if (!isInside(toX, toY) || !loaded[toX][toY])
			return false;
		int out = toX > fromX ? BLOCK_MOVEMENT_EAST : toX < fromX ? BLOCK_MOVEMENT_WEST : toY > fromY ? BLOCK_MOVEMENT_NORTH : BLOCK_MOVEMENT_SOUTH;
		int in = toX > fromX ? BLOCK_MOVEMENT_WEST : toX < fromX ? BLOCK_MOVEMENT_EAST : toY > fromY ? BLOCK_MOVEMENT_SOUTH : BLOCK_MOVEMENT_NORTH;
		return (flags[fromX][fromY] & out) == 0 && (flags[toX][toY] & (in | BLOCK_MOVEMENT_FULL)) == 0;
	}

	private static int[][] copyGrid(int[][] flags, boolean[][] loaded)
	{
		int[][] grid = new int[flags.length][];
		for (int x = 0; x < flags.length; x++)
		{
			grid[x] = new int[flags[x].length];
			for (int y = 0; y < flags[x].length; y++)
			{
				grid[x][y] = loaded[x][y] ? flags[x][y] : -1;
			}
		}
		return grid;
	}

	private boolean isInside(int x, int y) { return x >= 0 && y >= 0 && x < width && y < height; }
	public int getDistance(int x, int y) { return isInside(x, y) ? distance[x][y] : -1; }

	LocalPathfinder restrict(BiPredicate<Integer, Integer> allowed)
	{
		boolean[][] mask = new boolean[width][height];
		int sx = -1, sy = -1;
		for (int x = 0; x < width; x++)
			for (int y = 0; y < height; y++)
			{
				mask[x][y] = loaded[x][y] && allowed.test(x, y);
				if (distance[x][y] == 0)
				{
					sx = x;
					sy = y;
				}
			}
		return new LocalPathfinder(flags, mask, sx, sy);
	}

	/** The walkable grid this route searched. Never changed after construction; callers must not change it either. */
	int[][] getGrid() { return grid; }
	/** Whether this route searched the same walkable grid as one kept from an earlier route. */
	boolean hasSameGrid(int[][] other) { return Arrays.deepEquals(grid, other); }
	/**
	 * The same grid searched from another tile. Routes never change after construction, so each start tile is
	 * searched once for the life of this route (normally one guidance update).
	 */
	LocalPathfinder routeFrom(Point point)
	{
		if (!isInside(point.getX(), point.getY()))
			return new LocalPathfinder(flags, loaded, grid, point.getX(), point.getY());
		return fromRoutes.computeIfAbsent(point.getX() * height + point.getY(), key -> new LocalPathfinder(flags, loaded, grid, point.getX(), point.getY()));
	}

	/** Reachable interaction edges, or the precise passage waypoint. */
	List<Point> findContacts(Goal goal)
	{
		List<Point> points = new ArrayList<>();
		for (int x = goal.minX - 1; x <= goal.maxX + 1; x++)
			for (int y = goal.minY - 1; y <= goal.maxY + 1; y++)
				if (isContact(goal, x, y) && getDistance(x, y) >= 0)
					points.add(new Point(x, y));
		return points;
	}

	/** A goal with a reachable facing tile is used only from that tile; otherwise from any side. */
	private boolean isContact(Goal goal, int x, int y)
	{
		if (goal.facing != null && getDistance(goal.facing.getX(), goal.facing.getY()) >= 0)
			return x == goal.facing.getX() && y == goal.facing.getY();
		return isSideContact(goal, x, y);
	}

	static boolean isSideContact(Goal goal, int x, int y)
	{
		return goal.walkTo ? x == goal.minX && y == goal.minY :
				(x >= goal.minX && x <= goal.maxX && (y == goal.minY - 1 || y == goal.maxY + 1))
				|| (y >= goal.minY && y <= goal.maxY && (x == goal.minX - 1 || x == goal.maxX + 1));
	}

	public static final class Goal
	{
		final int index;
		final int minX;
		final int minY;
		final int maxX;
		final int maxY;
		final boolean exploration;
		final boolean walkTo;
		Point continuation;

		/** Preferred interaction tile (the bowl's side facing the room center); any side when it is unreachable. */
		Point facing;

		/**
		 * The room behind this door may hold a demi: light it from the outer (front) tile, never inside the doorway.
		 */
		boolean exposedEntry;

		/** Extra tiles charged for switching away from the door lit last update (see Routing.DOOR_SWITCH_COST). */
		int switchCost;

		int getContinuationCost(int x, int y)
		{
			return switchCost + getCrossing(x, y);
		}

		private int getCrossing(int x, int y)
		{
			return continuation == null ? 0 : Math.abs(x - continuation.getX()) + Math.abs(y - continuation.getY());
		}

		/**
		 * Distance straight through the wall toward the room being entered (ignores sideways offset along the wall).
		 */
		int getDepthToContinuation(int x, int y)
		{
			if (continuation == null)
				return 0;
			if (continuation.getY() > maxY || continuation.getY() < minY)
				return Math.abs(y - continuation.getY());
			if (continuation.getX() > maxX || continuation.getX() < minX)
				return Math.abs(x - continuation.getX());
			return getCrossing(x, y);
		}

		public Goal(int index, int minX, int minY, int maxX, int maxY, boolean exploration)
		{
			this(index, minX, minY, maxX, maxY, exploration, false);
		}

		private Goal(int index, int minX, int minY, int maxX, int maxY, boolean exploration, boolean walkTo)
		{
			this.index = index;
			this.minX = minX;
			this.minY = minY;
			this.maxX = maxX;
			this.maxY = maxY;
			this.exploration = exploration;
			this.walkTo = walkTo;
		}
	}

	/** Legal adjacent Light contact, with the intended crossing used to choose the interaction side. */
	public static Goal unlockTo(int index, int minX, int minY, int maxX, int maxY, int enterX, int enterY)
	{
		Goal goal = new Goal(index, minX, minY, maxX, maxY, true);
		goal.continuation = new Point(enterX, enterY);
		return goal;
	}

	/** As unlockTo, for a door into a room that may hold a demi (front tile only). */
	public static Goal unlockToExposed(int index, int minX, int minY, int maxX, int maxY, int enterX, int enterY)
	{
		Goal goal = unlockTo(index, minX, minY, maxX, maxY, enterX, enterY);
		goal.exposedEntry = true;
		return goal;
	}

	/** A walkable floor tile must be reached, rather than interacted with from next to it. */
	public static Goal walkTo(int index, int x, int y)
	{
		return new Goal(index, x, y, x, y, true, true);
	}

	public static final class Choice
	{
		public final int index;
		public final List<Point> path;

		Choice(int index, List<Point> path)
		{
			this.index = index;
			this.path = path;
		}
	}

	static final int DOOR_DEPTH_ALLOWANCE = 2;

	/** Compare reachable work and unlock nodes by walking distance. Prefer work on ties. */
	public Choice chooseNearest(List<Goal> goals)
	{
		Goal best = null;
		int bestDistance = Integer.MAX_VALUE;
		int endX = 0;
		int endY = 0;
		for (Goal goal : goals)
		{
			for (int x = goal.minX - 1; x <= goal.maxX + 1; x++)
			{
				for (int y = goal.minY - 1; y <= goal.maxY + 1; y++)
				{
					int cost = getDistance(x, y);
					if (!isContact(goal, x, y) || cost < 0)
					{
						continue;
					}
					cost += goal.getContinuationCost(x, y);
					boolean preferWork = best != null && best.exploration && !goal.exploration;
					boolean samePriority = best != null && best.exploration == goal.exploration;
					boolean earlier = best != null && (goal.minX < best.minX || (goal.minX == best.minX && goal.minY < best.minY));
					// Equally short tiles of one node: the one nearest in a straight line, which is the side that
					// looks nearest, since a diagonal step costs one tile. Doors keep their own side rules below.
					boolean straighter = goal == best && goal.continuation == null && cost == bestDistance
							&& getStraightLine(x, y) < getStraightLine(endX, endY);
					if (best == null || cost < bestDistance || straighter || (cost == bestDistance && (preferWork || (samePriority && earlier))))
					{
						best = goal;
						bestDistance = cost;
						endX = x;
						endY = y;
					}
				}
			}
		}
		if (best != null && best.continuation != null && best.exposedEntry)
		{
			// Possible demi behind the door: among equally cheap legal tiles, keep the one furthest from that room.
			int depth = best.getDepthToContinuation(endX, endY);
			for (Goal goal : goals)
			{
				if (!goal.exposedEntry || goal.continuation == null)
				{
					continue;
				}
				for (Point contact : findContacts(goal))
				{
					int total = getDistance(contact.getX(), contact.getY()) + goal.getContinuationCost(contact.getX(), contact.getY());
					int contactDepth = goal.getDepthToContinuation(contact.getX(), contact.getY());
					if (total == bestDistance && (contactDepth > depth || (contactDepth == depth && isNearerDoor(goal, best))))
					{
						best = goal;
						depth = contactDepth;
						endX = contact.getX();
						endY = contact.getY();
					}
				}
			}
		}
		else if (best != null && best.continuation != null)
		{
			// Light a door from the legal tile nearest the room being entered (deepest into the doorway), when that
			// costs at most DOOR_DEPTH_ALLOWANCE extra walking in total.
			int bestContinuation = best.getDepthToContinuation(endX, endY);
			int limit = bestDistance + DOOR_DEPTH_ALLOWANCE;
			int chosenTotal = bestDistance;
			for (Goal goal : goals)
			{
				if (goal.continuation == null || goal.exposedEntry)
				{
					continue;
				}
				for (Point contact : findContacts(goal))
				{
					int continuation = goal.getDepthToContinuation(contact.getX(), contact.getY());
					int total = getDistance(contact.getX(), contact.getY()) + goal.getContinuationCost(contact.getX(), contact.getY());
					if (total > limit)
					{
						continue;
					}
					if (continuation < bestContinuation || (continuation == bestContinuation && (total < chosenTotal
							|| (total == chosenTotal && isNearerDoor(goal, best)))))
					{
						best = goal;
						bestContinuation = continuation;
						chosenTotal = total;
						endX = contact.getX();
						endY = contact.getY();
					}
				}
			}
		}
		return best == null ? null : new Choice(best.index, findPath(endX, endY));
	}

	/**
	 * A doorway's two doors often tie on cost from its inset tiles; the tie goes to the door nearer the player in a
	 * straight line, not the one with the lower coordinates.
	 */
	private boolean isNearerDoor(Goal goal, Goal best)
	{
		return goal != best && getDoorLine(goal) < getDoorLine(best);
	}

	/** Squared straight-line distance from the start tile to the footprint's center, in half tiles. */
	private int getDoorLine(Goal goal)
	{
		int dx = goal.minX + goal.maxX - 2 * startX;
		int dy = goal.minY + goal.maxY - 2 * startY;
		return dx * dx + dy * dy;
	}

	/** Squared straight-line distance from the start tile. */
	int getStraightLine(int x, int y)
	{
		return (x - startX) * (x - startX) + (y - startY) * (y - startY);
	}

	/** Path to the nearest reachable tile inside the box (lowest x, then y, on ties); empty when none is reachable. */
	public List<Point> findPathIntoArea(int minX, int minY, int maxX, int maxY)
	{
		int bestX = -1, bestY = -1, best = Integer.MAX_VALUE;
		for (int x = minX; x <= maxX; x++)
			for (int y = minY; y <= maxY; y++)
			{
				int tiles = getDistance(x, y);
				if (tiles >= 0 && tiles < best)
				{
					best = tiles;
					bestX = x;
					bestY = y;
				}
			}
		return best == Integer.MAX_VALUE ? Collections.emptyList() : findPath(bestX, bestY);
	}

	public List<Point> findPath(int x, int y)
	{
		if (getDistance(x, y) < 0)
		{
			return Collections.emptyList();
		}
		List<Point> result = new ArrayList<>();
		while (true)
		{
			result.add(new Point(x, y));
			if (distance[x][y] == 0)
			{
				break;
			}
			int encoded = parent[x][y];
			x = encoded / height;
			y = encoded % height;
		}
		Collections.reverse(result);
		return Collections.unmodifiableList(result);
	}
}
