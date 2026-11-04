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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import static net.runelite.api.gameval.ItemID.GAUNTLET_BARK_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_COMBO_FOOD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FIBRE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HERB_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_ORE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PESTLE;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_3;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_UNFINISHED;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RAW_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_SCEPTRE;
import static net.runelite.api.gameval.ItemID.GAUNTLET_SCEPTRE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_WATER;

/** Forecasts inventory changes on copied state for route planning. */
public final class Simulation
{
	public enum Kind
	{
		DROP_TOOL,
		DROP_SURPLUS,
		STORE_FISH,
		EQUIP,
		CRAFT,
		FILL,
		MIX,
		GRIND,
		COOK,
		RETRIEVE_FISH,
		GATHER,
		PICKUP,
		SEARCH,
		TELEPORT;

		/** Done in the inventory on the way, so it never decides where the trip goes. */
		public boolean isInventoryOnly() { return this == DROP_TOOL || this == EQUIP || this == MIX || this == GRIND; }
	}

	public static final class Operation
	{
		public final Kind kind;
		public final int itemId, quantity;
		public final String label;

		Operation(Kind kind, int id, int quantity, String label)
		{
			this.kind = kind;
			this.itemId = id;
			this.quantity = quantity;
			this.label = label;
		}
	}

	public static final class Capacity
	{
		public final int additionalSlots, peakSlots, rawToStore, cookedToStore;

		Capacity(int additional, int peak, int raw, int cooked)
		{
			additionalSlots = additional;
			peakSlots = peak;
			rawToStore = raw;
			cookedToStore = cooked;
		}
	}

	private final Map<Integer, Integer> held, worn;
	private int used, peak, storedRaw, storedCooked, craftedPotions;
	private final Requirements goal;
	private final Goals goals;
	private final List<Operation> operations = new ArrayList<>();

	public Simulation(Requirements goal, Inventory inventory, int storedRaw, int storedCooked)
	{
		this.goal = goal;
		goals = goal.getGoals().withFoodTarget(goal.getFoodTarget());
		craftedPotions = goal.getCraftedPotions();
		held = new HashMap<>(inventory.getHeldItems());
		worn = new HashMap<>(inventory.getEquippedItems());
		used = Inventory.SIZE - inventory.getFreeSlots();
		peak = used;
		this.storedRaw = Math.max(0, storedRaw);
		this.storedCooked = Math.max(0, storedCooked);
	}

	public Simulation copy()
	{
		Simulation copy = new Simulation(goal, snapshot(), storedRaw, storedCooked);
		copy.peak = peak;
		copy.craftedPotions = craftedPotions;
		copy.markDirty();
		return copy;
	}

	// Forecast state is rebuilt thousands of times per trip search; reuse derived views until the next mutation.
	private Inventory cachedSnapshot;
	private Requirements cachedPlan;
	private String cachedKey;

	private void markDirty()
	{
		cachedSnapshot = null;
		cachedPlan = null;
		cachedKey = null;
	}

	public Inventory snapshot()
	{
		if (cachedSnapshot == null)
			cachedSnapshot = new Inventory(held, worn, used);
		return cachedSnapshot;
	}

	/** The forecast plan: numbers only, shared until the simulation changes. */
	public Requirements plan()
	{
		if (cachedPlan == null)
			cachedPlan = new Requirements(snapshot(), goals.withCraftedPotions(craftedPotions)).withStoredFood(storedRaw + storedCooked);
		return cachedPlan;
	}

	int getUsedSlots() { return used; }
	public int getPeakSlots() { return peak; }
	public int getStoredRaw() { return storedRaw; }
	public int getStoredCooked() { return storedCooked; }
	public List<Operation> getOperations() { return List.copyOf(operations); }
	public static boolean isStacked(int id)
	{
		return id == GAUNTLET_CRYSTAL_SHARD_HM || id == GAUNTLET_ORE_HM || id == GAUNTLET_BARK_HM
				|| id == GAUNTLET_FIBRE_HM || id == GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
	}

