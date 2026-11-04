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
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.game.Tier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import static net.runelite.api.gameval.ItemID.GAUNTLET_BARK_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FIBRE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_GENERIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T3_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_ORE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY_HM;

/** Forecast one home visit without spending a shard or inventory slot twice. */
public final class HomeCraftPlan
{
	/** Shards for the attuned weapon upgrade; a basic weapon is free from a frame, perfected uses the component. */
	public static final int ATTUNE_SHARDS = 50;
	private final List<Requirements.Craft> planned = new ArrayList<>();
	private final List<String> notes = new ArrayList<>();
	private final Inventory start;
	private Inventory forecast;
	private String blocked;
	private final List<Requirements.Craft> wanted;
	/** After the base crafts and vials: the forecast owns the T1 base and the staff, so upgrades may follow. */
	private boolean baseArmorDone;
	private final int baseArmorGoal;

	/**
	 * @param upgradeTypes  selected weapon types; any whose upgrade component is held is crafted up to perfected before vials
	 * @param groundArmor   armor slots whose piece lies on the ground: picked up instead
	 * @param armorUpgrades the armor goal's steps above basic still to make (Requirements.findArmorUpgrades), crafted last
	 * @param upgradeSpare  held shards beyond every other need (Requirements.getUpgradeShardSpare): the upgrades' budget
	 * @param baseArmorGoal the armor base made before the vials (ArmorUpgrades.getBaseGoal): T1, or none for the None goal
	 */
	public HomeCraftPlan(Inventory inventory, int missingVials, boolean needStaff, List<WeaponType> upgradeTypes, Set<ArmorSlot> groundArmor,
			List<ArmorUpgrades.Step> armorUpgrades, int upgradeSpare, int baseArmorGoal)
	{
		this.baseArmorGoal = baseArmorGoal;
		start = inventory;
		// Shards and materials choose the visit's crafts; slots only decide their order and how many fit now, so a
		// nearly full inventory doesn't limit a bowl visit to one vial or armor piece.
		forecast = inventory;
		select(inventory, missingVials, needStaff, upgradeTypes, groundArmor);
		selectUpgrades(armorUpgrades, upgradeSpare);
		wanted = Collections.unmodifiableList(new ArrayList<>(planned));
		planned.clear();
		forecast = inventory;
		schedule();
		notes.add("Vials after gear: " + countPlanned(GAUNTLET_VIAL_EMPTY_HM) + "/" + missingVials + " craftable (10 shards + 1 slot each)");
	}

