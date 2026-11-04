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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.runelite.api.Point;

/** Walking tour through observed needed nodes, including interaction sides and the planned exit. */
public final class RoomRoutePlanner
{
	private static final int INF = Integer.MAX_VALUE / 4, EXACT_LIMIT = 10, MAX_EXACT_STATES = 250_000;
	/** Larger than any room tour's tile count, so tiles only break ties that FIRST_STOP_WEIGHT leaves. */
	private static final int FIRST_STOP_WEIGHT = 1024;
	/** Larger than any first leg times FIRST_STOP_WEIGHT plus the tour's tiles, so ticks always decide first. */
	private static final int TICK_WEIGHT = 1024 * FIRST_STOP_WEIGHT;

	public static final class Result
	{
		public final List<Integer> order;
		public final int exitIndex, tiles;
		public final boolean exact;
		public final List<Point> path;

		Result(List<Integer> order, int exitIndex, int tiles, boolean exact, List<Point> path)
		{
			this.order = List.copyOf(order);
			this.exitIndex = exitIndex;
			this.tiles = tiles;
			this.exact = exact;
			this.path = path;
		}
	}

	private static final class Contact
	{
		final int node;
		final Point tile;

		Contact(int node, Point tile)
		{
			this.node = node;
			this.tile = tile;
		}
	}

	private static final class Tour
	{
		final List<Integer> contacts;
		final int exit, tail;
		final boolean exact;

		Tour(List<Integer> contacts, int exit, int tail, boolean exact)
		{
			this.contacts = List.copyOf(contacts);
			this.exit = exit;
			this.tail = tail;
			this.exact = exact;
		}
	}

	/** A first stop, the tiles it was from the player when first chosen and the fewest it has been since. */
	private static final class HeldStop
	{
		final String node;
		final int chosenReach, nearestReach;

		HeldStop(String node, int chosenReach, int nearestReach)
		{
			this.node = node;
			this.chosenReach = chosenReach;
			this.nearestReach = nearestReach;
		}
	}

	private String cachedKey;
	/** The walkable grid of the last build; a plan is rebuilt when the grid changes (a door opens, tiles load). */
	private int[][] cachedGrid;
	private Tour cached;
	private List<Contact> cachedContacts;
	private int[][] cachedDistance;
	private int[] cachedFinish, cachedExitIndex;
	/**
	 * The tile the last plan chose for its first and second stops, per node (by geometry), which a near tie keeps (see
	 * orientNearestFirstSide). The second stop's is the side the Next path ends on, so it holds when Next becomes the
	 * task. Per node, because one scene plans more than one room each tick, and kept through reset, which the scene
	 * calls whenever an object changes (sometimes every tick).
	 */
	private final Map<String, Point> shownSides = new HashMap<>();
	/**
	 * The first stop the player is walking to, per set of nodes and quotas, which a new plan keeps (see keepHeldFirstStop).
	 * Kept through reset, like shownSides.
	 */
	private final Map<String, HeldStop> heldStops = new HashMap<>();
	private List<LocalPathfinder.Goal> cachedNodes;
	private int builds;

	public void reset()
	{
		cachedKey = null;
		cachedGrid = null;
		cached = null;
		cachedContacts = null;
		cachedDistance = null;
		cachedFinish = null;
		cachedExitIndex = null;
		cachedNodes = null;
	}

	int getBuildCount() { return builds; }
	private static final Comparator<LocalPathfinder.Goal> GEOMETRY = Comparator.comparingInt((LocalPathfinder.Goal g) -> g.minX)
			.thenComparingInt(g -> g.minY)
			.thenComparingInt(g -> g.maxX)
			.thenComparingInt(g -> g.maxY)
			.thenComparing(g -> g.walkTo);

	public Result plan(LocalPathfinder route, List<LocalPathfinder.Goal> resourceGoals, List<LocalPathfinder.Goal> exitGoals)
	{
		return plan(route, resourceGoals, exitGoals, Collections.emptyMap());
	}