	private int count(int id) { return held.getOrDefault(id, 0); }
	private int getOwned(int id) { return count(id) + worn.getOrDefault(id, 0); }
	private void remove(int id, int quantity)
	{
		int removed = Math.min(count(id), quantity);
		if (removed <= 0)
			return;
		int remaining = count(id) - removed;
		used = Math.max(0, used - (isStacked(id) ? remaining == 0 ? 1 : 0 : removed));
		if (remaining == 0)
			held.remove(id);
		else
			held.put(id, remaining);
		markDirty();
	}

	private void put(int id, int quantity)
	{
		if (quantity <= 0)
			return;
		used += isStacked(id) ? count(id) == 0 ? 1 : 0 : quantity;
		held.merge(id, quantity, Integer::sum);
		peak = Math.max(peak, used);
		markDirty();
	}

	void forecastAcquire(int id, int quantity) { put(id, quantity); }
	public int acquire(int id, int quantity)
	{
		int accepted = Math.min(Math.max(0, quantity),
				isStacked(id) ? count(id) > 0 || used < Inventory.SIZE ? quantity : 0 : Math.max(0, Inventory.SIZE - used));
		put(id, accepted);
		return accepted;
	}

	public void drop(int id, int quantity, Kind kind, String label)
	{
		int dropped = Math.min(quantity, count(id));
		if (dropped > 0)
		{
			remove(id, dropped);
			operations.add(new Operation(kind, id, dropped, label));
		}
	}

