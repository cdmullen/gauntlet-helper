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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import static net.runelite.api.gameval.ItemID.GAUNTLET_COMBO_FOOD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HERB_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PESTLE;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_1;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_2;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_3;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_4;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_UNFINISHED;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RAW_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_SCEPTRE;
import static net.runelite.api.gameval.ItemID.GAUNTLET_SCEPTRE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_WATER;

/** Full-prep capacity includes remaining fishing; stored food is recovered only at the final food task. */
public final class InventoryAdvice
{
	private final Map<Integer, Integer> drops = new LinkedHashMap<>();
	private final Map<Integer, Integer> finishedTools = new LinkedHashMap<>();
	private int cookBeforeStoring;
	private int fishToStore;
	private boolean dropInProgress;

	/**
	 * Drop anywhere: finished gathering tools, dust no potion needs, shards with nothing left to buy, the sceptre once no room is left to light, the
	 * teleport crystal once home with nothing left outside it, plus everything not kept for Hunllef once only
	 * cooking/food retrieval remains.
	 */
	public Map<Integer, Integer> getFinishedTools() { return Collections.unmodifiableMap(finishedTools); }
	public boolean shouldStoreFish()
	{
		return cookBeforeStoring > 0 || drops.containsKey(GAUNTLET_RAW_FOOD) || drops.containsKey(GAUNTLET_FOOD);
	}

	/** Raw fish to cook at the range before any fish is left at home (fish are stored cooked, dropped at the range). */
	public int getCookBeforeStoring() { return cookBeforeStoring; }
	public final int requiredSlots;

	public InventoryAdvice(Requirements plan, Inventory inv, boolean atHome)
	{
		Map<Integer, Integer> anywhere = finishedTools;
		anywhere.putAll(Simulation.findFinishedTools(plan, inv));
		// Home with nothing left outside it, or time low, the crystal isn't needed for another trip back.
		if (atHome && (!plan.needsExploration() || plan.isUpgradesDeferred()) && inv.getHeld(GAUNTLET_TELEPORT_CRYSTAL_HM) > 0)
			anywhere.put(GAUNTLET_TELEPORT_CRYSTAL_HM, inv.getHeld(GAUNTLET_TELEPORT_CRYSTAL_HM));
		// Gear, potions and fish are done: everything but food, potions and the selected weapons gets a drop cue.
		if (plan.isOnlyFoodLeft())
			for (Map.Entry<Integer, Integer> held : inv.getHeldItems().entrySet())
				if (held.getValue() > 0 && !isKeptForHunllef(plan, held.getKey()) && (atHome || held.getKey() != GAUNTLET_TELEPORT_CRYSTAL_HM))
					anywhere.put(held.getKey(), held.getValue());
		Simulation.Capacity capacity = Simulation.computeCapacity(plan, inv, atHome, plan.getStoredFood(), 0);
		requiredSlots = capacity.additionalSlots;
		drops.putAll(finishedTools);
		if (!atHome)
			return;
		for (Requirement resource : Requirement.MATERIALS)
			if (plan.getRow(resource).target == 0)
				add(inv, resource.getItemId(), plan.getRow(resource).have);
		if (plan.getRow(Requirement.WEAPONS).isComplete())
			for (WeaponType type : WeaponType.values())
				if (!plan.getDesiredWeaponTypes().contains(type))
					for (Tier tier : Tier.values())
						add(inv, type.getItem(tier), inv.getHeld(type.getItem(tier)));
		// Surplus fish are dropped cooked at the range; the cue appears once the fixed number to store is cooked.
		int toStore = Simulation.countFishToStore(plan, inv, plan.getStoredFood(), capacity);
		if (capacity.rawToStore > 0 && inv.getHeld(GAUNTLET_RAW_FOOD) > 0)
		{
			cookBeforeStoring = inv.getHeld(GAUNTLET_RAW_FOOD);
			fishToStore = toStore;
			if (inv.getHeld(GAUNTLET_FOOD) >= toStore)
				add(inv, GAUNTLET_FOOD, toStore);
		}
		else
		{
			add(inv, GAUNTLET_FOOD, toStore);
		}
	}

	/** What the fight needs once preparation is done: food (raw fish is cooked, not dropped; a Corrupted paddlefish
	 * made at the bowl is food too), potions, armor and the selected weapon types. */
	static boolean isKeptForHunllef(Requirements plan, int id)
	{
		switch (id)
		{
			case GAUNTLET_FOOD:
			case GAUNTLET_RAW_FOOD:
			case GAUNTLET_COMBO_FOOD_HM:
			case GAUNTLET_POTION_1:
			case GAUNTLET_POTION_2:
			case GAUNTLET_POTION_3:
			case GAUNTLET_POTION_4:
				return true;
			default:
				if (ArmorUpgrades.getSlot(id) != null)
					return true;
				for (WeaponType type : plan.getDesiredWeaponTypes())
					for (Tier tier : Tier.values())
						if (type.getItem(tier) == id)
							return true;
				return false;
		}
	}

