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

package com.gauntlethelper.plan;

import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.runelite.api.Point;
import static net.runelite.api.gameval.ItemID.GAUNTLET_AXE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HARPOON_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MELEE_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PICKAXE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RANGED_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RAW_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM;

/** Bounded quantity/action search. Observed loot is exact; harvest yields and unexplored objectives are forecasts. */
public final class TripPlanner
{
	public static final class Step
	{
		public final Point room;
		public final Simulation.Kind kind;
		public final int itemId, quantity;
		public final String label;

		Step(Point room, Simulation.Kind kind, int id, int quantity, String label)
		{
			this.room = room;
			this.kind = kind;
			itemId = id;
			this.quantity = quantity;
			this.label = label;
		}
	}

	public static final class Result
	{
		public final List<Step> steps;
		public final int roomSteps, peakSlots;
		public final boolean complete, estimated, bounded;

		Result(List<Step> steps, int distance, int peak, boolean complete, boolean estimated, boolean bounded)
		{
			this.steps = List.copyOf(steps);
			roomSteps = distance;
			peakSlots = peak;
			this.complete = complete;
			this.estimated = estimated;
			this.bounded = bounded;
		}

		public Step getNextTask()
		{
			for (Step step : steps)
				if (!step.kind.isInventoryOnly())
					return step;
			return null;
		}

		public String buildSummary()
		{
			StringJoiner text = new StringJoiner(" → ");
			Point previous = null;
			int shown = 0;
			for (Step step : steps)
			{
				// Unlike getNextTask, the summary still shows mixing and grinding.
				if (step.kind == Simulation.Kind.DROP_TOOL || step.kind == Simulation.Kind.EQUIP)
					continue;
				if (step.room.equals(previous) && step.kind != Simulation.Kind.STORE_FISH)
					continue;
				if (shown++ >= 6)
				{
					text.add("…");
					break;
				}
				text.add(step.label);
				previous = step.room;
			}
			return "Trip forecast: " + text + " (" + roomSteps + " room steps; peak " + peakSlots + " slots)";
		}
	}

	/** Source id of the fill-vials source: a room with water (a fishing spot or the pump), not an item. */
	private static final int FILL_VIALS = 0;

	private static final class Source
	{
		final Point room;
		/** The item gathered or picked up, or FILL_VIALS. */
		final int id;
		final int quantity;
		final boolean harvest;
		final boolean estimated;
		/** The room has water for vials: an active fishing spot (always true for the fill-vials source). */
		final boolean roomHasWater;
		final String label;

		Source(Point room, int id, int quantity, boolean harvest, boolean estimated, boolean roomHasWater, String label)
		{
			this.room = room;
			this.id = id;
			this.quantity = quantity;
			this.harvest = harvest;
			this.estimated = estimated;
			this.roomHasWater = roomHasWater;
			this.label = label;
		}

		boolean isFillVials() { return id == FILL_VIALS; }

		String getKey() { return room + ":" + id + ":" + quantity + ":" + harvest + ":" + roomHasWater; }
	}

	private static final class State
	{
		final Point room;
		final Simulation sim;
		final int[] remaining;
		final List<Step> steps;
		final int distance, deficit;
		final Requirements plan;
		final boolean estimated;
		private String key;

		State(Point room, Simulation sim, int[] remaining, List<Step> steps, int distance, boolean estimated)
		{
			this.room = room;
			this.sim = sim;
			this.remaining = remaining;
			this.steps = steps;
			this.distance = distance;
			this.estimated = estimated;
			this.plan = sim.plan();
			this.deficit = computeDeficit(this.plan);
		}

		/** Built once: a state is never changed after it is offered, and sorting compares keys many times. */
		String getKey()
		{
			if (key == null)
				key = room + "|" + sim.getKey() + "|" + Arrays.toString(remaining);
			return key;
		}
	}

	private String cachedKey;
	private Result cached;
	private static final int WIDTH = 48, MAX_EVALUATIONS = 6000, MAX_DEPTH = 36;

	public void reset()
	{
		cachedKey = null;
		cached = null;
	}

	public Result plan(GauntletMap map, Point start, Requirements goal, Inventory inv,
		GroundLoot loot, DemiBossTracker kills, Set<Point> firstSteps, int maxDistance)
	{
		return plan(map, start, goal, inv, loot, kills, firstSteps, maxDistance, true);
	}