	public Result plan(LocalPathfinder route, List<LocalPathfinder.Goal> resourceGoals, List<LocalPathfinder.Goal> exitGoals, Map<Integer, Integer> tailCosts)
	{
		return plan(route, resourceGoals, exitGoals, tailCosts, Collections.emptyMap(), Collections.emptyMap());
	}

	/**
	 * Grouped goals (goal index -> group) are interchangeable nodes of one resource: the tour visits only as many of
	 * each group as its quota (group -> node count) requires, choosing the cheapest subset for the whole room trip.
	 * Ungrouped goals, and groups whose quota covers every reachable node, remain mandatory.
	 */
	public Result plan(LocalPathfinder route, List<LocalPathfinder.Goal> resourceGoals, List<LocalPathfinder.Goal> exitGoals,
			Map<Integer, Integer> tailCosts,
			Map<Integer, Integer> groupOf, Map<Integer, Integer> quota)
	{
		List<LocalPathfinder.Goal> nodes = new ArrayList<>(), exits = new ArrayList<>();
		for (LocalPathfinder.Goal goal : resourceGoals)
			if (!route.findContacts(goal).isEmpty())
				nodes.add(goal);
		for (LocalPathfinder.Goal goal : exitGoals)
			if (!route.findContacts(goal).isEmpty())
				exits.add(goal);
		nodes.sort(GEOMETRY);
		exits.sort(GEOMETRY);
		if (nodes.isEmpty())
			return null;
		int[] group = new int[nodes.size()];
		StringBuilder groups = new StringBuilder();
		for (int i = 0; i < nodes.size(); i++)
		{
			Integer groupId = groupOf.get(nodes.get(i).index);
			group[i] = groupId == null || !quota.containsKey(groupId) ? -1 : groupId;
			groups.append(group[i]).append(',');
		}
		Map<Integer, Integer> required = new TreeMap<>();
		for (int groupId : group)
			if (groupId >= 0)
				required.put(groupId, Math.max(1, quota.get(groupId)));
		String stopsKey = buildSignature(nodes) + "|" + groups + "|" + required;
		String key = stopsKey + "|" + buildSignature(exits) + "|" + new TreeMap<>(tailCosts);
		if (!key.equals(cachedKey) || !route.hasSameGrid(cachedGrid) || cached == null
				|| route.findPath(cachedContacts.get(cached.contacts.get(0)).tile.getX(),
				cachedContacts.get(cached.contacts.get(0)).tile.getY()).isEmpty())
		{
			builds++;
			cachedKey = key;
			cachedGrid = route.getGrid();
			List<Contact> contacts = new ArrayList<>();
			for (int i = 0; i < nodes.size(); i++)
				for (Point point : route.findContacts(nodes.get(i)))
					contacts.add(new Contact(i, point));
			int size = contacts.size();
			int[][] distance = new int[size][size];
			int[] start = new int[size];
			int[] finish = new int[size];
			int[] exitIndex = new int[size];
			Map<Point, LocalPathfinder> fields = new HashMap<>();
			for (int i = 0; i < size; i++)
			{
				Contact from = contacts.get(i);
				LocalPathfinder field = fields.computeIfAbsent(from.tile, route::routeFrom);
				start[i] = route.getDistance(from.tile.getX(), from.tile.getY());
				for (int j = 0; j < size; j++)
				{
					Point to = contacts.get(j).tile;
					int tiles = field.getDistance(to.getX(), to.getY());
					distance[i][j] = tiles < 0 ? INF : tiles;
				}
				finish[i] = exits.isEmpty() ? 0 : INF;
				exitIndex[i] = -1;
				for (int e = 0; e < exits.size(); e++)
				{
					LocalPathfinder.Goal goal = exits.get(e);
					LocalPathfinder.Choice exit = field.chooseNearest(Collections.singletonList(goal));
					if (exit == null)
						continue;
					Point last = exit.path.get(exit.path.size() - 1);
					int cost = exit.path.size() - 1 + goal.getContinuationCost(last.getX(), last.getY()) + tailCosts.getOrDefault(goal.index, 0);
					if (cost < finish[i])
					{
						finish[i] = cost;
						exitIndex[i] = e;
					}
				}
			}
			// Each leg ends with a stop at a node, so it costs whole running ticks: minimize ticks, then tiles.
			int[][] legDistance = new int[size][size];
			int[] legStart = new int[size], legFinish = new int[size];
			for (int i = 0; i < size; i++)
			{
				for (int j = 0; j < size; j++)
					legDistance[i][j] = getLegCost(distance[i][j]);
				legStart[i] = getStartCost(start[i]);
				legFinish[i] = getLegCost(finish[i]);
			}
			Tour tour;
			if (nodes.size() <= EXACT_LIMIT && (1L << nodes.size()) * size <= MAX_EXACT_STATES)
			{
				tour = planExact(contacts, nodes.size(), legDistance, legStart, legFinish, exitIndex, group, required);
				HeldStop held = heldStops.get(stopsKey);
				tour = keepHeldFirstStop(tour, held, nodes, contacts, start, legDistance, legStart, legFinish, exitIndex, group, required);
			}
			else
			{
				boolean[] keep = findNearestSubset(contacts, nodes.size(), start, group, required);
				tour = planByInsertion(contacts, legDistance, legStart, legFinish, exitIndex, keep);
			}
			cached = tour == null ? null : new Tour(tour.contacts, tour.exit, countTiles(tour.contacts, distance, finish), tour.exact);
			cachedContacts = contacts;
			cachedDistance = distance;
			cachedFinish = finish;
			cachedExitIndex = exitIndex;
			cachedNodes = nodes;
		}
		if (cached == null)
			return null;
		Tour tour = orientNearestFirstSide(route, cached);
		Contact first = cachedContacts.get(tour.contacts.get(0));
		if (shownSides.size() > 64)
			shownSides.clear();
		shownSides.put(getNodeKey(cachedNodes.get(first.node)), first.tile);
		if (tour.contacts.size() > 1)
		{
			Contact second = cachedContacts.get(tour.contacts.get(1));
			shownSides.put(getNodeKey(cachedNodes.get(second.node)), second.tile);
		}
		List<Point> path = route.findPath(first.tile.getX(), first.tile.getY());
		rememberFirstStop(stopsKey, getNodeKey(cachedNodes.get(first.node)), path.size() - 1);
		List<Integer> order = new ArrayList<>();
		for (int contact : tour.contacts)
			order.add(nodes.get(cachedContacts.get(contact).node).index);
		return new Result(order, tour.exit < 0 ? -1 : exits.get(tour.exit).index, path.size() - 1 + tour.tail, tour.exact, path);
	}