	private void select(Inventory inventory, int missingVials, boolean needStaff, List<WeaponType> upgradeTypes, Set<ArmorSlot> groundArmor)
	{
		List<ArmorUpgrades.Step> armorSteps = ArmorUpgrades.owned(inventory).withGround(groundArmor).findSteps(baseArmorGoal);
		int armorShards = ArmorUpgrades.sumShards(armorSteps);
		int armorMaterials = ArmorUpgrades.sumMaterials(armorSteps);
		boolean staffReady = !needStaff || inventory.getOwned(GAUNTLET_MAGIC_T2_HM) + inventory.getOwned(GAUNTLET_MAGIC_T3_HM) > 0;
		int afterStaff = Math.max(0, inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) - (staffReady ? 0 : ATTUNE_SHARDS));
		boolean hasArmorMaterials = inventory.getHeld(GAUNTLET_ORE_HM) >= armorMaterials
				&& inventory.getHeld(GAUNTLET_BARK_HM) >= armorMaterials
				&& inventory.getHeld(GAUNTLET_FIBRE_HM) >= armorMaterials;
		notes.add(armorSteps.isEmpty() ? "T1 armor: complete" : "T1 armor after the attuned staff: "
				+ armorShards + " shards"
				+ (hasArmorMaterials && afterStaff >= armorShards ? " (materials/shards ready)" : " (need "
				+ Math.max(0, armorShards - afterStaff) + " more shards"
				+ (hasArmorMaterials ? "" : ", missing materials") + ")"));
		// The basic staff is free from a frame; the attuned staff and then armor come from the shards.
		if (!staffReady && inventory.getOwned(GAUNTLET_MAGIC_T1_HM) == 0)
			append(GAUNTLET_MAGIC_T1_HM, "Basic staff");
		if (!staffReady && forecast.getOwned(GAUNTLET_MAGIC_T1_HM) > 0)
			append(GAUNTLET_MAGIC_T2_HM, "Attuned staff");
		for (ArmorUpgrades.Step step : armorSteps)
			append(step.itemId, step.label);
		// A held upgrade component means the hunt found it: finish that weapon before vials, cooking or filling.
		int weaponReserve = 0;
		for (WeaponType type : upgradeTypes)
		{
			int basic = type.getItem(Tier.BASIC);
			int attuned = type.getItem(Tier.ATTUNED);
			int perfected = type.getPerfected();
			if (forecast.getOwned(perfected) > 0 || forecast.getHeld(type.getComponent()) == 0)
				continue;
			if (forecast.getOwned(basic) + forecast.getOwned(attuned) == 0)
				append(basic, "Basic " + type.getLabel());
			if (forecast.getOwned(attuned) == 0)
				append(attuned, "Attuned " + type.getLabel());
			append(perfected, "Perfected " + type.getLabel());
			// Keep the attuned-tier shards away from vials; the opening staff's are already in the gear reserve.
			if (forecast.getOwned(attuned) + forecast.getOwned(perfected) == 0 && !(type == WeaponType.STAFF && needStaff))
				weaponReserve += ATTUNE_SHARDS;
		}
		int count = 0;
		while (count < missingVials && PotionShardBudget.canBuyVial(forecast, needStaff, baseArmorGoal)
				&& forecast.getHeld(GAUNTLET_CRYSTAL_SHARD_HM)
				>= PotionShardBudget.computeGearReserve(forecast, needStaff, baseArmorGoal) + weaponReserve + Recipes.SHARDS_PER_VIAL
				&& append(GAUNTLET_VIAL_EMPTY_HM, "Crystal vial (" + (missingVials - count) + " needed)"))
			count++;
		baseArmorDone = ArmorUpgrades.owned(forecast).isComplete(baseArmorGoal) && isStaffReadyAfter(needStaff);
	}

	private boolean isStaffReadyAfter(boolean needStaff)
	{
		return !needStaff || forecast.getOwned(GAUNTLET_MAGIC_T2_HM) + forecast.getOwned(GAUNTLET_MAGIC_T3_HM) > 0;
	}

	/**
	 * The armor goal's upgrades come after the vials, once the T1 base and the staff are made, and only from the shards
	 * left after every other need is done or funded, so normally at the final home visit.
	 */
	private void selectUpgrades(List<ArmorUpgrades.Step> armorUpgrades, int upgradeSpare)
	{
		if (armorUpgrades.isEmpty())
			return;
		int spent = 0;
		for (ArmorUpgrades.Step step : armorUpgrades)
		{
			if (!baseArmorDone || spent + step.shards > upgradeSpare || !append(step.itemId, step.label))
				break;
			spent += step.shards;
		}
		notes.add("Armor upgrades after vials: " + countUpgrades(planned) + "/" + armorUpgrades.size() + " craftable ("
				+ Math.max(0, upgradeSpare) + " spare shards)");
	}

	private static int countUpgrades(List<Requirements.Craft> crafts)
	{
		return (int) crafts.stream().filter(craft -> isArmorUpgrade(craft.itemId)).count();
	}

	/** An upgrade step above basic: the bowl upgrades the piece in place. */
	static boolean isArmorUpgrade(int itemId)
	{
		return ArmorUpgrades.getSlot(itemId) != null && ArmorUpgrades.getPieceTier(itemId) != Tier.BASIC;
	}

	/**
	 * Craft the chosen items in priority order, except that an item without room waits while a later one that fits
	 * (an upgrade that uses up a component, armor that uses the last material stacks) frees a slot for it. Vials
	 * never go ahead of gear, and armor upgrades never go ahead of vials. Whatever still has no room is left for after
	 * making space.
	 */
	private void schedule()
	{
		List<Requirements.Craft> left = new ArrayList<>(wanted);
		while (!left.isEmpty())
		{
			int first = left.stream().mapToInt(craft -> getRank(craft.itemId)).min().getAsInt();
			Requirements.Craft chosen = null;
			for (Requirements.Craft craft : left)
			{
				if (getRank(craft.itemId) > first)
					continue;
				if (hasInputs(forecast, getInputs(craft.itemId)) && canFitRecipe(forecast, craft.itemId))
				{
					chosen = craft;
					break;
				}
			}
			if (chosen == null)
			{
				blocked = "Make inventory space to craft (equip gear or drop surplus)";
				return;
			}
			left.remove(chosen);
			append(chosen.itemId, chosen.label);
		}
	}

	/** Bowl order groups: gear first, then vials, then armor upgrades. */
	private static int getRank(int itemId)
	{
		if (isArmorUpgrade(itemId))
			return 2;
		return itemId == GAUNTLET_VIAL_EMPTY_HM ? 1 : 0;
	}

	/**
	 * Add the craft to the forecast when its inputs are owned; slots are checked by schedule, not here. An armor
	 * upgrade of a worn piece stays worn: the bowl upgrades it in place.
	 */
	private boolean append(int output, String label)
	{
		if (!hasInputs(forecast, getInputs(output)))
			return false;
		forecast = applyCraft(forecast, output);
		planned.add(new Requirements.Craft(output, label));
		return true;
	}

	/** The inventory after one craft of an item whose inputs are owned. */
	private static Inventory applyCraft(Inventory inventory, int output)
	{
		Map<Integer, Integer> inputs = getInputs(output);
		Map<Integer, Integer> held = new HashMap<>(inventory.getHeldItems());
		Map<Integer, Integer> worn = new HashMap<>(inventory.getEquippedItems());
		int used = Inventory.SIZE - inventory.getFreeSlots();
		boolean inPlace = false;
		for (Map.Entry<Integer, Integer> input : inputs.entrySet())
		{
			int id = input.getKey(), quantity = input.getValue();
			int fromHeld = Math.min(held.getOrDefault(id, 0), quantity);
			if (fromHeld > 0)
			{
				int left = held.get(id) - fromHeld;
				held.put(id, left);
				used -= Simulation.isStacked(id) ? (left == 0 ? 1 : 0) : fromHeld;
			}
			if (quantity > fromHeld)
			{
				worn.put(id, worn.getOrDefault(id, 0) - (quantity - fromHeld));
				inPlace |= ArmorUpgrades.getSlot(id) != null;
			}
		}
		if (inPlace)
		{
			worn.put(output, worn.getOrDefault(output, 0) + 1);
		}
		else
		{
			held.put(output, held.getOrDefault(output, 0) + 1);
			used++;
		}
		return new Inventory(held, worn, used);
	}

	static Map<Integer, Integer> getInputs(int output)
	{
		Map<Integer, Integer> result = new HashMap<>();
		if (output == GAUNTLET_VIAL_EMPTY_HM)
			result.put(GAUNTLET_CRYSTAL_SHARD_HM, Recipes.SHARDS_PER_VIAL);
		// Every upgrade step has its recipe: an upgrade takes the piece one tier below. Plans only append the steps of
		// their own armor goal, so a piece above the goal is never made.
		ArmorUpgrades.Step armor = ArmorUpgrades.findStep(output, ArmorUpgrades.T3);
		if (armor != null)
		{
			result.put(GAUNTLET_CRYSTAL_SHARD_HM, armor.shards);
			result.put(GAUNTLET_ORE_HM, armor.materials);
			result.put(GAUNTLET_BARK_HM, armor.materials);
			result.put(GAUNTLET_FIBRE_HM, armor.materials);
			if (armor.tier != Tier.BASIC)
				result.put(ArmorUpgrades.getItem(armor.slot, armor.tier.getBelow()), 1);
		}
		for (WeaponType type : WeaponType.values())
		{
			if (output == type.getItem(Tier.BASIC))
				result.put(GAUNTLET_GENERIC_COMPONENT_HM, 1);
			if (output == type.getItem(Tier.ATTUNED))
			{
				result.put(type.getItem(Tier.BASIC), 1);
				result.put(GAUNTLET_CRYSTAL_SHARD_HM, ATTUNE_SHARDS);
			}
			if (output == type.getPerfected())
			{
				result.put(type.getItem(Tier.ATTUNED), 1);
				result.put(type.getComponent(), 1);
			}
		}
		return result;
	}

	private static boolean hasInputs(Inventory inventory, Map<Integer, Integer> inputs)
	{
		return inputs.entrySet().stream().allMatch(e -> inventory.getOwned(e.getKey()) >= e.getValue());
	}

	public static boolean canFitRecipe(Inventory inventory, int output)
	{
		Map<Integer, Integer> inputs = getInputs(output);
		if (inputs.isEmpty() || !hasInputs(inventory, inputs))
			return false;
		// An upgrade of a worn piece needs no slot; a held piece frees its own.
		if (isArmorUpgrade(output))
			return true;
		int freed = 0;
		for (Map.Entry<Integer, Integer> input : inputs.entrySet())
		{
			int held = inventory.getHeld(input.getKey());
			if (held > 0)
				freed += Simulation.isStacked(input.getKey()) ? (held <= input.getValue() ? 1 : 0) : Math.min(held, input.getValue());
		}
		return inventory.getFreeSlots() + freed >= 1;
	}

	public Requirements.Craft getNextCraft() { return planned.isEmpty() ? null : planned.get(0); }
	/** Every craft this visit has room for, in bowl order (vials repeat); the overlay marks them all at once. */
	public List<Requirements.Craft> getCrafts() { return Collections.unmodifiableList(planned); }

	/**
	 * This visit's crafts for clicking in rank order, lowest first (a sort that keeps one button's tiers in order). A
	 * craft with no free slot waits for a later one that frees it, so nothing lands on the ground; when the ranks can't
	 * finish every craft that way, the planned order stands, since it always fits.
	 */
	public List<Requirements.Craft> orderCrafts(ToIntFunction<Requirements.Craft> rank)
	{
		List<Requirements.Craft> left = new ArrayList<>(planned);
		left.sort(Comparator.comparingInt(rank));
		List<Requirements.Craft> ordered = new ArrayList<>();
		Inventory state = start;
		while (!left.isEmpty())
		{
			Requirements.Craft chosen = null;
			for (Requirements.Craft craft : left)
			{
				if (canFitRecipe(state, craft.itemId))
				{
					chosen = craft;
					break;
				}
			}
			if (chosen == null)
				return getCrafts();
			left.remove(chosen);
			ordered.add(chosen);
			state = applyCraft(state, chosen.itemId);
		}
		return ordered;
	}

	/** Crafts the shards and materials allow but the free slots don't yet: cooking and storing fish first helps. */
	public int countWaitingForSpace() { return wanted.size() - planned.size(); }

	/** How many of this item the visit's plan crafts (vials repeat, one entry each). */
	public int countPlanned(int itemId)
	{
		return (int) planned.stream().filter(craft -> craft.itemId == itemId).count();
	}

	List<String> getNotes() { return Collections.unmodifiableList(notes); }
	public String getBlocked() { return blocked; }
	/** Generic: the craft overlay marks the item, so the text never names it. */
	public String getInstruction()
	{
		return getNextCraft() != null ? ObjectKind.BOWL.label : blocked;
	}
}