	public Result plan(GauntletMap map, Point start, Requirements goal, Inventory inv, GroundLoot loot, DemiBossTracker kills,
			Set<Point> firstSteps, int maxDistance, boolean demiSearch)
	{
		List<Source> sources = listSources(map, loot);
		int raw = 0, cooked = 0;
		for (GroundLoot.Entry e : loot.getEntries())
			if (loot.isStoredFood(e))
			{
				if (e.id == GAUNTLET_FOOD)
					cooked += e.quantity;
				else
					raw += e.quantity;
			}
		List<Point> ordered = new ArrayList<>(firstSteps);
		ordered.sort(Comparator.comparingInt(Point::getX).thenComparingInt(Point::getY));
		StringBuilder text = new StringBuilder();
		text.append(map.getRouteRevision()).append('|').append(start).append('|')
				.append(new TreeMap<>(inv.getHeldItems())).append('|').append(new TreeMap<>(inv.getEquippedItems()))
				.append('|').append(inv.getFreeSlots()).append('|').append(demiSearch).append('|')
				.append(goal.getFoodTarget()).append(':').append(goal.getLeafTarget()).append(':')
				.append(goal.getCraftedPotions()).append(':')
				.append(goal.isStaffRequired()).append(':').append(goal.getWeaponPair()).append(':')
				.append(goal.getSelection()).append(':').append(goal.getArmorGoal())
				.append('|').append(raw).append(':').append(cooked).append('|')
				.append(ordered).append('|').append(maxDistance).append('|')
				.append(kills == null ? "" : kills.buildForecastKey());
		for (Source source : sources)
			text.append('|').append(source.getKey());
		// Search ranking depends on observed enemy types and inspection/death state as well as geometry.
		for (GauntletRoom room : map.getRooms())
			text.append('|').append(room.getIndex()).append(':').append(room.isDemiSearchChecked()).append(':').append(room.countLivingEnemiesByType());
		String key = text.toString();
		if (key.equals(cachedKey))
			return cached;
		cachedKey = key;
		cached = solve(map, start, goal, inv, raw, cooked, kills, firstSteps, maxDistance, sources, demiSearch);
		return cached;
	}

	private List<Source> listSources(GauntletMap map, GroundLoot loot)
	{
		List<Source> result = new ArrayList<>();
		for (GauntletRoom room : map.getRooms())
		{
			Map<Integer, Integer> harvest = new TreeMap<>();
			boolean fishingSpot = false;
			for (GauntletRoom.ResourceNode node : room.getResources())
				if (node.isActive())
				{
					ObjectKind kind = node.getKind();
					int yield = kind.getNominalYield();
					harvest.merge(kind.resource.getItemId(), yield, Integer::sum);
					fishingSpot |= kind == ObjectKind.FISH;
				}
			for (Map.Entry<Integer, Integer> e : harvest.entrySet())
				result.add(new Source(map.getRelative(room), e.getKey(), e.getValue(), true, true, fishingSpot, getLabel(e.getKey())));
			if (fishingSpot || room.hasFacility(ObjectKind.WATER))
				result.add(new Source(map.getRelative(room), FILL_VIALS, 1, false, false, true, "Fill vials"));
			for (GroundLoot.Entry e : room.getLoot())
				if (!loot.isStoredFood(e))
					result.add(new Source(map.getRelative(room), e.id, e.quantity, false, false, false, "Pick up " + GroundLoot.getLootName(e.id)));
		}
		result.sort(Comparator.comparingInt((Source s) -> s.room.getX())
				.thenComparingInt(s -> s.room.getY())
				.thenComparingInt(s -> s.id)
				.thenComparing(s -> s.harvest));
		return result;
	}

	private Result solve(GauntletMap map, Point start, Requirements goal, Inventory inv, int raw, int cooked,
			DemiBossTracker kills,
			Set<Point> firstSteps, int maxDistance, List<Source> sources, boolean demiSearch)
	{
		return new TripSearch(map, start, kills, firstSteps, maxDistance, sources, demiSearch).run(new Simulation(goal, inv, raw, cooked));
	}