	/**
	 * A new plan for the same nodes and quotas changed only where the tour ends (an enemy moved, a door lit), so it doesn't
	 * move the first stop the player is walking to: the tour is replanned from that node. A node no nearer than when it was
	 * chosen, or more than a tile further than it has been, means the player isn't going there, and the new plan stands.
	 */
	private Tour keepHeldFirstStop(Tour tour, HeldStop held, List<LocalPathfinder.Goal> nodes, List<Contact> contacts, int[] start,
			int[][] legDistance, int[] legStart, int[] legFinish, int[] exitIndex, int[] group, Map<Integer, Integer> required)
	{
		if (tour == null || held == null)
			return tour;
		int[] heldStart = new int[legStart.length];
		Arrays.fill(heldStart, INF);
		int reach = INF;
		for (int c = 0; c < contacts.size(); c++)
			if (getNodeKey(nodes.get(contacts.get(c).node)).equals(held.node) && start[c] >= 0)
			{
				heldStart[c] = legStart[c];
				reach = Math.min(reach, start[c]);
			}
		if (reach >= held.chosenReach || reach > held.nearestReach + 1 || getNodeKey(nodes.get(contacts.get(tour.contacts.get(0)).node)).equals(held.node))
			return tour;
		Tour kept = planExact(contacts, nodes.size(), legDistance, heldStart, legFinish, exitIndex, group, required);
		return kept == null ? tour : kept;
	}

