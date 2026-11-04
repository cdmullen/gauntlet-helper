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

import com.gauntlethelper.game.ArmorSlot;
import com.gauntlethelper.game.Tier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CHESTPLATE_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CHESTPLATE_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CHESTPLATE_T3_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HELMET_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HELMET_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HELMET_T3_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PLATELEGS_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PLATELEGS_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PLATELEGS_T3_HM;

/**
 * Every armor-tier calculation: the pieces' items, each slot's owned tier and the bowl crafts between them. An armor
 * goal is a target tier in every slot (T1 basic, T2 attuned, T3 perfected), named by its tier number (1, 2, 3).
 * Its steps are each slot's crafts from the owned tier up to the target, cheapest first. The Armor goal setting
 * (ArmorGoal) picks None (no armor, goal 0), T1, T2 or T3. A goal's number is its tier, not a step count: getStepCount
 * gives the steps a goal takes (3, 6, 9), and the tiers (Tier's BASIC, ATTUNED, PERFECTED) are one lower.
 */
public final class ArmorUpgrades
{
	/** No armor made: a goal with no steps and no T1 base. */
	public static final int NO_ARMOR = 0;

	/** The full T1 goal: a basic (or better) piece in every slot. */
	public static final int T1 = 1;

	/** The full T2 goal: an attuned (or better) helm, body and legs. */
	public static final int T2 = 2;

	/** Every slot perfected: every step. */
	public static final int T3 = 3;

	/** Each slot's pieces, by ArmorSlot then Tier ordinal. */
	private static final int[][] ITEMS = {
			{GAUNTLET_HELMET_T1_HM, GAUNTLET_HELMET_T2_HM, GAUNTLET_HELMET_T3_HM},
			{GAUNTLET_CHESTPLATE_T1_HM, GAUNTLET_CHESTPLATE_T2_HM, GAUNTLET_CHESTPLATE_T3_HM},
			{GAUNTLET_PLATELEGS_T1_HM, GAUNTLET_PLATELEGS_T2_HM, GAUNTLET_PLATELEGS_T3_HM}};

	/** One bowl craft in the upgrade order: the piece it makes, its shards and how many of each ore, bark and linum tirinum. */
	public static final class Step
	{
		public final ArmorSlot slot;
		public final Tier tier;
		public final int itemId;
		public final int shards;
		public final int materials;
		public final String label;

		Step(ArmorSlot slot, Tier tier, int shards, int materials, String label)
		{
			this.slot = slot;
			this.tier = tier;
			this.itemId = getItem(slot, tier);
			this.shards = shards;
			this.materials = materials;
			this.label = label;
		}
	}

	/** Every craft by tier, cheapest first within a tier, so a short run makes the cheap steps first. */
	private static final Step[] STEPS = {
			new Step(ArmorSlot.HELM, Tier.BASIC, 50, 1, "Basic helm"),
			new Step(ArmorSlot.BODY, Tier.BASIC, 50, 1, "Basic body"),
			new Step(ArmorSlot.LEGS, Tier.BASIC, 50, 1, "Basic legs"),
			new Step(ArmorSlot.HELM, Tier.ATTUNED, 50, 1, "Attuned helm"),
			new Step(ArmorSlot.LEGS, Tier.ATTUNED, 50, 1, "Attuned legs"),
			new Step(ArmorSlot.BODY, Tier.ATTUNED, 100, 2, "Attuned body"),
			new Step(ArmorSlot.HELM, Tier.PERFECTED, 100, 2, "Perfected helm"),
			new Step(ArmorSlot.LEGS, Tier.PERFECTED, 100, 2, "Perfected legs"),
			new Step(ArmorSlot.BODY, Tier.PERFECTED, 100, 2, "Perfected body")};

	/** Each slot's highest owned tier; a slot with no piece is absent. */
	private final Map<ArmorSlot, Tier> tiers;

	private ArmorUpgrades(Map<ArmorSlot, Tier> tiers) { this.tiers = tiers; }

	/** Each slot's highest owned tier, from a count of owned items (held plus worn, or worn only). */
	public static ArmorUpgrades of(IntUnaryOperator owned)
	{
		Map<ArmorSlot, Tier> tiers = new EnumMap<>(ArmorSlot.class);
		for (ArmorSlot slot : ArmorSlot.values())
			for (Tier tier : Tier.values())
				if (owned.applyAsInt(getItem(slot, tier)) > 0)
					tiers.put(slot, tier);
		return new ArmorUpgrades(tiers);
	}

