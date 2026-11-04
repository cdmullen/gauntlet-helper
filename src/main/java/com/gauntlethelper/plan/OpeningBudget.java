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
import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.map.OpeningRoute;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Point;

import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_GENERIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RAW_FOOD;

/** First-home funding and a conservative, quantity-based opening food allowance. */
public final class OpeningBudget
{
	private OpeningBudget() { }
	public static int computeShardTarget(Requirements plan, GauntletMap map, OpeningRoute opening)
	{
		int[] have = {plan.getRow(Requirement.ORE).have, plan.getRow(Requirement.BARK).have, plan.getRow(Requirement.LINUM_TIRINUM).have};
		Requirement[] materials = {Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM};
		for (Point at : opening.getRemainingRooms())
		{
			GauntletRoom room = map.getRoom(at);
			if (room == null)
				continue;
			// Observed node yield is a forecast.
			for (int i = 0; i < 3; i++)
				have[i] += room.countResourceNodes(materials[i]) * ObjectKind.ORE.getNominalYield();
		}
		// The T1 armor crafts, cheapest first, that the held and forecast materials cover; upgrades are never funded.
		int armorMaterials = Math.min(have[0], Math.min(have[1], have[2]));
		int armorShards = 0;
		for (ArmorUpgrades.Step step : plan.findBaseArmorSteps())
		{
			if (step.materials > armorMaterials)
				break;
			armorMaterials -= step.materials;
			armorShards += step.shards;
		}
		// The loop's own kills still fund the attuned staff for the first home visit, made there before the armor;
		// when the loop falls short a basic staff starts the demi hunt.
		return (plan.needsOpeningStaff() && !plan.hasAttunedStaff() ? HomeCraftPlan.ATTUNE_SHARDS : 0) + armorShards;
	}