	private void rememberFirstStop(String stopsKey, String node, int reach)
	{
		if (heldStops.size() > 64)
			heldStops.clear();
		HeldStop held = heldStops.get(stopsKey);
		if (held != null && held.node.equals(node))
			heldStops.put(stopsKey, new HeldStop(node, held.chosenReach, Math.min(held.nearestReach, reach)));
		else
			heldStops.put(stopsKey, new HeldStop(node, reach, reach));
	}

	/**
	 * The side the last plan chose for its second stop, or null when that plan had a single stop (or none). The Next
	 * preview ends there, so its path doesn't move once that stop becomes the first one.
	 */
	Point getPlannedSecondStop()
	{
		return cached == null || cached.contacts.size() < 2 ? null : cachedContacts.get(cached.contacts.get(1)).tile;
	}

	/**
	 * The cached tour with its first stop re-picked from the current start among the same node's sides. Walking up to a node can
	 * leave the player at another side of it, so the path never asks for a step to the far side. Ties keep the
	 * planned side; later stops are unchanged.
	 */
	private Tour orientNearestFirstSide(LocalPathfinder route, Tour tour)
	{
		List<Integer> order = tour.contacts;
		int first = order.get(0), node = cachedContacts.get(first).node, best = first;
		long bestCost = computeFirstLegCost(route, order, first);
		for (int c = 0; c < cachedContacts.size(); c++)
		{
			if (c == first || cachedContacts.get(c).node != node)
				continue;
			long cost = computeFirstLegCost(route, order, c);
			if (cost < bestCost)
			{
				best = c;
				bestCost = cost;
			}
		}
		// Sides reached in the same number of tiles: the one nearest in a straight line, the side that looks nearest and
		// that clicking the node walks to, even when another is a tile nearer the next stop.
		int reach = getReach(route, best);
		Point bestTile = cachedContacts.get(best).tile;
		for (int c = 0; c < cachedContacts.size(); c++)
		{
			Point tile = cachedContacts.get(c).tile;
			if (cachedContacts.get(c).node == node && getReach(route, c) == reach
					&& route.getStraightLine(tile.getX(), tile.getY()) < route.getStraightLine(bestTile.getX(), bestTile.getY()))
			{
				best = c;
				bestTile = tile;
			}
		}
		// The side already shown, by this path or the Next path, holds while it is within a tile of the better one, to
		// reach and for the walk on. Where the player stands always wins.
		int held = getShownSide(node);
		if (held >= 0 && held != best && getReach(route, best) > 0)
		{
			long heldTiles = countWalkTiles(route, order, held);
			if (heldTiles != Long.MAX_VALUE && countWalkTiles(route, order, best) >= heldTiles - 1 && getReach(route, best) >= getReach(route, held) - 1)
				best = held;
		}
		if (best == first)
			return tour;
		List<Integer> changed = new ArrayList<>(order);
		changed.set(0, best);
		int exit = changed.size() == 1 ? cachedExitIndex[best] : tour.exit;
		return new Tour(changed, exit, countTiles(changed, cachedDistance, cachedFinish), tour.exact);
	}

	/** The cached contact of node at the tile the last plan chose for it, or -1. */
	private int getShownSide(int node)
	{
		Point shown = shownSides.get(getNodeKey(cachedNodes.get(node)));
		for (int c = 0; shown != null && c < cachedContacts.size(); c++)
			if (cachedContacts.get(c).node == node && cachedContacts.get(c).tile.equals(shown))
				return c;
		return -1;
	}

	private static String getNodeKey(LocalPathfinder.Goal goal)
	{
		return buildSignature(Collections.singletonList(goal));
	}

	private int getReach(LocalPathfinder route, int contact)
	{
		Point tile = cachedContacts.get(contact).tile;
		return route.getDistance(tile.getX(), tile.getY());
	}

	/** Tiles walked to a side of the first node and on to the next stop (or the exit). */
	private long countWalkTiles(LocalPathfinder route, List<Integer> order, int contact)
	{
		int start = getReach(route, contact);
		int after = order.size() > 1 ? cachedDistance[contact][order.get(1)] : cachedFinish[contact];
		return start < 0 || after >= INF ? Long.MAX_VALUE : (long) start + after;
	}

