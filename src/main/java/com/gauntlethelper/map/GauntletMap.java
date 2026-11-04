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

package com.gauntlethelper.map;

import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.Requirement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;

/** One run's 7x7 public map. Indexing is [row][column], north at row zero. */
public final class GauntletMap
{
	public static final int SIZE = 7;
	/** The home room (spawn), at the center of the map; rooms are relative to it. */
	public static final Point HOME = new Point(0, 0);
	/**
	 * The regular Gauntlet's ring holds one room each with at least this many crystal deposits, linum tirinum and phren
	 * roots (and one with weak monsters); the Corrupted Gauntlet's ring has no such rooms.
	 */
	public static final int RING_GUARANTEE_NODES = 3;

	public static final class SearchTarget
	{
		public final GauntletRoom objective;
		public final Point waypoint;
		private final String description;

		public SearchTarget(GauntletRoom objective, Point waypoint, String description)
		{
			this.objective = objective;
			this.waypoint = waypoint;
			this.description = description;
		}

		public String getLabel()
		{
			if (description != null)
				return description;
			return objective.hasLivingDemiBoss() ? "Check observed demi room" : objective.isGuaranteedDemiSpawn()
					? "Check guaranteed demi room" : "Check demi room";
		}
	}

	/** A room waiting in a path search's queue: lowest cost first, then the earliest queued, so ties stay breadth-first. */
	private static final class QueuedRoom
	{
		static final Comparator<QueuedRoom> ORDER = Comparator.comparingInt((QueuedRoom room) -> room.cost).thenComparingInt(room -> room.order);

		final Point point;
		final int cost;
		final int order;

		QueuedRoom(Point point, int cost, int order)
		{
			this.point = point;
			this.cost = cost;
			this.order = order;
		}
	}

	private GauntletRoom[][] rooms;
	private List<GauntletRoom> roomList;
	private int startIndex = -1, revision;
	private long nextEnemyKey = 1;
	private final IdentityHashMap<Object, Long> enemyIdentities = new IdentityHashMap<>();
	private final Map<Integer, Long> enemyIndices = new HashMap<>();

	public boolean initialize(int startIndex)
	{
		if (MapCoordinates.getBossDirection(startIndex) == null)
			return false;
		if (rooms != null && this.startIndex == startIndex)
			return true;
		reset();
		this.startIndex = startIndex;
		rooms = new GauntletRoom[SIZE][SIZE];
		for (int row = 0; row < SIZE; row++)
			for (int col = 0; col < SIZE; col++)
				rooms[row][col] = new GauntletRoom(row, col, startIndex);
		discover(HOME);
		GauntletRoom boss = rooms[3][3];
		for (GauntletRoom.Direction d : boss.getConnections().keySet())
			connect(getRelative(boss), d, GauntletRoom.Connection.HUNLLEF_BARRIER);
		return true;
	}

	public void reset()
	{
		rooms = null;
		roomList = null;
		startIndex = -1;
		nextEnemyKey = 1;
		enemyIdentities.clear();
		enemyIndices.clear();
		revision++;
	}

	public boolean isInitialized() { return rooms != null; }
	public int getRouteRevision() { return revision; }
	public GauntletRoom getRoom(int row, int column)
	{
		return rooms == null || row < 0 || row >= SIZE || column < 0 || column >= SIZE ? null : rooms[row][column];
	}

	public GauntletRoom getRoom(Point relative)
	{
		return relative == null ? null : getRoom(startIndex / SIZE - relative.getY(), startIndex % SIZE + relative.getX());
	}

	/** Every room, row by row. The grid is fixed once initialized, so the list is built once per attempt. */
	public List<GauntletRoom> getRooms()
	{
		if (rooms == null)
			return Collections.emptyList();
		if (roomList == null)
		{
			List<GauntletRoom> result = new ArrayList<>();
			for (GauntletRoom[] row : rooms)
				result.addAll(Arrays.asList(row));
			roomList = List.copyOf(result);
		}
		return roomList;
	}

	public Point getRelative(GauntletRoom room)
	{
		return new Point(room.getColumn() - startIndex % SIZE, startIndex / SIZE - room.getRow());
	}

	public void discover(Point relative)
	{
		GauntletRoom room = getRoom(relative);
		if (room != null && !room.isDiscovered())
		{
			room.discover();
			revision++;
		}
	}

	public void visit(Point relative)
	{
		discover(relative);
		GauntletRoom room = getRoom(relative);
		if (room != null)
			room.visit();
	}

	public void recordResource(Point relative, WorldPoint at, ObjectKind kind, boolean active, int tick)
	{
		GauntletRoom room = getRoom(relative);
		if (room != null && room.isDiscovered())
			room.recordResource(at, kind, active, tick);
	}

