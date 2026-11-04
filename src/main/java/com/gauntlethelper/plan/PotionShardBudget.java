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

import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T3_HM;

/**
 * Potion spending never consumes shards reserved for the attuned staff and full T1 armor, which is made after the
 * staff from the shards left.
 */
public final class PotionShardBudget
{
	private PotionShardBudget() { }
	public static boolean needsStaff(Requirements plan)
	{ return plan.needsOpeningStaff(); }
	/** @param baseGoal the armor base reserved (ArmorUpgrades.getBaseGoal): T1, or none for the None goal */
	public static int computeGearReserve(Inventory inventory, boolean needStaff, int baseGoal)
	{
		int reserve = needStaff && inventory.getOwned(GAUNTLET_MAGIC_T2_HM) + inventory.getOwned(GAUNTLET_MAGIC_T3_HM) == 0 ? HomeCraftPlan.ATTUNE_SHARDS : 0;
		return reserve + ArmorUpgrades.owned(inventory).computeShards(baseGoal);
	}

	public static boolean canBuyVial(Inventory inventory, boolean needStaff, int baseGoal)
	{
		return inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) >= computeGearReserve(inventory, needStaff, baseGoal) + Recipes.SHARDS_PER_VIAL;
	}

	public static boolean canBuyVial(Requirements plan, Inventory inventory)
	{
		return canBuyVial(inventory, needsStaff(plan), ArmorUpgrades.getBaseGoal(plan.getArmorGoal()));
	}

	public static int getShardsForDust(int missing)
	{
		return ((Math.max(0, missing) + Recipes.GRIND_BATCH - 1) / Recipes.GRIND_BATCH) * Recipes.GRIND_BATCH;
	}
	public static int computeGrindQuantity(Requirements plan, Inventory inventory)
	{
		int reserve = computeGearReserve(inventory, needsStaff(plan), ArmorUpgrades.getBaseGoal(plan.getArmorGoal()))
				+ plan.getRow(Requirement.VIALS).getMissing() * Recipes.SHARDS_PER_VIAL;
		int shards = inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM);
		int quantity = Math.min(getShardsForDust(plan.getRow(Requirement.DUST).getMissing()),
				Math.max(0, shards - reserve) / Recipes.GRIND_BATCH * Recipes.GRIND_BATCH);
		if (quantity > 0 && inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) == 0 && inventory.getFreeSlots() == 0 && quantity < shards)
			return 0;
		return quantity;
	}
}