	/**
	 * One bounded breadth-first search. Each layer offers every state's gathers and pickups, walk home and teleport
	 * home to the next, which keeps the WIDTH states with the most progress; it stops after MAX_DEPTH layers or
	 * MAX_EVALUATIONS candidate sources.
	 */
	private static final class TripSearch
	{
		private final GauntletMap map;
		private final Point start;
		private final Point home = GauntletMap.HOME;
		private final DemiBossTracker kills;
		private final Set<Point> firstSteps;
		private final int maxDistance;
		private final List<Source> sources;
		private final boolean demiSearch;
		private final BiFunction<Point, Point, List<Point>> route;
		private final Function<Point, List<Point>> toHome;
		private final Map<String, Integer> seen = new HashMap<>();
		private State best, bestComplete, bestSearch;
		private Step bestSearchStep;
		private int bestSearchDistance;
		private int evaluated;
		private boolean bounded;

		TripSearch(GauntletMap map, Point start, DemiBossTracker kills, Set<Point> firstSteps, int maxDistance, List<Source> sources, boolean demiSearch)
		{
			this.map = map;
			this.start = start;
			this.kills = kills;
			this.firstSteps = firstSteps;
			this.maxDistance = maxDistance;
			this.sources = sources;
			this.demiSearch = demiSearch;
			Map<Point, Map<Point, List<Point>>> paths = new HashMap<>();
			Map<Point, List<Point>> homePaths = new HashMap<>();
			// Room paths are fixed for one search; memoize them instead of rebuilding string keys and BFS results per
			// candidate.
			route = (a, b) -> paths.computeIfAbsent(a,
					k -> new HashMap<>()).computeIfAbsent(b, k -> a.equals(start) ? map.findPath(a, b, firstSteps::contains)
					: map.findPath(a, b));
			toHome = a -> homePaths.computeIfAbsent(a, k -> map.findPath(k, home));
		}

		Result run(Simulation initial)
		{
			List<State> layer = List.of(createRoot(initial));
			for (int depth = 0; depth < MAX_DEPTH && !layer.isEmpty(); depth++)
			{
				Map<String, State> next = new LinkedHashMap<>();
				for (State state : layer)
				{
					if (!expand(state, next))
					{
						bounded = true;
						return chooseResult();
					}
				}
				layer = keepBest(next);
			}
			if (!layer.isEmpty())
				bounded = true;
			return chooseResult();
		}

		/** The starting state: the simulation after the start room's own work, with every source unused. */
		private State createRoot(Simulation initial)
		{
			int[] remaining = new int[sources.size()];
			for (int i = 0; i < remaining.length; i++)
				remaining[i] = sources.get(i).quantity;
			List<Step> initialSteps = new ArrayList<>();
			normalize(map, start, initial, initialSteps);
			State root = new State(start, initial, remaining, initialSteps, 0, false);
			best = root;
			seen.put(root.getKey(), 0);
			return root;
		}

		/** Records a finished trip or the progress made, then offers the state's successors; false once over budget. */
		private boolean expand(State state, Map<String, State> next)
		{
			if (state.plan.isReady() && state.room.equals(home))
			{
				if (bestComplete == null || compareComplete(state, bestComplete) < 0)
					bestComplete = state;
				return true;
			}
			if (compareProgress(state, best) < 0)
				best = state;
			considerDemiSearch(state);
			if (!offerSources(state, next))
				return false;
			if (!state.room.equals(home))
			{
				offerWalkHome(state, next);
				offerTeleportHome(state, next);
			}
			return true;
		}