	/** A slot's piece of a tier. */
	public static int getItem(ArmorSlot slot, Tier tier) { return ITEMS[slot.ordinal()][tier.ordinal()]; }

	public static ArmorUpgrades owned(Inventory inventory) { return of(inventory::getOwned); }

	public static ArmorUpgrades worn(Inventory inventory) { return of(inventory::getEquipped); }

	/** The same holdings plus a basic piece in each slot whose crafted piece lies on the ground. */
	public ArmorUpgrades withGround(Set<ArmorSlot> slots)
	{
		Map<ArmorSlot, Tier> copy = new EnumMap<>(ArmorSlot.class);
		copy.putAll(tiers);
		for (ArmorSlot slot : slots)
			copy.putIfAbsent(slot, Tier.BASIC);
		return new ArmorUpgrades(copy);
	}

	/** The armor slot of any tier's piece, or null for anything else. */
	public static ArmorSlot getSlot(int id)
	{
		for (ArmorSlot slot : ArmorSlot.values())
			for (int piece : ITEMS[slot.ordinal()])
				if (piece == id)
					return slot;
		return null;
	}

	/** The tier of any armor piece, or null for anything else. */
	public static Tier getPieceTier(int id)
	{
		for (int[] pieces : ITEMS)
			for (Tier tier : Tier.values())
				if (pieces[tier.ordinal()] == id)
					return tier;
		return null;
	}

	/** The base every upgrade builds on: T1 for a goal above it, none for the None goal. */
	public static int getBaseGoal(int goal) { return Math.min(goal, T1); }

	/** The tier a goal targets in every slot; null for the None goal. */
	public static Tier getTargetTier(int goal) { return goal == NO_ARMOR ? null : Tier.values()[goal - 1]; }

	/** The number of upgrade steps a goal takes from nothing: one per slot per tier. */
	public static int getStepCount(int goal) { return ArmorSlot.values().length * goal; }

	/** The goal's step that makes this piece, or null when the goal never crafts it. */
	public static Step findStep(int itemId, int goal)
	{
		for (Step step : STEPS)
			if (step.itemId == itemId && !step.tier.isAbove(getTargetTier(goal)))
				return step;
		return null;
	}

	/** The slot's highest owned tier, or null with no piece. */
	public Tier getTier(ArmorSlot slot) { return tiers.get(slot); }

	/** Whether a step raises a slot above basic, which the bowl makes after the vials. */
	public static boolean isUpgrade(Step step) { return step.tier != Tier.BASIC; }

	public boolean hasPiece(ArmorSlot slot) { return tiers.containsKey(slot); }

	/** Slots with a piece of any tier. */
	public int countPieces() { return tiers.size(); }

	/**
	 * The goal's steps not yet reached, cheapest first: each slot's crafts from its owned tier up to the target. A higher
	 * tier owned counts every lower step in its slot, and a piece above the target counts its slot as done.
	 */
	public List<Step> findSteps(int goal)
	{
		Tier target = getTargetTier(goal);
		List<Step> left = new ArrayList<>();
		for (Step step : STEPS)
			if (!step.tier.isAbove(target) && step.tier.isAbove(tiers.get(step.slot)))
				left.add(step);
		return Collections.unmodifiableList(left);
	}

	/** The goal's steps already reached: the owned tiers with each slot capped at the target. */
	public int countDone(int goal) { return getStepCount(goal) - findSteps(goal).size(); }

	public boolean isComplete(int goal) { return findSteps(goal).isEmpty(); }

	/** The goal's next craft, or null when it is complete. */
	public Step findNextStep(int goal)
	{
		List<Step> left = findSteps(goal);
		return left.isEmpty() ? null : left.get(0);
	}

	public int computeShards(int goal) { return sumShards(findSteps(goal)); }

	/** Ore, bark and linum tirinum each (every step takes the same number of all three). */
	public int computeMaterials(int goal) { return sumMaterials(findSteps(goal)); }

	public static int sumShards(List<Step> steps) { return steps.stream().mapToInt(step -> step.shards).sum(); }

	public static int sumMaterials(List<Step> steps) { return steps.stream().mapToInt(step -> step.materials).sum(); }
}
