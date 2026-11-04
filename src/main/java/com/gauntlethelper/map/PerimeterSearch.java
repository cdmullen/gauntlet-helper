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
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.DemiBossTracker;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.WeaponType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import net.runelite.api.Point;

/** Stable adjacent-first perimeter circuit. Shortcuts use observed targets or cheaper guaranteed legs. */
public final class PerimeterSearch
{
	private int direction;
	/** The upgrades missing when the destination was chosen; null before the first choice. */
	private Set<WeaponType> missingUpgrades;
	private Point destination, lastRoom;

	public void reset()
	{
		direction = 0;
		missingUpgrades = null;
		destination = null;
		lastRoom = null;
	}

	/** A forecast copy (direction and held destination) whose selections leave this sweep unchanged. */
	public PerimeterSearch copy()
	{
		PerimeterSearch copy = new PerimeterSearch();
		copy.direction = direction;
		copy.missingUpgrades = missingUpgrades;
		copy.destination = destination;
		copy.lastRoom = lastRoom;
		return copy;
	}

	public int getDirection() { return direction; }
	public Point getDestination() { return destination; }
	public void prefer(Point room, Requirements plan)
	{
		destination = room;
		missingUpgrades = plan.getMissingUpgrades();
	}

	private List<Point> buildRing(GauntletMap map)
	{
		List<Point> ring = new ArrayList<>();
		int last = GauntletMap.SIZE - 1;
		for (int column = 0; column <= last; column++)
			ring.add(map.getRelative(map.getRoom(0, column)));
		for (int row = 1; row <= last; row++)
			ring.add(map.getRelative(map.getRoom(row, last)));
		for (int column = last - 1; column >= 0; column--)
			ring.add(map.getRelative(map.getRoom(last, column)));
		for (int row = last - 1; row > 0; row--)
			ring.add(map.getRelative(map.getRoom(row, 0)));
		return ring;
	}

	private boolean isEligible(GauntletRoom room, DemiBossPlan goal)
	{
		return room != null && room.isPerimeter() && (!room.isDemiSearchChecked() || room.hasLivingDemiBoss()) && goal.rank(room) < Integer.MAX_VALUE;
	}

	private int computeCost(GauntletMap map, Point from, Point to, Predicate<Point> firstStep, Requirements plan)
	{
		List<Point> leg = map.findExplorationPath(from, to, firstStep), back = map.findExplorationPath(to, GauntletMap.HOME, p -> true);
		if (leg.isEmpty() || back.isEmpty())
			return Integer.MAX_VALUE / 4;
		int result = leg.size() + back.size() - 2;
		// Include the cheapest known needed-resource detour, rather than requiring an unrelated home-axis trip.
		for (Requirement resource : Requirement.GATHERED)
			if (plan.getRow(resource).getMissing() > 0)
			{
				int detour = Integer.MAX_VALUE;
				for (GauntletRoom room : map.getRooms())
					if (room.countResourceNodes(resource) > 0)
					{
						Point resourceRoom = map.getRelative(room);
						List<Point> toResource = map.findExplorationPath(to, resourceRoom, q -> true);
						List<Point> resourceToHome = map.findExplorationPath(resourceRoom, GauntletMap.HOME, q -> true);
						if (!toResource.isEmpty() && !resourceToHome.isEmpty())
							detour = Math.min(detour, toResource.size() + resourceToHome.size() - back.size() - 1);
					}
				if (detour != Integer.MAX_VALUE)
					result += Math.max(0, detour);
			}
		return result;
	}