	public void recordFacility(Point relative, WorldPoint at, ObjectKind kind)
	{
		GauntletRoom room = getRoom(relative);
		if (room != null && room.isDiscovered())
			room.recordFacility(at, kind);
	}

	public void removeObject(WorldPoint at, int tick)
	{
		for (GauntletRoom room : getRooms())
		{
			room.recordResource(at, null, false, tick);
			room.removeFacility(at);
		}
	}

	/** Flattened adapter for local node guidance; room objects remain authoritative. */
	public Map<Point, ObjectKind> findActiveNodes()
	{
		Map<Point, ObjectKind> result = new LinkedHashMap<>();
		for (GauntletRoom room : getRooms())
			for (GauntletRoom.ResourceNode node : room.getResources())
				if (node.isActive())
					result.put(new Point(node.getLocation().getX(), node.getLocation().getY()), node.getKind());
		return Collections.unmodifiableMap(result);
	}

	public void connect(Point relative, GauntletRoom.Direction direction, GauntletRoom.Connection state)
	{
		GauntletRoom from = getRoom(relative);
		if (from == null)
			return;
		GauntletRoom to = getRoom(new Point(relative.getX() + direction.getDx(), relative.getY() + direction.getDy()));
		if (to == null)
			return;
		if (from.isHunllefRoom() || to.isHunllefRoom())
			state = GauntletRoom.Connection.HUNLLEF_BARRIER;
		GauntletRoom.Connection previous = from.getConnection(direction);
		// Loaded stale unlit objects must not reverse an observed opening.
		if (previous == GauntletRoom.Connection.OPEN && state == GauntletRoom.Connection.UNLIT)
			return;
		if (previous != state)
		{
			from.setConnection(direction, state);
			to.setConnection(getOpposite(direction), state);
			revision++;
		}
	}

	private static GauntletRoom.Direction getOpposite(GauntletRoom.Direction d)
	{
		return GauntletRoom.Direction.values()[(d.ordinal() + 2) % 4];
	}

	public void beginEnemyObservation()
	{
		for (GauntletRoom room : getRooms())
			room.beginObservation();
	}

	public void completeObservation(Point relative, int tick)
	{
		GauntletRoom room = getRoom(relative);
		if (room != null && room.isDiscovered())
			room.completeObservation(tick);
	}

	private GauntletRoom.Enemy findEnemy(long key)
	{
		for (GauntletRoom room : getRooms())
		{
			GauntletRoom.Enemy found = room.findEnemy(key);
			if (found != null)
				return found;
		}
		return null;
	}

	public void observeEnemy(Object identity, int npcIndex, int npcId, Point relative, WorldPoint at, boolean dead, int tick)
	{
		GauntletRoom room = getRoom(relative);
		EnemyType type = EnemyType.fromNpcId(npcId);
		if (room == null || !room.isDiscovered() || at == null || type == null)
			return;
		Long key = enemyIdentities.get(identity);
		GauntletRoom.Enemy previous = key == null ? null : findEnemy(key);
		if (previous == null)
		{
			Long indexed = enemyIndices.get(npcIndex);
			GauntletRoom.Enemy candidate = indexed == null ? null : findEnemy(indexed);
			if (candidate != null && candidate.isAlive() && !candidate.isVisible() && candidate.getType() == type && candidate.getLocation().equals(at))
				previous = candidate;
			if (previous == null)
				for (GauntletRoom.Enemy candidateAtTile : room.getEnemies())
					if (candidateAtTile.isAlive() && !candidateAtTile.isVisible() && candidateAtTile.getType() == type
							&& candidateAtTile.getLocation().equals(at))
					{
						previous = candidateAtTile;
						break;
					}
			key = previous == null ? nextEnemyKey++ : previous.getKey();
			enemyIdentities.put(identity, key);
		}
		// Reconcile movement across room boundaries without duplicating the enemy.
		for (GauntletRoom other : getRooms())
			if (other != room)
				other.removeEnemy(key);
		GauntletRoom.EnemyState state = dead || previous != null && previous.getState() == GauntletRoom.EnemyState.DEFEATED
				? GauntletRoom.EnemyState.DEFEATED : GauntletRoom.EnemyState.ALIVE;
		room.putEnemy(new GauntletRoom.Enemy(key, npcId, npcIndex, type, at, state, true, tick));
		enemyIndices.put(npcIndex, key);
	}