		/** Keeps the demi search waypoint in reach with the lowest deficit, then the shortest walk. */
		private void considerDemiSearch(State state)
		{
			Requirements plan = state.plan;
			int deficit = state.deficit;
			DemiBossPlan targetPlan = new DemiBossPlan(plan, kills);
			if (demiSearch && plan.getRow(Requirement.UPGRADES).getMissing() > 0 && state.sim.snapshot().getFreeSlots() >= countLootSlots(plan))
			{
				Set<Point> available = state.room.equals(start) ? firstSteps : findNeighbors(map, state.room);
				GauntletRoom current = map.getRoom(state.room);
				List<GauntletMap.SearchTarget> searches = new ArrayList<>(map.findDemiSearchTargets(state.room, available::contains, targetPlan));
				if (targetPlan.isUseful(current))
					searches.add(new GauntletMap.SearchTarget(current, state.room, targetPlan.getLabel(current)));
				for (GauntletMap.SearchTarget search : searches)
				{
					List<Point> leg = route.apply(state.room, search.waypoint);
					if (leg.isEmpty())
						continue;
					List<Point> back = toHome.apply(search.waypoint);
					if (back.isEmpty())
						continue;
					int distance = state.distance + leg.size() - 1;
					if (distance + (state.sim.snapshot().getHeld(GAUNTLET_TELEPORT_CRYSTAL_HM) > 0 ? 0 : back.size() - 1) > maxDistance)
						continue;
					if (bestSearch == null || deficit < bestSearch.deficit || deficit == bestSearch.deficit && distance < bestSearchDistance)
					{
						bestSearch = state;
						bestSearchDistance = distance;
						bestSearchStep = new Step(search.waypoint, Simulation.Kind.SEARCH, 0, 0, search.getLabel());
					}
				}
			}
		}

		/** Offers a gather or pickup at each candidate source; false once the evaluation budget is spent. */
		private boolean offerSources(State state, Map<String, State> next)
		{
			for (int i : findCandidates(state, sources, route))
			{
				if (++evaluated > MAX_EVALUATIONS)
					return false;
				offerSource(state, i, next);
			}
			return true;
		}

		/** Walks to source i and takes what the plan still needs of it, if the walk home stays in reach. */
		private void offerSource(State state, int i, Map<String, State> next)
		{
			Source source = sources.get(i);
			if (state.remaining[i] <= 0)
				return;
			int need = countNeeded(source, state.plan, state.sim.snapshot());
			if (need <= 0)
				return;
			List<Point> leg = route.apply(state.room, source.room);
			if (leg.isEmpty())
				return;
			List<Point> back = toHome.apply(source.room);
			if (back.isEmpty())
				return;
			int distance = state.distance + leg.size() - 1;
			if (distance + (state.sim.snapshot().getHeld(GAUNTLET_TELEPORT_CRYSTAL_HM) > 0 ? 0 : back.size() - 1) > maxDistance)
				return;
			Simulation sim = state.sim.copy();
			int accepted = source.isFillVials() ? 1 : sim.acquire(source.id, Math.min(need, state.remaining[i]));
			if (accepted == 0)
				return;
			int[] left = state.remaining.clone();
			left[i] -= accepted;
			List<Step> steps = new ArrayList<>(state.steps);
			if (!source.isFillVials())
				steps.add(new Step(source.room, source.harvest ? Simulation.Kind.GATHER
						: Simulation.Kind.PICKUP, source.id, accepted, source.label + " ×" + accepted));
			normalize(map, source.room, sim, steps);
			if (sim.getKey().equals(state.sim.getKey()))
				return;
			offer(new State(source.room, sim, left, steps, distance, state.estimated || source.estimated), next, seen);
		}

		private void offerWalkHome(State state, Map<String, State> next)
		{
			List<Point> leg = route.apply(state.room, home);
			if (!leg.isEmpty() && state.distance + leg.size() - 1 <= maxDistance)
			{
				Simulation sim = state.sim.copy();
				List<Step> steps = new ArrayList<>(state.steps);
				normalize(map, home, sim, steps);
				if (!sim.getKey().equals(state.sim.getKey()))
					offer(new State(home, sim, state.remaining.clone(), steps, state.distance + leg.size() - 1, state.estimated), next, seen);
			}
		}

		private void offerTeleportHome(State state, Map<String, State> next)
		{
			if (state.sim.snapshot().getHeld(GAUNTLET_TELEPORT_CRYSTAL_HM) > 0)
			{
				Simulation sim = state.sim.copy();
				sim.teleportHome();
				List<Step> steps = new ArrayList<>(state.steps);
				steps.add(new Step(home, Simulation.Kind.TELEPORT, GAUNTLET_TELEPORT_CRYSTAL_HM, 1, "Use teleport crystal to return home"));
				normalize(map, home, sim, steps);
				offer(new State(home, sim, state.remaining.clone(), steps, state.distance, state.estimated), next, seen);
			}
		}