	public GauntletMap.SearchTarget findKnownShortcut(GauntletMap map, Point from, Point nominal, Predicate<Point> firstStep,
		Requirements plan, DemiBossTracker kills)
	{
		DemiBossPlan goal = new DemiBossPlan(plan, kills);
		int baseline = computeCost(map, from, nominal, firstStep, plan);
		GauntletRoom best = null;
		int bestCost = Integer.MAX_VALUE, bestRank = Integer.MAX_VALUE;
		GauntletRoom nominalRoom = map.getRoom(nominal);
		int nominalRank = nominalRoom == null ? Integer.MAX_VALUE : goal.rank(nominalRoom);
		for (GauntletRoom room : map.getRooms())
			if (!map.getRelative(room).equals(from) && goal.isUseful(room))
			{
				int rank = goal.rank(room);
				int roomCost = computeCost(map, from, map.getRelative(room), firstStep, plan);
				// Confirmed native upgrades avoid uncertain extra fights; do not favor an incidental bear over a known
				// orb.
				boolean eligible = roomCost < baseline || rank == 0 || nominalRoom != null && nominalRoom.hasLivingDemiBoss() && rank < nominalRank
						|| beatsUnseenRoom(map, from, room, nominal, firstStep, goal);
				if (eligible && (rank < bestRank || rank == bestRank && roomCost < bestCost))
				{
					best = room;
					bestCost = roomCost;
					bestRank = rank;
				}
			}
		return best == null ? null : map.buildSearchTarget(from, map.getRelative(best), firstStep, goal.getLabel(best));
	}

	/**
	 * A living demi whose drop is wanted is a sure upgrade, so it beats a room with no demi seen that is no nearer, whichever
	 * lies closer to home. A fallback kill, whose own drop isn't wanted, doesn't.
	 */
	private static boolean beatsUnseenRoom(GauntletMap map, Point from, GauntletRoom room, Point nominal, Predicate<Point> firstStep, DemiBossPlan goal)
	{
		GauntletRoom nominalRoom = map.getRoom(nominal);
		if (nominalRoom != null && nominalRoom.hasLivingDemiBoss() || goal.computeWantedDrops(room).isEmpty())
			return false;
		List<Point> leg = map.findExplorationPath(from, map.getRelative(room), firstStep);
		return !leg.isEmpty() && leg.size() <= map.findExplorationPath(from, nominal, firstStep).size();
	}

	public GauntletMap.SearchTarget select(GauntletMap map, Point from, Predicate<Point> firstStep, Requirements plan, DemiBossTracker kills)
	{
		return select(map, from, firstStep, plan, kills, null);
	}

	/**
	 * doorSteps (optional) gives the walking tiles from the player to the door into a neighboring room. On a tied
	 * first direction it picks the nearer door instead of defaulting to clockwise.
	 */
	public GauntletMap.SearchTarget select(GauntletMap map, Point from, Predicate<Point> firstStep,
		Requirements plan, DemiBossTracker kills, ToIntFunction<Point> doorSteps)
	{
		if (!map.isInitialized() || plan.getRow(Requirement.UPGRADES).isComplete())
		{
			reset();
			return null;
		}
		DemiBossPlan goal = new DemiBossPlan(plan, kills);
		List<Point> ring = buildRing(map);
		int ringIndex = ring.indexOf(from);
		Set<WeaponType> missing = plan.getMissingUpgrades();
		if (!missing.equals(missingUpgrades))
		{
			missingUpgrades = missing;
			destination = null;
		}
		if (destination != null && (destination.equals(from) || !isEligible(map.getRoom(destination), goal)))
			destination = null;
		if (destination != null && ringIndex >= 0)
			continueFromNeighbor(map, ring, ringIndex, firstStep, goal);
		if (ringIndex >= 0)
			lastRoom = from;
		if (destination == null)
		{
			if (ringIndex >= 0)
			{
				if (direction == 0)
				{
					int plus = computeNextCost(map, ring, ringIndex, 1, firstStep, goal, plan);
					int minus = computeNextCost(map, ring, ringIndex, -1, firstStep, goal, plan);
					if (minus == plus && doorSteps != null)
					{
						minus = countFirstDoorSteps(map, ring, ringIndex, -1, firstStep, goal, doorSteps);
						plus = countFirstDoorSteps(map, ring, ringIndex, 1, firstStep, goal, doorSteps);
					}
					direction = minus < plus ? -1 : 1;
				}
				destination = findNext(map, ring, ringIndex, direction, firstStep, goal);
				if (destination == null)
				{
					direction = -direction;
					destination = findNext(map, ring, ringIndex, direction, firstStep, goal);
				}
			}
			else
			{
				int best = Integer.MAX_VALUE;
				for (Point ringRoom : ring)
					if (isEligible(map.getRoom(ringRoom), goal))
					{
						int roomCost = computeCost(map, from, ringRoom, firstStep, plan);
						if (roomCost < best)
						{
							best = roomCost;
							destination = ringRoom;
						}
					}
			}
		}
		if (destination == null)
			return null;
		GauntletMap.SearchTarget shortcut = findKnownShortcut(map, from, destination, firstStep, plan, kills);
		if (shortcut != null)
			destination = map.getRelative(shortcut.objective);
		// Adjacent unknown rooms are inspected first; after checked runs, a cheaper guaranteed route can avoid a long
		// empty arc.
		if (ringIndex >= 0 && !map.getRoom(destination).hasLivingDemiBoss() && Math.abs(from.getX() - destination.getX())
				+ Math.abs(from.getY() - destination.getY()) > 1)
		{
			int baseline = computeCost(map, from, destination, firstStep, plan);
			for (Point ringRoom : ring)
				if (map.getRoom(ringRoom).isGuaranteedDemiSpawn() && isEligible(map.getRoom(ringRoom), goal))
				{
					int roomCost = computeCost(map, from, ringRoom, firstStep, plan);
					if (roomCost < baseline)
					{
						baseline = roomCost;
						destination = ringRoom;
					}
				}
		}
		GauntletMap.SearchTarget result = map.buildSearchTarget(from, destination, firstStep, goal.getLabel(map.getRoom(destination)));
		if (result == null)
			destination = null;
		return result;
	}

