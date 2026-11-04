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

import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.plan.ArmorUpgrades;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import net.runelite.api.gameval.ItemID;

/** Ordinary prep NPCs only. */
public final class EnemyRules
{
	private EnemyRules() { }

	/** A rat, spider or bat. */
	public static boolean isOrdinary(int id)
	{
		EnemyType type = EnemyType.fromNpcId(id);
		return type != null && type.isWeak();
	}

	/** A unicorn, scorpion or wolf. */
	public static boolean isAvoided(int id)
	{
		EnemyType type = EnemyType.fromNpcId(id);
		return type != null && type.isLarge();
	}

	/** Guaranteed minimum shard drop of a unicorn, scorpion or wolf (80-100, OSRS Wiki, after the 22 July 2026 update). */
	public static final int LARGE_KILL_SHARDS = 80;

	/** A shortfall above one typical small kill (about 25 shards) is worth a unicorn, scorpion or wolf. */
	public static final int LARGE_KILL_THRESHOLD = 25;

	/**
	 * Unicorns/scorpions/wolves become shard targets only once the T1 base is finished (a piece in every armor slot,
	 * none with the None goal, + both perfected weapons) and the remaining shard shortfall is larger than one
	 * small kill. A higher armor goal's upgrades are paid for by these kills, so they don't wait for the upgrades. With a
	 * goal above T1 (T2 or T3) they also unlock once the attuned staff is owned and the shortfall the demis won't cover
	 * is more than one large kill's minimum.
	 */
	public static boolean areLargeKillsAllowed(Requirements plan)
	{
		int missing = plan.getRow(Requirement.SHARDS).getMissing();
		if (plan.findBaseArmorSteps().isEmpty() && plan.getRow(Requirement.WEAPONS).isComplete()
				&& missing > LARGE_KILL_THRESHOLD)
			return true;
		return plan.getArmorGoal() > ArmorUpgrades.T1 && plan.hasAttunedStaff()
				&& missing - predictDemiShards(plan) > LARGE_KILL_SHARDS;
	}

	/** Kill objective for the current plan: always rats/bats/spiders, plus the larger three when allowed. */
	public static boolean isEligible(int id, Requirements plan)
	{
		return isOrdinary(id) || plan != null && isAvoided(id) && areLargeKillsAllowed(plan);
	}

	/**
	 * Conservative forecast (each demi's guaranteed minimum is 50 shards). Re-evaluated from held upgrades; it never
	 * increments inventory.
	 */
	public static int predictDemiShards(Requirements plan)
	{
		return plan.getRow(Requirement.UPGRADES).getMissing() * 50;
	}

	/**
	 * Every demi-boss always drops a weapon frame (plus 50-60 shards), subject to the
	 * three-frame cap. Forecast only: it never credits a frame before one is held.
	 */
	public static int predictDemiFrames(Requirements plan)
	{
		return plan.canReceiveFrameDrop() ? plan.getRow(Requirement.UPGRADES).getMissing() : 0;
	}

	public static boolean isCoveredByDemis(Requirements plan, boolean demiTrip)
	{
		int frames = plan.getRow(Requirement.FRAMES).getMissing();
		return demiTrip && predictDemiShards(plan) > 0
				&& plan.getRow(Requirement.SHARDS).getMissing() <= predictDemiShards(plan)
				&& (frames == 0 || !plan.canReceiveFrameDrop() || frames <= predictDemiFrames(plan));
	}

	/**
	 * Corrupt fishing spots, deposits, phren roots, grym roots and linum plants give 25-30 shards on about 28% of
	 * gathers, about 8 per catch. Once every
	 * demi upgrade is held, a small kill adds little, so a shard-only kill waits until the needed gathering is done.
	 * Frames still come only from kills.
	 */
	public static boolean isGatheringFirst(Requirements plan)
	{
		if (!plan.getRow(Requirement.UPGRADES).isComplete())
			return false;
		for (Requirement resource : Requirement.GATHERED)
			if (!plan.getRow(resource).isComplete())
				return true;
		return false;
	}

	public static String getReason(Requirements plan, Inventory inventory)
	{
		return getReason(plan, inventory, false);
	}

	/**
	 * With gatheringFirst set (see isGatheringFirst), shards alone are not a reason to kill; a missing frame still is.
	 */
	public static String getReason(Requirements plan, Inventory inventory, boolean gatheringFirst)
	{
		boolean needsFrames = !plan.getRow(Requirement.FRAMES).isComplete() && plan.canReceiveFrameDrop() && inventory.getFreeSlots() > 0;
		boolean needsShards = !gatheringFirst && !plan.getRow(Requirement.SHARDS).isComplete()
				&& (inventory.getFreeSlots() > 0
				|| inventory.getHeld(ItemID.GAUNTLET_CRYSTAL_SHARD_HM) > 0);
		if (needsFrames && needsShards)
			return "frames and shards";
		if (needsFrames)
			return "a weapon frame";
		if (needsShards)
			return "shards";
		return null;
	}
}