		/** The next layer: the WIDTH states with the most progress. */
		private List<State> keepBest(Map<String, State> next)
		{
			List<State> ordered = new ArrayList<>(next.values());
			ordered.sort(TripPlanner::compareProgress);
			if (ordered.size() > WIDTH)
			{
				bounded = true;
				ordered = ordered.subList(0, WIDTH);
			}
			return ordered;
		}

		/** The shortest finished trip, else the best demi search, else the state with the most progress. */
		private Result chooseResult()
		{
			if (bestComplete != null)
				return buildResult(bestComplete, bestComplete.steps, bestComplete.distance, true, bounded);
			if (bestSearch != null)
			{
				List<Step> steps = new ArrayList<>(bestSearch.steps);
				steps.add(bestSearchStep);
				return buildResult(bestSearch, steps, bestSearchDistance, false, bounded);
			}
			return buildResult(best, best.steps, best.distance, false, bounded);
		}
	}

	private static List<Integer> findCandidates(State state, List<Source> sources, BiFunction<Point, Point, List<Point>> route)
	{
		List<Integer> useful = new ArrayList<>();
		int[] legs = new int[sources.size()];
		for (int i = 0; i < sources.size(); i++)
			if (state.remaining[i] > 0 && countNeeded(sources.get(i), state.plan, state.sim.snapshot()) > 0)
			{
				legs[i] = route.apply(state.room, sources.get(i).room).size();
				if (legs[i] > 0)
					useful.add(i);
			}
		useful.sort(Comparator.comparingInt((Integer i) -> legs[i])
				.thenComparing(i -> sources.get(i).estimated)
				.thenComparingInt(i -> -state.remaining[i])
				.thenComparingInt(i -> i));
		Map<Integer, Integer> counts = new HashMap<>();
		List<Integer> result = new ArrayList<>();
		for (int i : useful)
		{
			Source source = sources.get(i);
			int chosen = counts.getOrDefault(source.id, 0);
			if (chosen < (source.isFillVials() ? 1 : 2))
			{
				result.add(i);
				counts.put(source.id, chosen + 1);
			}
		}
		return result;
	}

	private static Result buildResult(State state, List<Step> steps, int distance, boolean complete, boolean bounded)
	{
		return new Result(steps, distance, state.sim.getPeakSlots(), complete, state.estimated, bounded);
	}