	private void add(Inventory inv, int id, int quantity)
	{
		int dropCount = Math.min(quantity, inv.getHeld(id));
		if (dropCount > 0)
			drops.put(id, dropCount);
	}

	public Map<Integer, Integer> getDrops() { return Collections.unmodifiableMap(drops); }

	/** No room is left to light (Sceptre.isSceptreDone): a held sceptre is dropped anywhere. */
	public void dropSceptre(Inventory inv)
	{
		for (int id : new int[]{GAUNTLET_SCEPTRE_HM, GAUNTLET_SCEPTRE})
			if (inv.getHeld(id) > 0)
			{
				finishedTools.put(id, inv.getHeld(id));
				drops.put(id, inv.getHeld(id));
			}
	}

	/** The route still lights a door (Guidance.applyGuidance): no drop cue for the sceptre, even with only food left. */
	public void keepSceptre()
	{
		for (int id : new int[]{GAUNTLET_SCEPTRE_HM, GAUNTLET_SCEPTRE})
		{
			finishedTools.remove(id);
			drops.remove(id);
		}
	}

	/** A started fish drop keeps its count and outranks crafting, filling and cooking until it is finished. */
	public void holdFishDrop(int cooked)
	{
		cookBeforeStoring = 0;
		drops.remove(GAUNTLET_RAW_FOOD);
		drops.put(GAUNTLET_FOOD, cooked);
		dropInProgress = true;
	}

	public boolean isDropInProgress() { return dropInProgress; }
	public String getInstruction()
	{
		// The number to store is known before the first fish is cooked, so the wording stays the same when its red
		// drop squares appear.
		if (cookBeforeStoring > 0)
			return "Cook " + cookBeforeStoring + " paddlefish at the range, then drop " + fishToStore + " cooked fish there";
		return shouldStoreFish() ? "Leave "
				+ (drops.getOrDefault(GAUNTLET_RAW_FOOD, 0) + drops.getOrDefault(GAUNTLET_FOOD, 0))
				+ " cooked fish at the range for final retrieval" : null;
	}

	public static Map<Integer, String> buildActions(Requirements plan, Inventory inv)
	{
		Map<Integer, String> actions = new HashMap<>();
		ArmorUpgrades worn = ArmorUpgrades.worn(inv);
		for (ArmorSlot slot : ArmorSlot.values())
			if (!worn.hasPiece(slot))
				for (Tier tier : Tier.values())
					if (inv.getHeld(ArmorUpgrades.getItem(slot, tier)) > 0)
						actions.put(ArmorUpgrades.getItem(slot, tier), InventoryCue.EQUIP);
		boolean perfectedWorn = plan.getDesiredWeaponTypes().stream().anyMatch(type -> inv.getEquipped(type.getPerfected()) > 0);
		if (!perfectedWorn)
		{
			boolean perfectedHeld = false;
			for (WeaponType type : plan.getDesiredWeaponTypes())
			{
				int id = type.getPerfected();
				if (inv.getHeld(id) > 0)
				{
					actions.put(id, InventoryCue.EQUIP);
					perfectedHeld = true;
				}
			}
			if (!perfectedHeld && inv.getEquipped(GAUNTLET_MAGIC_T2_HM) == 0 && inv.getHeld(GAUNTLET_MAGIC_T2_HM) > 0)
				actions.put(GAUNTLET_MAGIC_T2_HM, InventoryCue.EQUIP);
		}
		if (plan.needsWater())
		{
			actions.put(GAUNTLET_VIAL_EMPTY_HM, InventoryCue.FILL);
			actions.put(GAUNTLET_VIAL_EMPTY, InventoryCue.FILL);
		}
		boolean leafPairs = inv.getHeld(GAUNTLET_VIAL_WATER) > 0 && inv.getHeld(GAUNTLET_HERB_HM) > 0;
		// Grym leaves wait until every vial is filled, then dust waits on the leaves.
		if (!plan.getRow(Requirement.POTIONS).isComplete() && leafPairs && !plan.needsWater())
		{
			actions.put(GAUNTLET_HERB_HM, InventoryCue.MIX);
			actions.put(GAUNTLET_VIAL_WATER, InventoryCue.MIX);
		}
		if (!plan.getRow(Requirement.POTIONS).isComplete() && !leafPairs && inv.getHeld(GAUNTLET_POTION_UNFINISHED) > 0
				&& inv.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) >= Recipes.DUST_PER_POTION)
		{
			actions.put(GAUNTLET_POTION_UNFINISHED, InventoryCue.MIX);
			actions.put(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM, InventoryCue.MIX);
		}
		int grind = PotionShardBudget.computeGrindQuantity(plan, inv);
		if (grind > 0 && inv.getHeld(GAUNTLET_PESTLE) > 0)
		{
			actions.put(GAUNTLET_PESTLE, InventoryCue.grind(grind));
			actions.put(GAUNTLET_CRYSTAL_SHARD_HM, InventoryCue.grind(grind));
		}
		return actions;
	}
}
