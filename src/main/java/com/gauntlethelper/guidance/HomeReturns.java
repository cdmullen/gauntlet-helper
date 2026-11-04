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

import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.InventoryAdvice;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Point;
import net.runelite.api.gameval.ItemID;

/**
 * The task steps that head home or back for seen loot: the full-inventory, time-low, stored-fish and observed-loot returns, and the
 * last resort when nothing else has a task but home has work.
 * Each decides from the TickContext; TaskPriorities.publish does the walking.
 */
final class HomeReturns
{
	private final FullReturn fullReturn = new FullReturn();

	/** What the latest full-inventory trip home planned, for the Next line. The space return latch is AttemptLatches's. */
	private static final class FullReturn
	{
		/** The trip home will cook and store before the bowl (isStorageFirst). */
		private boolean storageFirst;

		void reset() { storageFirst = false; }

		void plan(boolean first) { storageFirst = first; }

		boolean isStorageFirst() { return storageFirst; }
	}

	void reset()
	{
		fullReturn.reset();
	}

	boolean isFullReturnStorageFirst() { return fullReturn.isStorageFirst(); }

	/**
	 * Whether a home visit with this plan goes to the range before the bowl (HomeSpace.prepareHomeSpace), so a trip home
	 * points at the station it will arrive at.
	 */
	static boolean isStorageFirst(Requirements plan, HomeVisit visit)
	{
		if (plan.isReady() || plan.isInventoryAction() && !plan.needsInventorySpace())
			return false;
		InventoryAdvice advice = new InventoryAdvice(plan, plan.getInventory(), true);
		return advice.shouldStoreFish() && isWaitingPastDropCues(plan, visit.previewCrafts(), advice);
	}

	/**
	 * Crafts wait for slots that the drop cues won't free, so only storing fish makes the room. Doing what a cue asks
	 * then never changes the station, and a player who leaves the cued item held still gets the crafts that fit first.
	 */
	static boolean isWaitingPastDropCues(Requirements plan, HomeCraftPlan home, InventoryAdvice advice)
	{
		return home.countWaitingForSpace() > 0 && plan.countCraftsWaitingAfterDrops(advice.getFinishedTools()) > 0;
	}

	/**
	 * The full-inventory trip home names only the home jobs this inventory has: craft when a recipe is available, store
	 * fish when fish are held.
	 */
	static String buildFullReturnInstruction(Requirements plan, HomeVisit visit)
	{
		if (visit.canCraft() && isStorageFirst(plan, visit))
			return "Inventory full: return home to cook and store fish, then craft";
		return "Inventory full: return home to " + describeHomeJobs(visit.canCraft(), plan.hasFish());
	}

	private static String describeHomeJobs(boolean craft, boolean fish)
	{
		if (craft && fish)
			return "craft and store fish";
		if (craft)
			return "craft supplies";
		if (fish)
			return "store fish";
		return "make space";
	}

	/**
	 * A full inventory (or a latched space return) away from home goes home to make space. It claims even with no way home
	 * yet: the instruction stays up with the task cleared until a route appears.
	 */
	Decision returnForFullInventory(TickContext context)
	{
		Requirements plan = context.plan;
		if (!context.isInventorySpaceReturn() && context.inv.getFreeSlots() != 0 || context.isAtHome() || plan.isReady() || plan.isFullBagHarmless())
			return null;
		String instruction = buildFullReturnInstruction(plan, context.getHomeVisit());
		fullReturn.plan(context.canCraft() && isStorageFirst(plan, context.getHomeVisit()));
		return Decision.walkTo(GauntletMap.HOME, instruction).withGuidancePaused().withSpaceReturnLatched();
	}

	/** Low time ends the steps here; away from home with a way there, the task is that way. */
	Decision returnForLowTime(TickContext context)
	{
		if (!context.urgent)
			return null;
		if (context.isAtHome() || !context.canWalkTo(GauntletMap.HOME))
			return Decision.CLAIM_ONLY;
		if (context.retrieveFood())
			return Decision.walkTo(GauntletMap.HOME, "Time low: return home to retrieve final food");
		if (context.canCraft())
			return Decision.walkTo(GauntletMap.HOME, "Time low: return home to craft and finish supplies");
		return Decision.walkTo(GauntletMap.HOME, "Time low: return home to finish supplies");
	}

	Decision returnForStoredFish(TickContext context)
	{
		if (!context.retrieveFood() || context.loot.countHomeFood() <= 0 || context.isAtHome() || !context.canWalkTo(GauntletMap.HOME))
			return null;
		return Decision.walkTo(GauntletMap.HOME, "Return home to retrieve stored fish");
	}

	/** The nearest other room with needed ground loot already seen, while a slot is free. */
	Decision returnForObservedLoot(TickContext context)
	{
		if (context.urgent || context.inv.getFreeSlots() <= 0 || context.isAtHome() && context.canCraft())
			return null;
		Point destination = findNearestLootRoom(context);
		if (destination == null || !context.canWalkTo(destination))
			return null;
		return Decision.walkTo(destination, "Return to observed needed ground loot");
	}

	/**
	 * The last step: nothing has a task, the upgrades and gathered resources are in, and home has work. The trip forecast
	 * stops offering a walk home longer than its time budget, and the time-low return starts only at the warning, so
	 * without this the player has no target in between. A resource still short keeps its door or search instead, until
	 * the time budget wouldn't cover even the rooms home: then no search is left, and home is the next step whatever waits there.
	 */
	Decision returnForHomeWork(TickContext context)
	{
		if (context.hasTask() || context.isAtHome() || context.opening.isActive() || context.isOutwardTrip() || !context.isOuterRoomChecked())
			return null;
		Requirements plan = context.plan;
		if (!plan.getRow(Requirement.UPGRADES).isComplete() || !context.canWalkTo(GauntletMap.HOME))
			return null;
		boolean work = context.canCraft() || context.inv.getHeld(ItemID.GAUNTLET_RAW_FOOD) > 0;
		boolean gatherShort = OptimizedTrip.isGatherShortAfterDemis(plan, Collections.emptyMap());
		if (gatherShort ? !isTourBudgetSpent(context) : !work)
			return null;
		return Decision.walkTo(GauntletMap.HOME, "Return home to finish preparation");
	}

	/** The time budget for touring doesn't cover the rooms between here and home. */
	private static boolean isTourBudgetSpent(TickContext context)
	{
		Point current = context.opening.getRoomAt(context.getHere());
		int rooms = context.map.findPath(current, GauntletMap.HOME, context.findReachableRoomSteps(current)::contains).size() - 1;
		return context.getTourBudget() <= rooms;
	}

	/** The nearest other room, by the room path, with a needed pile the player may pick up; null when none. */
	private static Point findNearestLootRoom(TickContext context)
	{
		Point current = context.opening.getRoomAt(context.getHere());
		Point destination = null;
		int length = Integer.MAX_VALUE;
		for (GroundLoot.Entry e : context.loot.getEntries())
		{
			if (!context.loot.isNeeded(e, context.plan, context.inv, context.retrieveFood()) || !context.isLootAllowed(e)
				|| !context.loot.isWorthRoomTrip(e))
				continue;
			Point room = context.opening.getRoomAt(e.point);
			if (room.equals(current))
				continue;
			List<Point> route = context.map.findPath(current, room);
			if (!route.isEmpty() && route.size() < length)
			{
				length = route.size();
				destination = room;
			}
		}
		return destination;
	}
}
