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

import com.gauntlethelper.config.ArmorGoal;
import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.config.WeaponPair;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** What the attempt aims for: the targets, weapon pair and armor goal, plus the potions already crafted. Immutable. */
public final class Goals
{
	private final int foodTarget, leafTarget, craftedPotions;
	private final WeaponPair weaponPair;
	/** The pair's weapons plus those the attempt has committed to. */
	private final Set<WeaponType> selection;
	/** The ArmorUpgrades goal: None (0), T1 (1), T2 (2) or T3 (3). */
	private final int armorGoal;

	public Goals(int foodTarget, int leafTarget, int craftedPotions, WeaponPair pair, Set<WeaponType> selection, int armorGoal)
	{
		this.foodTarget = clampFood(foodTarget);
		this.leafTarget = Math.max(0, Math.min(8, leafTarget));
		this.craftedPotions = Math.max(0, craftedPotions);
		this.weaponPair = pair;
		Set<WeaponType> wanted = EnumSet.noneOf(WeaponType.class);
		wanted.addAll(pair.getWeapons());
		wanted.addAll(selection);
		this.selection = Collections.unmodifiableSet(wanted);
		this.armorGoal = armorGoal;
	}

	/** The same goals with new food and crafted-potion counts; the trip search makes one per forecast state. */
	private Goals(Goals other, int foodTarget, int craftedPotions)
	{
		this.foodTarget = clampFood(foodTarget);
		this.leafTarget = other.leafTarget;
		this.craftedPotions = Math.max(0, craftedPotions);
		this.weaponPair = other.weaponPair;
		this.selection = other.selection;
		this.armorGoal = other.armorGoal;
	}

	private static int clampFood(int target) { return Math.max(0, Math.min(26, target)); }

	/** The Armor goal setting as an upgrade goal; T1 when the saved value can't be read. */
	public static int getArmorGoalSetting(GauntletHelperConfig config)
	{
		ArmorGoal goal = config.armorGoal();
		return goal == null ? ArmorUpgrades.T1 : goal.getGoal();
	}

	/** The Grym leaf setting as the plan uses it: 0 plans no potions (no vials, dust or leaves). */
	public static int getLeafSetting(GauntletHelperConfig config)
	{
		return Math.max(0, config.grymLeafTarget());
	}

	public Goals withFoodTarget(int target) { return new Goals(this, target, craftedPotions); }
	public Goals withCraftedPotions(int crafted) { return new Goals(this, foodTarget, crafted); }

	/** The food target asked for; the plan may cap it to what the final inventory holds (Requirements.getFoodTarget). */
	public int getFoodTarget() { return foodTarget; }
	public int getLeafTarget() { return leafTarget; }
	public int getCraftedPotions() { return craftedPotions; }
	public WeaponPair getWeaponPair() { return weaponPair; }
	public Set<WeaponType> getSelection() { return selection; }
	public int getArmorGoal() { return armorGoal; }
	public boolean isStaffRequired() { return selection.contains(WeaponType.STAFF); }

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof Goals))
			return false;
		Goals that = (Goals) other;
		return foodTarget == that.foodTarget && leafTarget == that.leafTarget && craftedPotions == that.craftedPotions
				&& weaponPair == that.weaponPair && selection.equals(that.selection) && armorGoal == that.armorGoal;
	}

	@Override
	public int hashCode() { return Objects.hash(foodTarget, leafTarget, craftedPotions, weaponPair, selection, armorGoal); }
}