	public static String getEnemyReason(Requirements plan, Inventory inv, int shards)
	{
		boolean needsFrame = plan.needsOpeningStaff() && !plan.hasStaff() && inv.getHeld(GAUNTLET_GENERIC_COMPONENT_HM) == 0
				&& plan.canReceiveFrameDrop() && inv.getFreeSlots() > 0;
		boolean needsFunds = inv.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) < shards && (inv.getFreeSlots() > 0 || inv.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) > 0);
		if (needsFrame && needsFunds)
			return "the staff frame and first-home shards";
		if (needsFrame)
			return "the staff frame";
		if (needsFunds)
			return "first-home shards";
		return null;
	}

	/**
	 * With the map and loop known, an ingredient is reserved only up to what the loop's rooms left (the current one
	 * included) can still give: once every one of them has been seen, a material none of them offers is gathered after
	 * the home visit, so its slots go to fish and the pond isn't left with fish still needed. While any of those still
	 * arrives the loop keeps one slot free: the full-inventory return fires at 28, even when the rest would stack. A loop
	 * room still to enter keeps the last slot from fish too, or the return would leave the loop unfinished; the last room
	 * may fill it, since home is its exit anyway.
	 */
	public static int computeFishAllowance(Requirements plan, int firstHomeShards, GauntletMap map, OpeningRoute opening)
	{
		int allowance = findFishCapacity(plan, firstHomeShards, map, opening);
		return hasLoopRoomAhead(opening) ? Math.min(allowance, Math.max(0, plan.getInventory().getFreeSlots() - 1)) : allowance;
	}

	/**
	 * While a demi upgrade is missing, fish take only the slots nothing else on the hunt needs: each missing upgrade, one
	 * for a missing frame, a slot per short Grym leaf, one for a material or shard stack not yet started, and the last slot, since a full inventory
	 * goes home. What doesn't fit waits until after the hunt; the quota stays. At home the fish about to be stored already
	 * count as gone, so the departure's Next doesn't change once they are dropped.
	 */
	public static int computeHuntFishAllowance(Requirements plan, boolean atHome)
	{
		Inventory inv = plan.getInventory();
		int free = inv.getFreeSlots();
		if (atHome)
		{
			Simulation.Capacity capacity = Simulation.computeCapacity(plan, inv, true, plan.getStoredFood(), 0);
			free += Simulation.countFishToStore(plan, inv, plan.getStoredFood(), capacity);
		}
		int reserved = 1 + plan.getRow(Requirement.UPGRADES).getMissing() + plan.getRow(Requirement.GRYM_LEAVES).getMissing();
		if (plan.getRow(Requirement.FRAMES).getMissing() > 0)
			reserved++;
		for (Requirement stack : new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM, Requirement.SHARDS})
			if (plan.getRow(stack).getMissing() > 0 && inv.getHeld(stack.getItemId()) == 0)
				reserved++;
		return Math.max(0, free - reserved);
	}

	/** The largest extra fish that fits the projected opening and home-action peaks. */
	private static int findFishCapacity(Requirements plan, int firstHomeShards, GauntletMap map, OpeningRoute opening)
	{
		int missing = plan.getRow(Requirement.FISH).getMissing();
		for (int extra = missing; extra >= 0; extra--)
		{
			Simulation sim = new Simulation(plan, plan.getInventory(), plan.getStoredFood(), 0);
			sim.forecastAcquire(GAUNTLET_RAW_FOOD, extra);
			int gathering = 0;
			// Reserve actual remaining opening ingredients even before their rooms have been seen.
			for (Requirement resource : new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM, Requirement.GRYM_LEAVES})
			{
				int quantity = Math.min(plan.getRow(resource).getMissing(), countOpeningSupply(resource, map, opening));
				sim.forecastAcquire(resource.getItemId(), quantity);
				gathering += quantity;
			}
			if (plan.needsOpeningStaff() && !plan.hasStaff() && plan.getInventory().getHeld(GAUNTLET_GENERIC_COMPONENT_HM) == 0)
			{
				sim.forecastAcquire(GAUNTLET_GENERIC_COMPONENT_HM, 1);
				gathering++;
			}
			int shards = Math.max(0, firstHomeShards - plan.getInventory().getHeld(GAUNTLET_CRYSTAL_SHARD_HM));
			sim.forecastAcquire(GAUNTLET_CRYSTAL_SHARD_HM, shards);
			gathering += shards;
			int limit = gathering > 0 ? Inventory.SIZE - 1 : Inventory.SIZE;
			sim.normalize(false, false, false);
			if (sim.getPeakSlots() > limit)
				continue;
			sim.normalize(true, true, false);
			if (sim.getPeakSlots() > limit)
				continue;
			Simulation.Capacity trip = Simulation.computeCapacity(sim.plan(), sim.snapshot(), true, sim.getStoredRaw(), sim.getStoredCooked());
			// Storage is at home. Keep real non-food acquisitions feasible.
			int afterStorage = trip.peakSlots - trip.rawToStore - trip.cookedToStore;
			if (afterStorage <= Inventory.SIZE || extra == 0)
				return extra;
		}
		return 0;
	}

	private static boolean hasLoopRoomAhead(OpeningRoute opening)
	{
		return opening != null && opening.isActive() && opening.getStep() < 5;
	}

	/**
	 * Whether the loop's rooms left, the current one included, hold nothing but fish for this attempt. An unseen room
	 * counts as holding whatever is still short. Materials and Grym leaves come from nodes, the frame and first-home
	 * shards from weak enemies. Fish never hold the loop open: they are gathered on the later roam.
	 */
	public static boolean isOnlyFishLeftInLoop(Requirements plan, GauntletMap map, OpeningRoute opening)
	{
		for (Requirement resource : new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM, Requirement.GRYM_LEAVES})
			if (plan.getRow(resource).getMissing() > 0 && countOpeningSupply(resource, map, opening) > 0)
				return false;
		return !needsOpeningKills(plan, computeShardTarget(plan, map, opening)) || !hasOpeningEnemy(map, opening);
	}

	/**
	 * The loop's next stop is its home visit: a room has been entered, its rooms left hold nothing needed and no
	 * opening kill is pending, so the loop's next door only leads home. A pond in the room the player stands in is
	 * fished first then, up to the opening fish allowance, rather than left for the roam and fetched back across the map.
	 */
	public static boolean isHomeVisitNext(Requirements plan, GauntletMap map, OpeningRoute opening)
	{
		return opening != null && opening.isActive() && opening.getStep() > 0 && isOnlyFishLeftInLoop(plan, map, opening)
				&& !needsOpeningKills(plan, computeShardTarget(plan, map, opening));
	}

	private static boolean needsOpeningKills(Requirements plan, int firstHomeShards)
	{
		Inventory inv = plan.getInventory();
		boolean needsFrame = plan.needsOpeningStaff() && !plan.hasStaff() && inv.getHeld(GAUNTLET_GENERIC_COMPONENT_HM) == 0 && plan.canReceiveFrameDrop();
		return needsFrame || inv.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) < firstHomeShards;
	}

	/** A living weak enemy in the loop's rooms left, or one of them still unseen. */
	private static boolean hasOpeningEnemy(GauntletMap map, OpeningRoute opening)
	{
		for (Point at : opening.getRemainingRooms())
		{
			GauntletRoom room = map.getRoom(at);
			if (room == null || !room.hasObservedContents())
				return true;
			for (GauntletRoom.Enemy enemy : room.getEnemies())
				if (enemy.isAlive() && enemy.getType().getCategory() == EnemyType.Category.WEAK)
					return true;
		}
		return false;
	}

	/** Nominal yield of the loop's rooms left for resource; unlimited while any of them is unseen. */
	static int countOpeningSupply(Requirement resource, GauntletMap map, OpeningRoute opening)
	{
		if (map == null || opening == null || !opening.isActive())
			return Integer.MAX_VALUE;
		ObjectKind kind = ObjectKind.valueOf(resource.name());
		int supply = 0;
		for (Point at : opening.getRemainingRooms())
		{
			GauntletRoom room = map.getRoom(at);
			if (room == null || !room.hasObservedContents())
				return Integer.MAX_VALUE;
			supply += room.countResourceNodes(resource) * kind.getNominalYield();
		}
		return supply;
	}

	/** @param crafts the plan's next recipes, the home visit's when it has planned them */
	public static boolean hasHomeWork(Requirements plan, List<Requirements.Craft> crafts)
	{
		HomeCraftPlan craft = new HomeCraftPlan(plan.getInventory(), plan.getRow(Requirement.VIALS).getMissing(), plan.needsOpeningStaff(),
				Collections.emptyList(), Collections.emptySet(), Collections.emptyList(), 0, ArmorUpgrades.getBaseGoal(plan.getArmorGoal()));
		Inventory inv = plan.getInventory();
		boolean weaponUpgrade = false;
		for (Requirements.Craft recipe : crafts)
			for (WeaponType type : plan.getDesiredWeaponTypes())
				weaponUpgrade |= recipe.itemId == type.getPerfected();
		return weaponUpgrade || craft.getNextCraft() != null || craft.getBlocked() != null || plan.needsWater()
				|| new InventoryAdvice(plan, inv, true).shouldStoreFish();
	}
}
