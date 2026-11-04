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
import com.gauntlethelper.game.Tier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_GENERIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_WATER;

/**
 * The wording of a Requirements: its outstanding actions in priority order and the current and next lines they give. The
 * plugin builds one per tick, for the final plan; the trip search never does.
 */
public final class Instructions
{
	public static final String FIND_STAFF_FRAME = "Find a weapon frame for the staff";
	private static final String UPGRADE_ACTION = "Upgrade your armor at home";

	private final Requirements plan;
	private final List<String> actions;
	private final String instruction;
	private final String next;

	private Instructions(Requirements plan)
	{
		this.plan = plan;
		actions = Collections.unmodifiableList(buildActions(plan));
		String first = actions.isEmpty() ? "Review your preparation" : actions.get(0);
		String second = actions.size() > 1 ? actions.get(1) : "Review your loadout";
		if (plan.isReady())
		{
			instruction = getLoadoutName(plan) + " loadout ready";
			next = "Enter when you are ready";
		}
		else if (plan.needsInventorySpace())
		{
			instruction = "Make inventory space; keep needed items";
			next = first;
		}
		else if (plan.isToolDropsContinued())
		{
			instruction = first;
			next = first;
		}
		else if (plan.isEquipAfterCrafts())
		{
			instruction = second;
			next = first;
		}
		else
		{
			instruction = first;
			next = second;
		}
	}

	public static Instructions of(Requirements plan) { return new Instructions(plan); }