	public void markEnemyDead(Object identity, int tick)
	{
		Long key = enemyIdentities.get(identity);
		if (key == null)
			return;
		for (GauntletRoom room : getRooms())
		{
			GauntletRoom.Enemy enemy = room.findEnemy(key);
			if (enemy != null)
				room.putEnemy(enemy.withState(GauntletRoom.EnemyState.DEFEATED, enemy.isVisible(), tick));
		}
	}

	public void markEnemyUnloaded(Object identity)
	{
		Long key = enemyIdentities.get(identity);
		if (key == null)
			return;
		for (GauntletRoom room : getRooms())
		{
			GauntletRoom.Enemy enemy = room.findEnemy(key);
			if (enemy != null)
				room.putEnemy(enemy.withState(enemy.getState(), false, enemy.getObservedTick()));
		}
	}

	public void updateLoot(List<GroundLoot.Entry> entries, Function<WorldPoint, Point> roomAt)
	{
		Map<GauntletRoom, List<GroundLoot.Entry>> grouped = new HashMap<>();
		for (GroundLoot.Entry entry : entries)
		{
			GauntletRoom room = getRoom(roomAt.apply(entry.point));
			if (room != null && room.isDiscovered())
				grouped.computeIfAbsent(room, r -> new ArrayList<>()).add(entry);
		}
		for (GauntletRoom room : getRooms())
			room.setLoot(grouped.getOrDefault(room, Collections.emptyList()));
	}

	/** Known-room paths allow an unknown/unlit final room only; geometry always stays inside the grid. */
	public List<Point> findPath(Point from, Point to) { return findPath(from, to, p -> true); }
	public List<Point> findPath(Point from, Point to, Predicate<Point> firstStep)
	{
		return findPath(from, to, false, firstStep);
	}

	private List<Point> findPath(Point from, Point to, boolean crossUnopened, Predicate<Point> firstStep)
	{
		GauntletRoom start = getRoom(from), goal = getRoom(to);
		if (start == null || goal == null || start.isHunllefRoom() || goal.isHunllefRoom())
			return Collections.emptyList();
		// Fewest rooms first; among equally short routes, the one with fewest unopened steps, so a tie through an
		// unlit side room doesn't hide the far door of a just-lit empty room.
		Map<Point, Point> previous = new HashMap<>();
		Map<Point, Integer> costs = new HashMap<>();
		// Equal routes resolve in breadth-first order (QueuedRoom.ORDER).
		PriorityQueue<QueuedRoom> queue = new PriorityQueue<>(QueuedRoom.ORDER);
		int order = 0;
		costs.put(from, 0);
		queue.add(new QueuedRoom(from, 0, order++));
		previous.put(from, null);
		Set<Point> done = new HashSet<>();
		while (!queue.isEmpty())
		{
			Point reached = queue.remove().point;
			if (!done.add(reached))
				continue;
			if (reached.equals(to))
				return buildPath(reached, previous);
			GauntletRoom room = getRoom(reached);
			for (GauntletRoom.Direction d : GauntletRoom.Direction.values())
			{
				Point next = new Point(reached.getX() + d.getDx(), reached.getY() + d.getDy());
				GauntletRoom neighbor = findSearchNeighbor(from, reached, next, done, firstStep);
				if (neighbor == null)
					continue;
				GauntletRoom.Connection connection = room.getConnection(d);
				boolean unopened = !neighbor.isDiscovered() || connection == GauntletRoom.Connection.UNLIT;
				if (connection == GauntletRoom.Connection.HUNLLEF_BARRIER || !crossUnopened && !next.equals(to) && unopened)
					continue;
				int cost = costs.get(reached) + ROOM_STEP_COST + (unopened ? 1 : 0);
				if (costs.containsKey(next) && costs.get(next) <= cost)
					continue;
				costs.put(next, cost);
				previous.put(next, reached);
				queue.add(new QueuedRoom(next, cost, order++));
			}
		}
		return Collections.emptyList();
	}

	/** The rooms from a path search's start to end, walking back through the search's previous-room links. */
	private static List<Point> buildPath(Point end, Map<Point, Point> previous)
	{
		List<Point> result = new ArrayList<>();
		for (Point step = end; step != null; step = previous.get(step))
			result.add(step);
		Collections.reverse(result);
		return result;
	}

	/**
	 * The room at next for a path search, or null when the search skips it: off the map, Hunllef's, already done, or a
	 * step out of the start room that firstStep refuses.
	 */
	private GauntletRoom findSearchNeighbor(Point from, Point reached, Point next, Set<Point> done, Predicate<Point> firstStep)
	{
		GauntletRoom neighbor = getRoom(next);
		if (neighbor == null || neighbor.isHunllefRoom() || done.contains(next) || reached.equals(from) && !firstStep.test(next))
			return null;
		return neighbor;
	}