	/** Running cost of reaching a side of the first node and leaving it for the next stop (or the exit). */
	private long computeFirstLegCost(LocalPathfinder route, List<Integer> order, int contact)
	{
		Point tile = cachedContacts.get(contact).tile;
		int start = route.getDistance(tile.getX(), tile.getY());
		if (start < 0)
			return Long.MAX_VALUE;
		int after = order.size() > 1 ? cachedDistance[contact][order.get(1)] : cachedFinish[contact];
		return (long) getStartCost(start) + getLegCost(after);
	}

	/**
	 * One leg's cost: running ticks (two tiles a tick, a stop at the end) first, tiles last, so an odd leg's spare half
	 * tick is free to spend. The first leg uses getStartCost.
	 */
	static int getLegCost(int tiles)
	{
		return tiles >= INF ? INF : (tiles + 1) / 2 * TICK_WEIGHT + tiles;
	}

	/**
	 * The first leg's cost: as getLegCost, plus its tiles weighed above the rest of the tour, so among tours of equal
	 * running ticks the one whose first stop is reached sooner wins before total tiles are compared.
	 */
	static int getStartCost(int tiles)
	{
		return tiles >= INF ? INF : getLegCost(tiles) + tiles * FIRST_STOP_WEIGHT;
	}

	/** Tiles walked after the first stop: every later leg plus the exit leg. */
	private static int countTiles(List<Integer> order, int[][] distance, int[] finish)
	{
		int total = finish[order.get(order.size() - 1)];
		for (int i = 1; i < order.size(); i++)
			total += distance[order.get(i - 1)][order.get(i)];
		return total;
	}

	private static String buildSignature(List<LocalPathfinder.Goal> goals)
	{
		StringBuilder text = new StringBuilder();
		for (LocalPathfinder.Goal g : goals)
			text.append(g.minX).append(',').append(g.minY).append(',')
					.append(g.maxX).append(',').append(g.maxY).append(',')
					.append(g.walkTo).append(',').append(g.continuation).append(',')
					.append(g.switchCost).append(',').append(g.facing).append(';');
		return text.toString();
	}

	/** A node subset satisfies every mandatory node and each group's quota (or all of a smaller group). */
	private static boolean isSatisfied(int mask, int[] group, Map<Integer, Integer> required)
	{
		Map<Integer, Integer> selected = new HashMap<>(), available = new HashMap<>();
		for (int i = 0; i < group.length; i++)
		{
			boolean chosen = (mask & (1 << i)) != 0;
			if (group[i] < 0)
			{
				if (!chosen)
					return false;
				continue;
			}
			available.merge(group[i], 1, Integer::sum);
			if (chosen)
				selected.merge(group[i], 1, Integer::sum);
		}
		for (Map.Entry<Integer, Integer> e : available.entrySet())
			if (selected.getOrDefault(e.getKey(), 0) < Math.min(e.getValue(), required.get(e.getKey())))
				return false;
		return true;
	}

	/** Bounded fallback for oversized rooms: keep each group's nearest nodes rather than exploring subsets. */
	private static boolean[] findNearestSubset(List<Contact> contacts, int count, int[] start, int[] group, Map<Integer, Integer> required)
	{
		int[] reach = new int[count];
		Arrays.fill(reach, INF);
		for (int c = 0; c < contacts.size(); c++)
			reach[contacts.get(c).node] = Math.min(reach[contacts.get(c).node], start[c]);
		boolean[] keep = new boolean[count];
		Map<Integer, Integer> taken = new HashMap<>();
		Integer[] byReach = new Integer[count];
		for (int i = 0; i < count; i++)
			byReach[i] = i;
		Arrays.sort(byReach, Comparator.comparingInt((Integer i) -> reach[i]).thenComparingInt(i -> i));
		for (int i : byReach)
		{
			if (group[i] < 0)
			{
				keep[i] = true;
				continue;
			}
			int t = taken.getOrDefault(group[i], 0);
			if (t < required.get(group[i]))
			{
				keep[i] = true;
				taken.put(group[i], t + 1);
			}
		}
		return keep;
	}