	/** Every outstanding prep action in priority order; the equip actions go first. */
	private static List<String> buildActions(Requirements plan)
	{
		Requirements.Gear gear = plan.gear;
		Requirements.Supplies supplies = plan.supplies;
		Inventory inventory = plan.getInventory();
		List<String> actions = new ArrayList<>();
		if (isShort(plan, Requirement.ORE))
		{
			actions.add("Gather " + plan.getHeldRow(Requirement.ORE).getMissing() + " ore");
		}
		if (isShort(plan, Requirement.BARK))
		{
			actions.add("Gather " + plan.getHeldRow(Requirement.BARK).getMissing() + " bark");
		}
		if (isShort(plan, Requirement.LINUM_TIRINUM))
		{
			actions.add("Gather " + plan.getHeldRow(Requirement.LINUM_TIRINUM).getMissing() + " linum tirinum");
		}
		if (isShort(plan, Requirement.FRAMES))
		{
			actions.add(gear.craftedWeapons + inventory.getHeld(GAUNTLET_GENERIC_COMPONENT_HM) >= 3
					? "Dismantle a spare weapon to allow frame drops"
					: "Find a weapon frame");
		}
		if (isShort(plan, Requirement.SHARDS))
		{
			actions.add("Gather " + plan.getHeldRow(Requirement.SHARDS).getMissing() + " shards");
		}
		if (!gear.armorSteps.isEmpty() && !isShort(plan, Requirement.ORE) && !isShort(plan, Requirement.BARK)
				&& !isShort(plan, Requirement.LINUM_TIRINUM)
				&& inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) >= gear.armorSteps.get(0).shards)
		{
			actions.add(ArmorUpgrades.isUpgrade(gear.armorSteps.get(0)) ? UPGRADE_ACTION : "Craft missing T1 armor at home");
		}
		if (gear.perfected < 2)
		{
			actions.add(gear.upgrades < 2 ? "Find distinct demi-boss upgrades" : "Craft your perfected weapons at home");
		}
		if (isShort(plan, Requirement.GRYM_LEAVES))
		{
			actions.add("Gather " + plan.getHeldRow(Requirement.GRYM_LEAVES).getMissing() + " grym leaves");
		}
		if (supplies.batches > 0 && !isShort(plan, Requirement.GRYM_LEAVES))
		{
			actions.add("Finish potions (fill vials, grym leaf, dust)");
		}
		if (isShort(plan, Requirement.FISH))
		{
			actions.add("Catch " + plan.getHeldRow(Requirement.FISH).getMissing() + " paddlefish");
		}
		if (!isShort(plan, Requirement.FISH) && isShort(plan, Requirement.COOKED))
		{
			actions.add("Cook your paddlefish at home");
		}
		if (supplies.batches > 0 && inventory.getHeld(GAUNTLET_VIAL_WATER) + supplies.unfinished >= supplies.batches
				&& !isShort(plan, Requirement.GRYM_LEAVES) && !isShort(plan, Requirement.DUST))
		{
			actions.add("Mix Egniol potions while moving");
		}
		if (gear.armorToEquip)
		{
			actions.add(0, "Equip your prepared armor");
		}
		if (gear.perfected >= 1 && !gear.wieldedPerfected)
		{
			actions.add(0, "Equip a perfected weapon");
		}
		if (plan.isUpgradesDeferred())
			moveUpgradesLast(plan, actions);
		return actions;
	}

	/** Deferred upgrades go after cooking and the final food, with any gathering only they still need. */
	private static void moveUpgradesLast(Requirements plan, List<String> actions)
	{
		List<String> later = new ArrayList<>(Collections.singletonList(UPGRADE_ACTION));
		for (Requirement resource : new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM, Requirement.SHARDS})
			if (plan.isShortOnlyForUpgrades(resource))
				later.add("Gather " + plan.getRow(resource).getMissing() + " " + resource.getLabel().toLowerCase(Locale.ROOT));
		later.removeIf(action -> !actions.remove(action));
		actions.addAll(later);
	}

	/** The action lines count the inventory alone: stored food and ground armor credits don't change their wording. */
	private static boolean isShort(Requirements plan, Requirement resource) { return plan.getHeldRow(resource).getMissing() > 0; }

	public boolean isFor(Requirements other) { return plan == other; }
	public List<String> getActions() { return actions; }
	public String getInstruction() { return instruction; }
	public String getNext() { return next; }

	public static String getLoadoutName(Requirements plan)
	{
		if (plan.getArmorGoal() == ArmorUpgrades.NO_ARMOR)
			return "No armor";
		return "T" + plan.getArmorGoal();
	}

	/** The panel label: above a T1 goal the ARMOR row counts tiers, each slot up to its goal tier (6 = 2+2+2). */
	public static String getLabel(Requirements plan, Requirement resource)
	{
		return resource == Requirement.ARMOR && plan.getArmorGoal() > ArmorUpgrades.T1 ? "Armor tiers" : resource.getLabel();
	}

	/** Guidance while no staff is owned yet: craft the basic staff from a held frame, else find one. */
	public static String getStaffInstruction(Requirements plan)
	{
		return plan.getInventory().getHeld(GAUNTLET_GENERIC_COMPONENT_HM) > 0 ? ObjectKind.BOWL.label : FIND_STAFF_FRAME;
	}

	/** The panel line when the potions leave no room for the fish asked for, else null. */
	public static String getFoodCapNotice(Requirements plan)
	{
		return plan.isFoodCapped() ? "Paddlefish capped at " + plan.getFoodCap() + " (" + plan.getLeafTarget() + " potions)" : null;
	}

	/** A plan's craft by name: "Crystal vial (2 needed)", "Basic helm", "Attuned Staff". */
	static String getCraftLabel(Requirements plan, int itemId)
	{
		if (itemId == GAUNTLET_VIAL_EMPTY_HM)
			return "Crystal vial (" + (plan.supplies.batches - plan.supplies.vials) + " needed)";
		if (ArmorUpgrades.getSlot(itemId) != null)
			return ArmorUpgrades.findStep(itemId, plan.getArmorGoal()).label;
		for (WeaponType type : WeaponType.values())
			for (Tier tier : Tier.values())
				if (type.getItem(tier) == itemId)
					return tier.getLabel() + " " + type.getLabel();
		throw new IllegalArgumentException("Not a bowl craft: " + itemId);
	}
}