	/**
	 * A player who walked against the sweep into a checked ring room (the demi room's other neighbor, for bark)
	 * continues the circle from there: the unchecked ring room beyond it replaces a held unknown destination back
	 * through the rooms behind the player.
	 */
	private void continueFromNeighbor(GauntletMap map, List<Point> ring, int ringIndex, Predicate<Point> firstStep, DemiBossPlan goal)
	{
		int held = ring.indexOf(destination), previous = ring.indexOf(lastRoom);
		if (held < 0 || direction == 0 || previous != Math.floorMod(ringIndex + direction, ring.size())
				|| !map.getRoom(ring.get(ringIndex)).isDemiSearchChecked() || map.getRoom(destination).hasLivingDemiBoss())
			return;
		Point beyond = ring.get(Math.floorMod(ringIndex - direction, ring.size()));
		if (isEligible(map.getRoom(beyond), goal) && !map.findExplorationPath(ring.get(ringIndex), beyond, firstStep).isEmpty())
		{
			destination = beyond;
			direction = -direction;
		}
	}

	private Point findNext(GauntletMap map, List<Point> ring, int ringIndex, int dir, Predicate<Point> firstStep, DemiBossPlan goal)
	{
		Point from = ring.get(ringIndex);
		for (int offset = 1; offset < ring.size(); offset++)
		{
			Point candidate = ring.get(Math.floorMod(ringIndex + dir * offset, ring.size()));
			if (isEligible(map.getRoom(candidate), goal) && !map.findExplorationPath(from, candidate, firstStep).isEmpty())
				return candidate;
		}
		return null;
	}

	private int countFirstDoorSteps(GauntletMap map, List<Point> ring, int ringIndex, int dir,
			Predicate<Point> firstStep, DemiBossPlan goal, ToIntFunction<Point> doorSteps)
	{
		Point nextRoom = findNext(map, ring, ringIndex, dir, firstStep, goal);
		List<Point> leg = nextRoom == null ? Collections.emptyList() : map.findExplorationPath(ring.get(ringIndex), nextRoom, firstStep);
		return leg.size() < 2 ? Integer.MAX_VALUE : doorSteps.applyAsInt(leg.get(1));
	}

	private int computeNextCost(GauntletMap map, List<Point> ring, int ringIndex, int dir, Predicate<Point> firstStep, DemiBossPlan goal, Requirements plan)
	{
		Point nextRoom = findNext(map, ring, ringIndex, dir, firstStep, goal);
		if (nextRoom == null)
			return Integer.MAX_VALUE / 4;
		int distance = Math.floorMod((ring.indexOf(nextRoom) - ringIndex) * dir, ring.size());
		return distance + computeCost(map, ring.get(ringIndex), nextRoom, firstStep, plan);
	}
}