	private Tour planExact(List<Contact> contacts, int count, int[][] d, int[] start, int[] finish, int[] exits, int[] group, Map<Integer, Integer> required)
	{
		int width = contacts.size(), full = (1 << count) - 1;
		int[] cost = new int[(full + 1) * width], previous = new int[cost.length];
		Arrays.fill(cost, INF);
		Arrays.fill(previous, -1);
		for (int i = 0; i < width; i++)
			cost[(1 << contacts.get(i).node) * width + i] = start[i];
		for (int mask = 1; mask <= full; mask++)
			for (int last = 0; last < width; last++)
			{
				int state = mask * width + last;
				if (cost[state] >= INF)
					continue;
				for (int next = 0; next < width; next++)
				{
					int bit = 1 << contacts.get(next).node;
					if ((mask & bit) != 0 || d[last][next] >= INF)
						continue;
					int target = (mask | bit) * width + next;
					int walk = cost[state] + d[last][next];
					if (walk < cost[target])
					{
						cost[target] = walk;
						previous[target] = state;
					}
				}
			}
		// Cheapest complete trip over every quota-satisfying subset; on equal walking keep fewer stops, then the fuller
		// set order.
		int best = -1, bestMask = -1, total = INF;
		for (int mask = full; mask >= 1; mask--)
		{
			if (!isSatisfied(mask, group, required))
				continue;
			for (int i = 0; i < width; i++)
			{
				int walk = cost[mask * width + i] + finish[i];
				if (walk >= INF)
					continue;
				if (walk < total || walk == total && Integer.bitCount(mask) < Integer.bitCount(bestMask))
				{
					best = i;
					bestMask = mask;
					total = walk;
				}
			}
		}
		if (best < 0)
			return null;
		List<Integer> order = new ArrayList<>();
		for (int at = bestMask * width + best; at >= 0; at = previous[at])
			order.add(at % width);
		Collections.reverse(order);
		return new Tour(order, exits[best], total - start[order.get(0)], true);
	}

	/** Oversized/custom rooms still receive a complete exit-aware tour, with bounded insertion work. */
	private Tour planByInsertion(List<Contact> contacts, int[][] d, int[] start, int[] finish, int[] exits, boolean[] keep)
	{
		List<Integer> order = new ArrayList<>();
		Set<Integer> visited = new HashSet<>();
		int total = INF, wanted = 0;
		for (boolean k : keep)
			if (k)
				wanted++;
		while (visited.size() < wanted)
		{
			int selected = -1, position = -1, best = INF;
			for (int c = 0; c < contacts.size(); c++)
				if (keep[contacts.get(c).node] && !visited.contains(contacts.get(c).node))
					for (int at = 0; at <= order.size(); at++)
					{
						int before = at == 0 ? -1 : order.get(at - 1);
						int after = at == order.size() ? -1 : order.get(at);
						int left = before < 0 ? start[c] : d[before][c];
						int right = after < 0 ? finish[c] : d[c][after];
						int removed = order.isEmpty() ? 0 : getReplacedLeg(before, after, d, start, finish);
						int cost = (order.isEmpty() ? 0 : total) + left + right - removed;
						if (left < INF && right < INF && cost < best)
						{
							best = cost;
							selected = c;
							position = at;
						}
					}
			if (selected < 0)
				return null;
			order.add(position, selected);
			visited.add(contacts.get(selected).node);
			total = best;
		}
		int last = order.get(order.size() - 1);
		return new Tour(order, exits[last], total - start[order.get(0)], false);
	}

	/** The leg an insertion between two contacts replaces: start to after, before to finish, or before to after (-1 is an end). */
	private static int getReplacedLeg(int before, int after, int[][] d, int[] start, int[] finish)
	{
		if (before < 0)
			return start[after];
		if (after < 0)
			return finish[before];
		return d[before][after];
	}
}