	public static Map<Integer, Integer> findFinishedTools(Requirements plan, Inventory inv)
	{
		Map<Integer, Integer> result = new LinkedHashMap<>();
		for (Requirement resource : Requirement.TOOLED)
			if (isToolDone(plan, resource) && inv.getHeld(resource.getTool()) > 0)
				result.put(resource.getTool(), inv.getHeld(resource.getTool()));
		// Dust left once no potion craft needs any (the attempt's potions are crafted) is dropped like a finished tool
		// (grinding is in ten-shard batches, so 30 dust can be ground for a 20-dust need).
		if (plan.getRow(Requirement.DUST).target == 0 && inv.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) > 0)
			result.put(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM, inv.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM));
		// Shards with nothing left to buy (gear paid for, every vial bought, the dust ground) don't wait for the potions.
		if (plan.getRow(Requirement.SHARDS).target == 0 && inv.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) > 0)
			result.put(GAUNTLET_CRYSTAL_SHARD_HM, inv.getHeld(GAUNTLET_CRYSTAL_SHARD_HM));
		// No potions planned at all (Grym leaf 0): vials, leaves and unfinished potions have no use.
		if (plan.getRow(Requirement.POTIONS).target == 0)
			for (int id : new int[]{GAUNTLET_VIAL_EMPTY_HM, GAUNTLET_VIAL_EMPTY, GAUNTLET_VIAL_WATER, GAUNTLET_HERB_HM, GAUNTLET_POTION_UNFINISHED})
				if (inv.getHeld(id) > 0)
					result.put(id, inv.getHeld(id));
		return Collections.unmodifiableMap(result);
	}

	/** With the upgrades deferred (time low) nothing more is gathered for them, so what they alone are short of ends the tool. */
	static boolean isToolDone(Requirements plan, Requirement resource)
	{
		boolean upgradeMaterial = resource == Requirement.ORE || resource == Requirement.BARK;
		return plan.getRow(resource).isComplete() || plan.isUpgradesDeferred() && upgradeMaterial && plan.isShortOnlyForUpgrades(resource);
	}

	private boolean equip(int id, boolean isWeapon)
	{
		if (count(id) <= 0)
			return false;
		if (isWeapon)
		{
			int old = 0;
			for (WeaponType type : WeaponType.values())
				for (Tier tier : Tier.values())
					if (worn.getOrDefault(type.getItem(tier), 0) > 0)
						old = type.getItem(tier);
			// A wielded sceptre goes back to the inventory like any weapon.
			for (int sceptre : new int[]{GAUNTLET_SCEPTRE_HM, GAUNTLET_SCEPTRE})
				if (worn.getOrDefault(sceptre, 0) > 0)
					old = sceptre;
			remove(id, 1);
			if (old != 0)
			{
				worn.remove(old);
				put(old, 1);
			}
			worn.put(id, 1);
		}
		else
		{
			remove(id, 1);
			ArmorSlot slot = ArmorUpgrades.getSlot(id);
			Tier old = ArmorUpgrades.of(item -> worn.getOrDefault(item, 0)).getTier(slot);
			if (old != null)
			{
				int oldId = ArmorUpgrades.getItem(slot, old);
				worn.remove(oldId);
				put(oldId, 1);
			}
			worn.put(id, 1);
		}
		markDirty();
		operations.add(new Operation(Kind.EQUIP, id, 1, "Equip prepared gear"));
		return true;
	}

	private boolean craft(int output, String name)
	{
		Map<Integer, Integer> inputs = HomeCraftPlan.getInputs(output);
		if (inputs.isEmpty() || !HomeCraftPlan.canFitRecipe(snapshot(), output))
			return false;
		boolean inPlace = false;
		for (Map.Entry<Integer, Integer> e : inputs.entrySet())
		{
			int used = Math.min(count(e.getKey()), e.getValue());
			remove(e.getKey(), used);
			if (used < e.getValue())
			{
				int remain = worn.getOrDefault(e.getKey(), 0) - (e.getValue() - used);
				if (remain == 0)
					worn.remove(e.getKey());
				else
					worn.put(e.getKey(), remain);
				inPlace |= ArmorUpgrades.getSlot(e.getKey()) != null;
				markDirty();
			}
		}
		// The bowl upgrades a worn armor piece in place.
		if (inPlace)
			worn.merge(output, 1, Integer::sum);
		else
			put(output, 1);
		operations.add(new Operation(Kind.CRAFT, output, 1, "Craft " + name));
		return true;
	}

	/** Apply available manual actions to a forecast only. Repeated calls are idempotent. */
	public void normalize(boolean atHome, boolean hasWater, boolean hasRange)
	{
		normalize(atHome, hasWater, hasRange, Integer.MAX_VALUE);
	}

	public void normalize(boolean atHome, boolean hasWater, boolean hasRange, int cookTarget)
	{
		for (int pass = 0; pass < 80; pass++)
		{
			String before = getKey();
			Requirements plan = plan();
			dropFinishedTools(plan);
			equipArmor();
			equipWeapon(plan);
			if (atHome)
			{
				craftAtHome();
				dropUnusedAtHome();
			}
			if (hasWater)
				fillVials();
			addGrymLeaves();
			grindDust();
			finishPotions();
			if (hasRange && count(GAUNTLET_RAW_FOOD) > 0)
				cookFish(cookTarget);
			if (before.equals(getKey()))
				break;
		}
	}

	private void dropFinishedTools(Requirements plan)
	{
		for (Map.Entry<Integer, Integer> e : findFinishedTools(plan, snapshot()).entrySet())
			drop(e.getKey(), e.getValue(), Kind.DROP_TOOL, "Drop finished tool");
	}

	/** Wear the best held piece in every armor slot whose worn piece is worse, or empty. */
	private void equipArmor()
	{
		ArmorUpgrades wornArmor = ArmorUpgrades.of(id -> worn.getOrDefault(id, 0));
		for (ArmorSlot slot : ArmorSlot.values())
			for (Tier tier = Tier.PERFECTED; tier != null && tier.isAbove(wornArmor.getTier(slot)); tier = tier.getBelow())
				if (count(ArmorUpgrades.getItem(slot, tier)) > 0)
				{
					equip(ArmorUpgrades.getItem(slot, tier), false);
					break;
				}
	}

	/** Wield a held perfected weapon of the pair, else the attuned staff, unless one is wielded already. */
	private void equipWeapon(Requirements plan)
	{
		boolean wieldedPerfected = false;
		for (WeaponType type : plan.getDesiredWeaponTypes())
			wieldedPerfected |= worn.getOrDefault(type.getPerfected(), 0) > 0;
		if (!wieldedPerfected)
		{
			boolean equipped = false;
			for (WeaponType type : plan.getDesiredWeaponTypes())
				if (count(type.getPerfected()) > 0)
				{
					equip(type.getPerfected(), true);
					equipped = true;
					break;
				}
			if (!equipped && getOwned(GAUNTLET_MAGIC_T2_HM) > 0 && worn.getOrDefault(GAUNTLET_MAGIC_T2_HM, 0) == 0)
				equip(GAUNTLET_MAGIC_T2_HM, true);
		}
	}

	/**
	 * Basic staff, the attuned staff when affordable, then armor from what is left, the selected
	 * perfected weapon pair, then vials.
	 */
	private void craftAtHome()
	{
		craftStaff();
		craftArmor();
		craftWeapons();
		craftVials();
		craftUpgrades();
	}

	private void craftStaff()
	{
		Requirements plan = plan();
		boolean needsStaff = plan.needsOpeningStaff() && !plan.hasAttunedStaff();
		if (needsStaff && getOwned(GAUNTLET_MAGIC_T1_HM) == 0)
			craft(GAUNTLET_MAGIC_T1_HM, "basic staff");
		if (needsStaff && getOwned(GAUNTLET_MAGIC_T1_HM) > 0)
			craft(GAUNTLET_MAGIC_T2_HM, "attuned staff");
	}

	private void craftArmor()
	{
		for (ArmorUpgrades.Step step : ArmorUpgrades.of(this::getOwned).findSteps(ArmorUpgrades.getBaseGoal(goal.getArmorGoal())))
			craft(step.itemId, step.label.toLowerCase(Locale.ROOT));
	}

	/** The armor goal's upgrades after the vials, once the T1 base is made, from the shards no other need takes. */
	private void craftUpgrades()
	{
		for (int pass = 0; pass < ArmorUpgrades.getStepCount(ArmorUpgrades.T3); pass++)
		{
			Requirements plan = plan();
			ArmorUpgrades.Step step = plan.findNextUpgrade();
			if (step == null || step.shards > plan.getUpgradeShardSpare() || !craft(step.itemId, step.label.toLowerCase(Locale.ROOT)))
				return;
		}
	}

	private void craftWeapons()
	{
		Requirements plan = plan();
		for (WeaponType type : plan.getDesiredWeaponTypes())
		{
			int basic = type.getItem(Tier.BASIC);
			int attuned = type.getItem(Tier.ATTUNED);
			int perfected = type.getPerfected();
			if (count(type.getComponent()) == 0)
				continue; // Do not commit a spare frame to an unobserved second upgrade.
			if (getOwned(basic) + getOwned(attuned) + getOwned(perfected) == 0)
				craft(basic, "basic weapon");
			if (getOwned(perfected) == 0)
			{
				if (getOwned(attuned) == 0)
					craft(attuned, "attuned weapon");
				craft(perfected, "perfected weapon");
			}
		}
	}

	private void craftVials()
	{
		Requirements plan = plan();
		int vials = plan.getRow(Requirement.VIALS).getMissing();
		for (int i = 0; i < vials; i++)
			if (!PotionShardBudget.canBuyVial(plan(), snapshot()) || !craft(GAUNTLET_VIAL_EMPTY_HM, "crystal vial"))
				break;
	}

	/** Once the pair is perfected, the other prep weapons; once the armor is done, its leftover materials. */
	private void dropUnusedAtHome()
	{
		Requirements plan = plan();
		if (plan.getRow(Requirement.WEAPONS).isComplete())
			for (WeaponType type : WeaponType.values())
				if (!plan.getDesiredWeaponTypes().contains(type))
					for (Tier tier : Tier.values())
						drop(type.getItem(tier), count(type.getItem(tier)), Kind.DROP_SURPLUS, "Drop unused prep weapon");
		plan = plan();
		for (Requirement resource : Requirement.MATERIALS)
			if (plan.getRow(resource).target == 0)
				drop(resource.getItemId(), plan.getRow(resource).have, Kind.DROP_SURPLUS, "Drop surplus material");
	}

	private void fillVials()
	{
		Requirements plan = plan();
		int toFill = Math.min(plan.getRow(Requirement.POTIONS).getMissing() == 0 ? 0
				: plan.getRow(Requirement.VIALS).target - count(GAUNTLET_VIAL_WATER) - count(GAUNTLET_POTION_UNFINISHED),
				count(GAUNTLET_VIAL_EMPTY_HM) + count(GAUNTLET_VIAL_EMPTY));
		if (toFill > 0)
		{
			int hmVials = Math.min(toFill, count(GAUNTLET_VIAL_EMPTY_HM));
			remove(GAUNTLET_VIAL_EMPTY_HM, hmVials);
			remove(GAUNTLET_VIAL_EMPTY, toFill - hmVials);
			put(GAUNTLET_VIAL_WATER, toFill);
			operations.add(new Operation(Kind.FILL, GAUNTLET_VIAL_WATER, toFill, "Fill vials"));
		}
	}

	private void addGrymLeaves()
	{
		Requirements plan = plan();
		int leaves = Math.min(count(GAUNTLET_HERB_HM),
				Math.min(count(GAUNTLET_VIAL_WATER),
				plan.getRow(Requirement.VIALS).target - count(GAUNTLET_POTION_UNFINISHED)));
		if (leaves > 0)
		{
			remove(GAUNTLET_HERB_HM, leaves);
			remove(GAUNTLET_VIAL_WATER, leaves);
			put(GAUNTLET_POTION_UNFINISHED, leaves);
			operations.add(new Operation(Kind.MIX, GAUNTLET_POTION_UNFINISHED, leaves, "Add grym leaves to vials"));
		}
	}

	private void grindDust()
	{
		Requirements plan = plan();
		int grind = PotionShardBudget.computeGrindQuantity(plan, snapshot());
		if (grind > 0 && count(GAUNTLET_PESTLE) > 0
				&& (count(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) > 0 || used < Inventory.SIZE
				|| count(GAUNTLET_CRYSTAL_SHARD_HM) == grind))
		{
			remove(GAUNTLET_CRYSTAL_SHARD_HM, grind);
			put(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM, grind);
			operations.add(new Operation(Kind.GRIND, GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM, grind, "Grind potion dust"));
		}
	}

	private void finishPotions()
	{
		Requirements plan = plan();
		int potions = Math.min(count(GAUNTLET_POTION_UNFINISHED),
				Math.min(count(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) / Recipes.DUST_PER_POTION,
				plan.getRow(Requirement.POTIONS).getMissing()));
		if (potions > 0)
		{
			remove(GAUNTLET_POTION_UNFINISHED, potions);
			remove(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM, potions * Recipes.DUST_PER_POTION);
			put(GAUNTLET_POTION_3, potions);
			craftedPotions += potions;
			markDirty();
			operations.add(new Operation(Kind.MIX, GAUNTLET_POTION_3, potions, "Finish Egniol potions"));
		}
	}

	/** Cook raw fish at the range up to cookTarget cooked fish held. */
	private void cookFish(int cookTarget)
	{
		int toCook = Math.min(count(GAUNTLET_RAW_FOOD), Math.max(0, cookTarget - count(GAUNTLET_FOOD)));
		if (toCook > 0)
		{
			remove(GAUNTLET_RAW_FOOD, toCook);
			put(GAUNTLET_FOOD, toCook);
			operations.add(new Operation(Kind.COOK, GAUNTLET_FOOD, toCook, "Cook paddlefish"));
		}
	}

	public boolean teleportHome()
	{
		if (count(GAUNTLET_TELEPORT_CRYSTAL_HM) == 0)
			return false;
		remove(GAUNTLET_TELEPORT_CRYSTAL_HM, 1);
		operations.add(new Operation(Kind.TELEPORT, GAUNTLET_TELEPORT_CRYSTAL_HM, 1, "Use teleport crystal to return home"));
		return true;
	}

	public void storeFood(int raw, int cooked)
	{
		int rawStored = Math.min(raw, count(GAUNTLET_RAW_FOOD));
		int cookedStored = Math.min(cooked, count(GAUNTLET_FOOD));
		// Stored fish are cooked first and left cooked at the range (one slot per fish either way).
		if (rawStored > 0)
		{
			remove(GAUNTLET_RAW_FOOD, rawStored);
			put(GAUNTLET_FOOD, rawStored);
			operations.add(new Operation(Kind.COOK, GAUNTLET_FOOD, rawStored, "Cook paddlefish before storing"));
			cookedStored += rawStored;
		}
		if (cookedStored > 0)
		{
			drop(GAUNTLET_FOOD, cookedStored, Kind.STORE_FISH, "Store cooked fish at the range until final retrieval");
			storedCooked += cookedStored;
			markDirty();
		}
	}

	public void retrieveFood()
	{
		if (!plan().isReadyForStoredFood())
			return;
		int need = Math.max(0, goal.getFoodTarget() - count(GAUNTLET_RAW_FOOD) - count(GAUNTLET_FOOD) - count(GAUNTLET_COMBO_FOOD_HM));
		int cooked = acquire(GAUNTLET_FOOD, Math.min(storedCooked, need));
		storedCooked -= cooked;
		need -= cooked;
		markDirty();
		int raw = acquire(GAUNTLET_RAW_FOOD, Math.min(storedRaw, need));
		storedRaw -= raw;
		markDirty();
		if (raw + cooked > 0)
			operations.add(new Operation(Kind.RETRIEVE_FISH, GAUNTLET_RAW_FOOD, raw + cooked, "Retrieve stored fish"));
	}

	/** Forecast a collect-before-home trip, including conversion and subsequent recipe workspace. */
	public static Capacity computeCapacity(Requirements goal, Inventory inv, boolean atHome, int storedRaw, int storedCooked)
	{
		Simulation sim = new Simulation(goal, inv, storedRaw, storedCooked);
		sim.normalize(atHome, atHome, atHome);
		int baseline = sim.used;
		sim.peak = baseline;
		Requirements plan = sim.plan();
		for (Requirement resource : new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM,
				Requirement.SHARDS, Requirement.FRAMES, Requirement.GRYM_LEAVES})
			sim.put(resource.getItemId(), plan.getRow(resource).getMissing());
		plan = sim.plan();
		for (WeaponType type : plan.getDesiredWeaponTypes())
			if (!plan.hasUpgrade(type))
				sim.put(type.getComponent(), 1);
		// Retaining an alternate component can be necessary to force an orb on a repeat demi.
		if (plan.needsStaffOrb())
			for (WeaponType type : WeaponType.SECOND_WEAPONS)
				if (!sim.plan().hasUpgrade(type) && sim.count(type.getComponent()) == 0)
					sim.put(type.getComponent(), 1);
		// A route may fish before the next home visit. Reserve that room before any future home conversion.
		sim.put(GAUNTLET_RAW_FOOD, sim.plan().getRow(Requirement.FISH).getMissing());
		sim.normalize(false, false, false);
		int gearPeak = sim.peak;
		sim.normalize(true, true, true);
		plan = sim.plan();
		sim.put(GAUNTLET_RAW_FOOD, plan.getRow(Requirement.FISH).getMissing());
		sim.normalize(false, false, false);
		sim.normalize(true, true, true);
		int peak = Math.max(gearPeak, sim.peak);
		int shortfall = Math.max(0, peak - Inventory.SIZE);
		// No fish is kept in the inventory when storing.
		int raw = Math.min(inv.getHeld(GAUNTLET_RAW_FOOD), shortfall);
		int cooked = Math.min(inv.getHeld(GAUNTLET_FOOD), Math.max(0, shortfall - raw));
		return new Capacity(Math.max(0, peak - baseline), peak, raw, cooked);
	}

	/**
	 * The fewest fish to leave at home so the forecast fits: the smallest count that, gone first, leaves no shortfall.
	 * The plain forecast overstates it on a full inventory, where crafts that wait for a slot stay uncrafted.
	 */
	public static int countFishToStore(Requirements goal, Inventory inv, int storedFood, Capacity capacity)
	{
		int most = capacity.rawToStore + capacity.cookedToStore;
		for (int count = 1; count < most; count++)
		{
			Map<Integer, Integer> held = new HashMap<>(inv.getHeldItems());
			int raw = Math.min(count, held.getOrDefault(GAUNTLET_RAW_FOOD, 0));
			held.merge(GAUNTLET_RAW_FOOD, -raw, Integer::sum);
			held.merge(GAUNTLET_FOOD, raw - count, Integer::sum);
			Inventory without = new Inventory(held, inv.getEquippedItems(), Inventory.SIZE - inv.getFreeSlots() - count);
			Capacity forecast = computeCapacity(goal, without, true, storedFood + count, 0);
			if (forecast.rawToStore + forecast.cookedToStore == 0)
				return count;
		}
		return most;
	}

	public String getKey()
	{
		if (cachedKey == null)
			cachedKey = new TreeMap<>(held) + "|" + new TreeMap<>(worn) + "|" + used + "|" + storedRaw + ":" + storedCooked + "|" + craftedPotions;
		return cachedKey;
	}
}
