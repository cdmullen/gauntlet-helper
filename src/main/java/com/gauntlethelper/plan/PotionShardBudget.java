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

import com.gauntlethelper.game.Tier;
import java.util.List;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T3_HM;

/**
 * Potion spending never consumes shards reserved for the attuned staff, nor the attuned tier of a selected weapon whose
 * upgrade component is held. With the T1 and T2 Armor goals the vials and dust come before the T1 armor, which takes the
 * shards left; with T3 and None the full T1 armor is reserved ahead of them too.
 */
public final class PotionShardBudget
{
	private PotionShardBudget() { }
	public static boolean needsStaff(Requirements plan)
	{ return plan.needsOpeningStaff(); }

	/** The Armor goals whose vials and dust come before the T1 armor: T1 and T2. */
	public static boolean isVialsBeforeArmor(int armorGoal)
	{
		return armorGoal == ArmorUpgrades.T1 || armorGoal == ArmorUpgrades.T2;
	}

	/**
	 * The attuned staff while it is still needed; with the T3 and None goals also the missing T1 armor.
	 *
	 * @param armorGoal the Armor goal (Requirements.getArmorGoal)
	 */
	public static int computeGearReserve(Inventory inventory, boolean needStaff, int armorGoal)
	{
		int reserve = needStaff && inventory.getOwned(GAUNTLET_MAGIC_T2_HM) + inventory.getOwned(GAUNTLET_MAGIC_T3_HM) == 0 ? HomeCraftPlan.ATTUNE_SHARDS : 0;
		if (isVialsBeforeArmor(armorGoal))
			return reserve;
		return reserve + ArmorUpgrades.owned(inventory).computeShards(ArmorUpgrades.getBaseGoal(armorGoal));
	}

	/**
	 * The attuned-tier shards of each selected weapon whose upgrade component is held but that owns neither the attuned
	 * nor the perfected tier: the bowl finishes that weapon before vials. The opening staff's are in the gear reserve.
	 */
	public static int computeWeaponReserve(Inventory inventory, boolean needStaff, List<WeaponType> weaponTypes)
	{
		int reserve = 0;
		for (WeaponType type : weaponTypes)
			if (inventory.getHeld(type.getComponent()) > 0
					&& inventory.getOwned(type.getItem(Tier.ATTUNED)) + inventory.getOwned(type.getPerfected()) == 0
					&& !(type == WeaponType.STAFF && needStaff))
				reserve += HomeCraftPlan.ATTUNE_SHARDS;
		return reserve;
	}

	/** One vial's shards are left over after the gear and weapon reserves; Requirements and HomeCraftPlan both ask this. */
	public static boolean canBuyVial(Inventory inventory, boolean needStaff, int armorGoal, List<WeaponType> weaponTypes)
	{
		return inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) >= computeGearReserve(inventory, needStaff, armorGoal)
				+ computeWeaponReserve(inventory, needStaff, weaponTypes) + Recipes.SHARDS_PER_VIAL;
	}

	public static boolean canBuyVial(Requirements plan, Inventory inventory)
	{
		return canBuyVial(inventory, needsStaff(plan), plan.getArmorGoal(), plan.getDesiredWeaponTypes());
	}

	/** The shards for the dust the potions still to craft need, in whole grind batches; none with no potion left to craft. */
	public static int computeDustReserve(Requirements plan)
	{
		return getShardsForDust(plan.getRow(Requirement.DUST).getMissing());
	}

	/**
	 * Shards the T1 armor leaves untouched: with the T1 and T2 goals the attuned staff while still needed, the missing
	 * vials and the dust still to grind; nothing with T3 or None, whose armor comes before them.
	 */
	public static int computeArmorHoldback(int armorGoal, boolean staffShort, int missingVials, int dustShards)
	{
		if (!isVialsBeforeArmor(armorGoal))
			return 0;
		return (staffShort ? HomeCraftPlan.ATTUNE_SHARDS : 0) + Math.max(0, missingVials) * Recipes.SHARDS_PER_VIAL + dustShards;
	}

	public static int computeArmorHoldback(Requirements plan, Inventory inventory)
	{
		return computeArmorHoldback(plan.getArmorGoal(), computeGearReserve(inventory, needsStaff(plan), plan.getArmorGoal()) > 0,
				plan.getRow(Requirement.VIALS).getMissing(), computeDustReserve(plan));
	}

	public static int getShardsForDust(int missing)
	{
		return ((Math.max(0, missing) + Recipes.GRIND_BATCH - 1) / Recipes.GRIND_BATCH) * Recipes.GRIND_BATCH;
	}
	public static int computeGrindQuantity(Requirements plan, Inventory inventory)
	{
		int reserve = computeGearReserve(inventory, needsStaff(plan), plan.getArmorGoal())
				+ plan.getRow(Requirement.VIALS).getMissing() * Recipes.SHARDS_PER_VIAL;
		int shards = inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM);
		int quantity = Math.min(getShardsForDust(plan.getRow(Requirement.DUST).getMissing()),
				Math.max(0, shards - reserve) / Recipes.GRIND_BATCH * Recipes.GRIND_BATCH);
		if (quantity > 0 && inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) == 0 && inventory.getFreeSlots() == 0 && quantity < shards)
			return 0;
		return quantity;
	}
}