	private static Set<Point> findNeighbors(GauntletMap map, Point room)
	{
		Set<Point> result = new LinkedHashSet<>();
		for (int[] offset : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}})
		{
			Point neighbor = new Point(room.getX() + offset[0], room.getY() + offset[1]);
			if (map.getRoom(neighbor) != null)
				result.add(neighbor);
		}
		return result;
	}

	private static void normalize(GauntletMap map, Point room, Simulation sim, List<Step> steps)
	{
		GauntletRoom known = map.getRoom(room);
		boolean home = room.equals(GauntletMap.HOME);
		boolean water = home || known != null
				&& (known.hasFacility(ObjectKind.WATER)
				|| known.countResourceNodes(Requirement.FISH) > 0);
		boolean range = home || known != null && known.hasFacility(ObjectKind.RANGE);
		int before = sim.getOperations().size();
		// The forecast cooks held raw fish only once the upgrades are done; before that it cooks none.
		sim.normalize(home, water, range, sim.plan().getRow(Requirement.UPGRADES).isComplete() ? Integer.MAX_VALUE : 0);
		if (home && !sim.plan().isReadyForStoredFood() && !sim.plan().isReady())
		{
			Simulation.Capacity capacity = Simulation.computeCapacity(sim.plan(), sim.snapshot(), true, sim.getStoredRaw(), sim.getStoredCooked());
			sim.storeFood(capacity.rawToStore, capacity.cookedToStore);
		}
		if (home)
		{
			sim.retrieveFood();
			sim.normalize(true, true, true, sim.plan().getRow(Requirement.UPGRADES).isComplete() ? Integer.MAX_VALUE : 0);
		}
		List<Simulation.Operation> ops = sim.getOperations();
		for (int i = before; i < ops.size(); i++)
		{
			Simulation.Operation op = ops.get(i);
			// Guidance never names the craft; the craft overlay marks the item.
			steps.add(new Step(room, op.kind, op.itemId, op.quantity, op.kind == Simulation.Kind.CRAFT
					? ObjectKind.BOWL.label : op.label + (op.quantity > 1 ? " ×" + op.quantity : "")));
		}
	}

	private static void offer(State state, Map<String, State> next, Map<String, Integer> seen)
	{
		String key = state.getKey();
		Integer previous = seen.get(key);
		if (previous != null && previous <= state.distance)
			return;
		seen.put(key, state.distance);
		next.put(key, state);
	}

	private static int compareComplete(State a, State b)
	{
		int c = Integer.compare(a.distance, b.distance);
		return c != 0 ? c : Integer.compare(a.steps.size(), b.steps.size());
	}

	private static int compareProgress(State a, State b)
	{
		int c = Integer.compare(a.deficit, b.deficit);
		if (c == 0)
			c = compareComplete(a, b);
		if (c == 0)
			c = a.getKey().compareTo(b.getKey());
		return c;
	}

	/**
	 * How far a forecast is from ready, for ranking unfinished plans. A missing T1 base piece weighs more than a
	 * missing upgrade step, and materials only an upgrade needs weigh less than the base's, so finishing the base beats
	 * gathering upgrade materials only. With a T1 goal there are no upgrade terms.
	 */
	static int computeDeficit(Requirements plan)
	{
		List<ArmorUpgrades.Step> base = plan.findBaseArmorSteps();
		int upgradeSteps = Math.max(0, plan.getRow(Requirement.ARMOR).getMissing() - base.size());
		int baseMaterials = ArmorUpgrades.sumMaterials(base);
		int score = plan.getRow(Requirement.UPGRADES).getMissing() * 100 + base.size() * 20 + upgradeSteps * 10
				+ plan.getRow(Requirement.WEAPONS).getMissing() * 20 + plan.getRow(Requirement.POTIONS).getMissing() * 15;
		for (Requirement resource : Requirement.MATERIALS)
		{
			int missing = plan.getRow(resource).getMissing();
			int forBase = Math.min(missing, baseMaterials);
			score += forBase * 3 + (missing - forBase);
		}
		for (Requirement resource : new Requirement[]{Requirement.FRAMES, Requirement.GRYM_LEAVES, Requirement.VIALS})
			score += plan.getRow(resource).getMissing() * 3;
		return score + plan.getRow(Requirement.FISH).getMissing() * 2 + plan.getRow(Requirement.COOKED).getMissing()
				+ (plan.getRow(Requirement.SHARDS).getMissing() + 24) / 25;
	}

	private static int countLootSlots(Requirements plan)
	{
		return plan.getRow(Requirement.UPGRADES).getMissing() + (plan.needsStaffOrb() ? 1 : 0) + (plan.getRow(Requirement.FRAMES).getMissing() > 0 ? 1 : 0);
	}

	private static int countNeeded(Source source, Requirements plan, Inventory inv)
	{
		if (source.isFillVials())
			return plan.needsWater() ? 1 : 0;
		int id = source.id;
		Requirement resource = Requirement.ofItem(id);
		// A node whose tool is not held yields nothing.
		if (source.harvest && resource != null && resource.getTool() != -1 && inv.getHeld(resource.getTool()) == 0)
			return 0;
		if (resource == Requirement.FISH)
			return countFishNeeded(source, plan, inv);
		if (id == GAUNTLET_MAGIC_COMPONENT_HM)
			return plan.getRow(Requirement.UPGRADES).getMissing() > 0 && !plan.hasUpgrade(WeaponType.STAFF) ? 1 : 0;
		if (id == GAUNTLET_RANGED_COMPONENT_HM)
			return plan.needsUpgrade(WeaponType.BOW) ? 1 : 0;
		if (id == GAUNTLET_MELEE_COMPONENT_HM)
			return plan.needsUpgrade(WeaponType.HALBERD) ? 1 : 0;
		Requirement tooled = Requirement.ofTool(id);
		if (tooled != null)
			return plan.getRow(tooled).getMissing() > 0 && inv.getHeld(id) == 0 ? 1 : 0;
		return resource == null ? 0 : plan.getRow(resource).getMissing();
	}

	/**
	 * An incomplete forecast whose only work is a trip home (teleport, craft, cook, store), while fish are still
	 * missing and the inventory has room for all of them. That happens when no known fishing spot is in reach: the home
	 * work lowers the deficit, so the home trip wins the progress ranking, and the player must come straight back out
	 * for the fish. Lighting a nearby room for a new spot is better; the home work waits for the one trip
	 * after the fish. The same holds for every gathered resource, not only fish.
	 */
	public static boolean isGatherShortHomeTrip(Result forecast, Requirements plan, Inventory inv)
	{
		return isGatherShortHomeTrip(forecast, plan, inv, null);
	}

	/**
	 * isGatherShortHomeTrip as it will stand once the forecast's leading gathers in room are done: their items are
	 * counted as held. True when the trip after this room's work lights a room for a missing resource instead of the
	 * forecast's home trip, so the room tour previews a door, not the teleport.
	 */
	public static boolean isGatherShortAfterRoom(Result forecast, Requirements plan, Inventory inv, Point room)
	{
		return isGatherShortHomeTrip(forecast, plan, inv, room);
	}

	private static boolean isGatherShortHomeTrip(Result forecast, Requirements plan, Inventory inv, Point room)
	{
		if (forecast.complete)
			return false;
		Map<Integer, Integer> done = new HashMap<>();
		int first = 0;
		if (room != null)
			for (; first < forecast.steps.size(); first++)
			{
				Step step = forecast.steps.get(first);
				if (!step.room.equals(room) || step.kind != Simulation.Kind.GATHER && !step.kind.isInventoryOnly())
					break;
				if (step.kind == Simulation.Kind.GATHER)
					done.merge(step.itemId, step.quantity, Integer::sum);
			}
		if (room != null && done.isEmpty())
			return false;
		int free = inv.getFreeSlots();
		for (Map.Entry<Integer, Integer> entry : done.entrySet())
			free -= Simulation.isStacked(entry.getKey()) ? inv.getHeld(entry.getKey()) > 0 ? 0 : 1 : entry.getValue();
		Requirement[] gathered = {Requirement.FISH, Requirement.GRYM_LEAVES, Requirement.LINUM_TIRINUM, Requirement.ORE, Requirement.BARK};
		int[] tools = {GAUNTLET_HARPOON_HM, 0, 0, GAUNTLET_PICKAXE_HM, GAUNTLET_AXE_HM};
		int missing = 0, slots = 0;
		for (int i = 0; i < gathered.length; i++)
		{
			int id = gathered[i].getItemId();
			int resourceShort = plan.getRow(gathered[i]).getMissing() - done.getOrDefault(id, 0);
			if (resourceShort <= 0)
				continue;
			// A missing tool is fetched at home, so the home trip stands.
			if (tools[i] != 0 && inv.getHeld(tools[i]) == 0)
				return false;
			missing += resourceShort;
			// Ore, bark and linum tirinum stack: one slot, none when some are held.
			if (!Simulation.isStacked(id))
				slots += resourceShort;
			else if (inv.getHeld(id) == 0 && !done.containsKey(id))
				slots++;
		}
		if (missing <= 0 || free < slots)
			return false;
		Point home = GauntletMap.HOME;
		boolean any = false;
		for (Step step : forecast.steps.subList(first, forecast.steps.size()))
		{
			if (step.kind.isInventoryOnly())
				continue;
			if (step.kind == Simulation.Kind.GATHER || step.kind == Simulation.Kind.SEARCH || !step.room.equals(home))
				return false;
			any = true;
		}
		return any;
	}

	/** Fish from a fishing spot count toward the missing quota; stored fish only once the final pickup is due. */
	private static int countFishNeeded(Source source, Requirements plan, Inventory inv)
	{
		if (source.harvest)
			return plan.getRow(Requirement.FISH).getMissing();
		if (!plan.isReadyForStoredFood())
			return 0;
		return Math.max(0, plan.getFoodTarget() - inv.getHeld(GAUNTLET_RAW_FOOD) - inv.getHeldCookedFood());
	}

	/** The gathering instruction for a node's item. */
	private static String getLabel(int id)
	{
		Requirement resource = Requirement.ofItem(id);
		return resource == null || resource.getGatherLabel() == null ? Requirement.FISH.getGatherLabel() : resource.getGatherLabel();
	}
}