	/** A known route whose every step, the last included, is a discovered room through a lit door (no sceptre). */
	public boolean hasOpenRoute(Point from, Point to)
	{
		List<Point> route = findPath(from, to);
		if (route.isEmpty())
			return false;
		for (int i = 1; i < route.size(); i++)
		{
			Point previous = route.get(i - 1), step = route.get(i);
			if (!getRoom(step).isDiscovered() || getRoom(previous).getConnection(getDirection(previous, step)) == GauntletRoom.Connection.UNLIT)
				return false;
		}
		return true;
	}

	private static GauntletRoom.Direction getDirection(Point from, Point to)
	{
		return to.getX() > from.getX() ? GauntletRoom.Direction.EAST
				: to.getX() < from.getX() ? GauntletRoom.Direction.WEST
				: to.getY() > from.getY() ? GauntletRoom.Direction.NORTH
				: GauntletRoom.Direction.SOUTH;
	}

	/** findRingPath's costs: rooms, then doors to light, then edge rooms, then walking tiles to the first doorway. */
	private static final int RING_ROOM_COST = 1_000_000, RING_LIGHT_COST = 20_000, RING_EDGE_COST = 1_000;

	/** Cost of one room step; an unopened step adds one, so it only breaks ties between equally short routes. */
	private static final int ROOM_STEP_COST = 100;

	public List<Point> findExplorationPath(Point from, Point to, Predicate<Point> firstStep)
	{
		return findPath(from, to, true, firstStep);
	}

	public SearchTarget buildSearchTarget(Point from, Point destination, Predicate<Point> firstStep, String label)
	{
		List<Point> full = findPath(from, destination, true, firstStep);
		if (full.size() < 2)
			return null;
		return new SearchTarget(getRoom(destination), findFrontier(full), label);
	}

	/**
	 * The first room along an exploration path that is undiscovered or behind an unlit door: as far as known routes
	 * go toward the path's end. The end itself when every step is already known and open.
	 */
	public Point findFrontier(List<Point> exploration)
	{
		for (int i = 1; i < exploration.size(); i++)
		{
			Point previous = exploration.get(i - 1), step = exploration.get(i);
			if (!getRoom(step).isDiscovered() || getRoom(previous).getConnection(getDirection(previous, step)) == GauntletRoom.Connection.UNLIT)
				return step;
		}
		return exploration.get(exploration.size() - 1);
	}

	/**
	 * The route to the nearest undiscovered room of the eight around Hunllef's, for fish still short after the demis,
	 * rather than opening demi rooms. It may light interior rooms on the way but never an
	 * edge or corner room; an edge room already open may be walked through when that is faster. Fewest rooms first,
	 * then fewest doors to light (lighting is slower than walking), then fewest edge rooms, then fewest firstTiles
	 * (walking tiles to the doorway into a neighboring room, capped at 999). Empty when every ring room is discovered
	 * or none can be reached that way.
	 */
	public List<Point> findRingPath(Point from, Predicate<Point> firstStep, ToIntFunction<Point> firstTiles)
	{
		return findInnerPath(from, GauntletRoom::isHunllefRing, firstStep, firstTiles);
	}

	/**
	 * As findRingPath, to the nearest undiscovered room of the ring or the middle layer (any room neither edge nor
	 * Hunllef's), weighted alike: for materials still short after the demis while no fish is.
	 */
	public List<Point> findInteriorPath(Point from, Predicate<Point> firstStep, ToIntFunction<Point> firstTiles)
	{
		return findInnerPath(from, room -> !room.isPerimeter(), firstStep, firstTiles);
	}

	/**
	 * Regular Gauntlet ring metadata: whether a ring room has been seen with at least RING_GUARANTEE_NODES nodes of this
	 * material (depleted ones count), so it may be the guaranteed one. Until then that room is surely among the ring
	 * rooms not yet discovered. Only ore, bark and linum tirinum have such a room.
	 */
	public boolean hasRingGuaranteeRoom(Requirement resource)
	{
		for (GauntletRoom room : getRooms())
			if (room.isHunllefRing() && room.countSeenResourceNodes(resource) >= RING_GUARANTEE_NODES)
				return true;
		return false;
	}

	/** As hasRingGuaranteeRoom, for the regular ring's weak-monster room: a ring room seen with a rat, spider or bat. */
	public boolean hasRingWeakMonsterRoom()
	{
		for (GauntletRoom room : getRooms())
			for (EnemyType type : EnemyType.values())
				if (room.isHunllefRing() && type.isWeak() && room.hasSeenEnemy(type))
					return true;
		return false;
	}

	private List<Point> findInnerPath(Point from, Predicate<GauntletRoom> target, Predicate<Point> firstStep, ToIntFunction<Point> firstTiles)
	{
		GauntletRoom start = getRoom(from);
		if (start == null || start.isHunllefRoom())
			return Collections.emptyList();
		Map<Point, Point> previous = new HashMap<>();
		Map<Point, Integer> costs = new HashMap<>();
		PriorityQueue<QueuedRoom> queue = new PriorityQueue<>(QueuedRoom.ORDER);
		int order = 0;
		costs.put(from, 0);
		queue.add(new QueuedRoom(from, 0, order++));
		previous.put(from, null);
		Set<Point> done = new HashSet<>();
		while (!queue.isEmpty())
		{
			Point reached = queue.remove().point;
			if (!done.add(reached))
				continue;
			GauntletRoom room = getRoom(reached);
			if (target.test(room) && !room.isHunllefRoom() && !room.isDiscovered())
				return buildPath(reached, previous);
			for (GauntletRoom.Direction d : GauntletRoom.Direction.values())
			{
				Point next = new Point(reached.getX() + d.getDx(), reached.getY() + d.getDy());
				GauntletRoom neighbor = findSearchNeighbor(from, reached, next, done, firstStep);
				if (neighbor == null)
					continue;
				GauntletRoom.Connection connection = room.getConnection(d);
				boolean unopened = !neighbor.isDiscovered() || connection == GauntletRoom.Connection.UNLIT;
				if (connection == GauntletRoom.Connection.HUNLLEF_BARRIER || unopened && neighbor.isPerimeter())
					continue;
				int cost = costs.get(reached) + getInnerStepCost(connection, neighbor)
						+ (reached.equals(from) ? Math.min(999, Math.max(0, firstTiles.applyAsInt(next))) : 0);
				if (costs.containsKey(next) && costs.get(next) <= cost)
					continue;
				costs.put(next, cost);
				previous.put(next, reached);
				queue.add(new QueuedRoom(next, cost, order++));
			}
		}
		return Collections.emptyList();
	}

	/** A findRingPath or findInteriorPath result's cost, walking left out: two paths that tie differ only in firstTiles. */
	public int countInnerPathCost(List<Point> path)
	{
		int cost = 0;
		for (int i = 1; i < path.size(); i++)
			cost += getInnerStepCost(getRoom(path.get(i - 1)).getConnection(getDirection(path.get(i - 1), path.get(i))), getRoom(path.get(i)));
		return cost;
	}

	private static int getInnerStepCost(GauntletRoom.Connection connection, GauntletRoom neighbor)
	{
		boolean unopened = !neighbor.isDiscovered() || connection == GauntletRoom.Connection.UNLIT;
		return RING_ROOM_COST + (unopened ? RING_LIGHT_COST : 0) + (neighbor.isPerimeter() ? RING_EDGE_COST : 0);
	}

	/** Observed living demis first, then four guaranteed rooms, then other perimeter rooms.
	 * A distant unexplored objective yields its first reachable frontier, never a route through unseen tiles. */
	public List<SearchTarget> findDemiSearchTargets(Point current, Predicate<Point> reachableFirstStep, DemiBossPlan goal)
	{
		for (int tier = 0; tier < (goal == null ? 3 : 6); tier++)
		{
			List<SearchTarget> result = new ArrayList<>();
			for (GauntletRoom room : getRooms())
			{
				Point destination = getRelative(room);
				if (destination.equals(current) || room.isHunllefRoom() || room.isDemiSearchChecked() && !room.hasLivingDemiBoss())
					continue;
				int priority = goal != null ? goal.rank(room) : getSearchPriority(room);
				if (priority != tier)
					continue;
				List<Point> exploration = findPath(current, destination, true, reachableFirstStep);
				if (exploration.size() < 2)
					continue;
				Point waypoint = findFrontier(exploration);
				List<Point> leg = findPath(current, waypoint, reachableFirstStep);
				if (leg.size() > 1 && reachableFirstStep.test(leg.get(1)))
					result.add(new SearchTarget(room, waypoint, goal == null ? null : goal.getLabel(room)));
			}
			if (!result.isEmpty())
				return List.copyOf(result);
		}
		return Collections.emptyList();
	}

	/** Search tier without a demi plan: a living demi first, then guaranteed spawns, the perimeter, the rest. */
	private static int getSearchPriority(GauntletRoom room)
	{
		if (room.hasLivingDemiBoss())
			return 0;
		if (room.isGuaranteedDemiSpawn())
			return 1;
		if (room.isPerimeter())
			return 2;
		return 3;
	}
}
