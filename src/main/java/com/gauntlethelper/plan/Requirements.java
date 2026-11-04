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

import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.config.WeaponPair;
import com.gauntlethelper.game.ArmorSlot;
import com.gauntlethelper.game.Tier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static net.runelite.api.gameval.ItemID.GAUNTLET_BARK_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FIBRE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_GENERIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HERB_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T3_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_ORE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_UNFINISHED;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RAW_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_EMPTY_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_VIAL_WATER;

/**
 * What prep still needs, in numbers only: rows, gear counts, armor steps, crafts and readiness, from the Goals plus the
 * inventory. Immutable: an observation gives a new value (withStoredFood, withGroundArmor, ...). The trip search rebuilds it
 * for every forecast state, so it builds no text; the wording is in Instructions.
 */
public final class Requirements
{
	public static final class Row
	{
		public final int have;
		public final int target;

		Row(int have, int target)
		{
			this.have = Math.max(0, have);
			this.target = Math.max(0, target);
		}

		public int getMissing() { return Math.max(0, target - have); }
		public boolean isComplete() { return getMissing() == 0; }
		public String getText() { return have + "/" + target; }

		@Override
		public boolean equals(Object other)
		{
			return other instanceof Row && have == ((Row) other).have && target == ((Row) other).target;
		}

		@Override
		public int hashCode() { return 31 * have + target; }
	}

	public static final class Craft
	{
		public final int itemId;
		public final String label;

		public Craft(int itemId, String label)
		{
			this.itemId = itemId;
			this.label = label;
		}
	}

	/**
	 * Why the current instruction is an inventory task rather than a route task. Callers branch on this, never on the
	 * instruction text, so the wording can change freely.
	 */
	public enum InventoryAction
	{
		/** The instruction is an ordinary prep task. */
		NONE,
		/** Equip prepared armor or a perfected weapon. */
		EQUIP,
		/** Inventory is full with nothing craftable: make space, keeping needed items. */
		MAKE_SPACE
	}

	private final Goals goals;
	private final Inventory inventory;
	final Gear gear;
	final Supplies supplies;
	private final int foodTarget, foodCap;
	private final int supplySlotsShortfall;
	private final boolean needsWater;
	/** The rows from the inventory alone, before stored food and ground armor are credited: the instruction wording counts these. */
	private final Map<Requirement, Row> heldRows;

	// Set while a new value is built, never after it is returned.
	private Map<Requirement, Row> rows;
	private List<Integer> craftItems = new ArrayList<>();
	private int storedFood;
	private Set<ArmorSlot> groundArmor = Collections.emptySet();
	/** Time low with armor upgrades left: cooking and the final food come before them (withUpgradesDeferred). */
	private boolean upgradesDeferred;
	private boolean ready;
	private InventoryAction inventoryAction = InventoryAction.NONE;
	/** Instructions: the task behind the Make-space line stands (withToolDropsContinued). */
	private boolean toolDropsContinued;
	/** Instructions: the equip task moved to Next (withEquipAfterCrafts). */
	private boolean equipAfterCrafts;

	/**
	 * The plan as the plugin builds it every tick: targets and weapon pair from the settings, crafted potions and the
	 * weapon commitment from the attempt ledgers.
	 */
	public static Requirements live(Inventory inventory, GauntletHelperConfig config, PotionProgress potions, WeaponSelection selection)
	{
		WeaponPair pair = config.weaponPair();
		return new Requirements(inventory, new Goals(config.paddlefishTarget(), Goals.getLeafSetting(config), potions.observe(inventory),
				pair, selection.observe(pair, inventory), Goals.getArmorGoalSetting(config)));
	}

	public Requirements(Inventory inventory, Goals goals)
	{
		this.goals = goals;
		this.inventory = inventory;
		gear = countGear(goals.getSelection());
		foodCap = computeFoodCap(gear.desired.size(), goals.getLeafTarget());
		foodTarget = Math.min(goals.getFoodTarget(), foodCap);
		supplies = new Supplies(inventory, Math.max(0, goals.getLeafTarget() - goals.getCraftedPotions()));
		supplySlotsShortfall = supplies.computeSlotsShortfall(foodTarget, inventory.getFreeSlots());
		rows = new EnumMap<>(Requirement.class);
		heldRows = rows;
		putRows(gear, supplies);
		addCrafts(gear, supplies);
		needsWater = supplies.batches > 0 && (inventory.getHeld(GAUNTLET_VIAL_EMPTY_HM) + inventory.getHeld(GAUNTLET_VIAL_EMPTY)) > 0;
		chooseInventoryAction();
	}

	/** A copy for one with-method to change before returning it. */
	private Requirements(Requirements other)
	{
		goals = other.goals;
		inventory = other.inventory;
		gear = other.gear;
		supplies = other.supplies;
		foodTarget = other.foodTarget;
		foodCap = other.foodCap;
		supplySlotsShortfall = other.supplySlotsShortfall;
		needsWater = other.needsWater;
		heldRows = other.heldRows;
		rows = new EnumMap<>(other.rows);
		craftItems = new ArrayList<>(other.craftItems);
		storedFood = other.storedFood;
		groundArmor = other.groundArmor;
		upgradesDeferred = other.upgradesDeferred;
		ready = other.ready;
		inventoryAction = other.inventoryAction;
		toolDropsContinued = other.toolDropsContinued;
		equipAfterCrafts = other.equipAfterCrafts;
	}

	/** The server stops frame drops at three held frames/crafted weapons. */
	public boolean canReceiveFrameDrop()
	{
		int total = inventory.getHeld(GAUNTLET_GENERIC_COMPONENT_HM);
		for (WeaponType type : WeaponType.values())
			for (Tier tier : Tier.values())
				total += inventory.getOwned(type.getItem(tier));
		return total < 3;
	}

	/** Gear counted once per plan: armor pieces, weapon tiers, the pair the plan aims for and what it still costs. */
	static final class Gear
	{
		/** The armor goal's crafts not yet made, cheapest first. */
		List<ArmorUpgrades.Step> armorSteps;
		/** Every worn piece is at its goal tier. */
		boolean armorWorn;
		/** A held armor piece is better than the one worn in its slot, or its slot is empty. */
		boolean armorToEquip;
		/** The goal's upgrade step count, the ARMOR row's target. */
		int goal;
		/** Each type's highest owned tier; absent when none is owned. */
		final Map<WeaponType, Tier> tiers = new EnumMap<>(WeaponType.class);
		int craftedWeapons;
		List<WeaponType> desired;
		int perfected, upgrades;
		boolean wieldedPerfected;
		int frameTarget, weaponShards;

		int getArmor() { return goal - armorSteps.size(); }
		/** The type's highest owned tier, or null. */
		Tier getOwnedTier(WeaponType type) { return tiers.get(type); }
		/** The tiers owned, 0 to 3: the order weapons rank by. */
		int countOwnedTiers(WeaponType type) { return tiers.containsKey(type) ? tiers.get(type).ordinal() + 1 : 0; }
		boolean hasPerfected(WeaponType type) { return getOwnedTier(type) == Tier.PERFECTED; }
	}

	/** Potion and food supplies held; batches are the potions still to craft this attempt. */
	static final class Supplies
	{
		final int batches, unfinished, vials, dust, raw, cooked;

		Supplies(Inventory inventory, int batches)
		{
			this.batches = batches;
			unfinished = inventory.getHeld(GAUNTLET_POTION_UNFINISHED);
			vials = inventory.getHeld(GAUNTLET_VIAL_EMPTY_HM) + inventory.getHeld(GAUNTLET_VIAL_EMPTY) + inventory.getHeld(GAUNTLET_VIAL_WATER) + unfinished;
			dust = inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM);
			raw = inventory.getHeld(GAUNTLET_RAW_FOOD);
			cooked = inventory.getHeldCookedFood();
		}

		int computeSlotsShortfall(int foodTarget, int freeSlots)
		{
			return Math.max(0, Math.max(0, foodTarget - raw - cooked) + Math.max(0, batches - vials) - freeSlots);
		}
	}

	/**
	 * The most fish the finished loadout holds: armor is worn and one weapon wielded, so the inventory keeps the other
	 * selected weapons, one slot per potion and fish in the rest. Potions come first.
	 */
	public static int computeFoodCap(int selectedWeapons, int potions)
	{
		int carriedWeapons = Math.max(0, selectedWeapons - 1);
		return Math.max(0, Inventory.SIZE - carriedWeapons - potions);
	}

	private Gear countGear(Set<WeaponType> wanted)
	{
		Gear gear = new Gear();
		countArmor(gear);
		countWeaponTiers(gear);
		gear.desired = chooseWeaponTypes(gear, wanted);
		countSelectedWeapons(gear);
		priceSelectedWeapons(gear);
		return gear;
	}

	private void countArmor(Gear gear)
	{
		gear.goal = ArmorUpgrades.getStepCount(getArmorGoal());
		gear.armorSteps = findArmorSteps();
		ArmorUpgrades worn = ArmorUpgrades.worn(inventory);
		ArmorUpgrades owned = ArmorUpgrades.owned(inventory);
		gear.armorWorn = worn.isComplete(getArmorGoal());
		for (ArmorSlot slot : ArmorSlot.values())
			gear.armorToEquip |= owned.getTier(slot) != null && owned.getTier(slot).isAbove(worn.getTier(slot));
	}

	/** Each weapon type's highest owned tier (see Gear.tiers) and the number of crafted weapons owned. */
	private void countWeaponTiers(Gear gear)
	{
		for (WeaponType type : WeaponType.values())
		{
			for (Tier tier : Tier.values())
			{
				if (inventory.getOwned(type.getItem(tier)) > 0)
				{
					gear.tiers.put(type, tier);
				}
				gear.craftedWeapons += inventory.getOwned(type.getItem(tier));
			}
		}
	}

	/** The two weapon types the plan aims for: the selected pair, else the most advanced, staff first if required. */
	private List<WeaponType> chooseWeaponTypes(Gear gear, Set<WeaponType> wanted)
	{
		if (wanted.size() == 2)
			return List.copyOf(wanted);
		// Existing perfected weapons first, then usable upgrades, then existing weapons.
		List<WeaponType> types = new ArrayList<>(Arrays.asList(WeaponType.values()));
		types.sort(Comparator.<WeaponType>comparingInt(type ->
				(gear.hasPerfected(type) ? 1000 : 0) +
				(inventory.getHeld(type.getComponent()) > 0 ? 100 : 0) + gear.countOwnedTiers(type) * 10).reversed());
		if (goals.isStaffRequired())
		{
			types.remove(WeaponType.STAFF);
			types.add(0, WeaponType.STAFF);
		}
		return List.copyOf(types.subList(0, 2));
	}

	/** Of the chosen pair: a perfected weapon wielded, perfected weapons, upgrades (perfected or component held). */
	private void countSelectedWeapons(Gear gear)
	{
		for (WeaponType type : gear.desired)
			gear.wieldedPerfected |= inventory.getEquipped(type.getPerfected()) > 0;
		for (WeaponType type : gear.desired)
		{
			if (gear.hasPerfected(type))
				gear.perfected++;
			if (gear.hasPerfected(type) || inventory.getHeld(type.getComponent()) > 0)
				gear.upgrades++;
		}
	}

	/** The frames and weapon shards the chosen pair still needs. */
	private void priceSelectedWeapons(Gear gear)
	{
		for (WeaponType type : gear.desired)
		{
			if (gear.getOwnedTier(type) == null)
			{
				gear.frameTarget++;
			}
			// July 2026: T1 free, T2 costs 50, T3 component upgrade free.
			if (Tier.ATTUNED.isAbove(gear.getOwnedTier(type)))
			{
				gear.weaponShards += HomeCraftPlan.ATTUNE_SHARDS;
			}
		}

		// A non-staff final pair can still require an attuned staff for the demi trip.
		boolean needsPrepStaff = !gear.desired.contains(WeaponType.STAFF) && gear.upgrades < 2 && !hasAttunedStaff();
		if (needsPrepStaff)
		{
			gear.weaponShards += HomeCraftPlan.ATTUNE_SHARDS;
			if (inventory.getOwned(GAUNTLET_MAGIC_T1_HM) == 0)
				gear.frameTarget++;
		}
	}

	private void putRows(Gear gear, Supplies supplies)
	{
		int armorMaterials = ArmorUpgrades.sumMaterials(gear.armorSteps);
		int shards = ArmorUpgrades.sumShards(gear.armorSteps) + gear.weaponShards
				+ Math.max(0, supplies.batches - supplies.vials) * Recipes.SHARDS_PER_VIAL
				+ PotionShardBudget.getShardsForDust(supplies.batches * Recipes.DUST_PER_POTION - supplies.dust);

		put(Requirement.ORE, inventory.getHeld(GAUNTLET_ORE_HM), armorMaterials);
		put(Requirement.BARK, inventory.getHeld(GAUNTLET_BARK_HM), armorMaterials);
		put(Requirement.LINUM_TIRINUM, inventory.getHeld(GAUNTLET_FIBRE_HM), armorMaterials);
		put(Requirement.SHARDS, inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM), shards);
		put(Requirement.FRAMES, inventory.getHeld(GAUNTLET_GENERIC_COMPONENT_HM), gear.frameTarget);
		put(Requirement.UPGRADES, gear.upgrades, 2);
		put(Requirement.GRYM_LEAVES, inventory.getHeld(GAUNTLET_HERB_HM), Math.max(0, supplies.batches - supplies.unfinished));
		put(Requirement.VIALS, supplies.vials, supplies.batches);
		put(Requirement.DUST, supplies.dust, supplies.batches * Recipes.DUST_PER_POTION);
		put(Requirement.FISH, supplies.raw + supplies.cooked, foodTarget);
		put(Requirement.COOKED, supplies.cooked, foodTarget);
		put(Requirement.POTIONS, goals.getCraftedPotions(), goals.getLeafTarget());
		put(Requirement.ARMOR, gear.getArmor(), ArmorUpgrades.getStepCount(getArmorGoal()));
		put(Requirement.WEAPONS, gear.perfected, 2);
	}

	/**
	 * Choose one affordable next recipe in the menu. Basic weapons (free from a frame), then the upgrades, then T1
	 * armor (the attuned staff comes before armor), vials, and last the goal's next armor upgrade from spare shards.
	 */
	private void addCrafts(Gear gear, Supplies supplies)
	{
		addWeaponCrafts(gear, true);
		addWeaponCrafts(gear, false);
		for (ArmorUpgrades.Step step : gear.armorSteps)
			if (!ArmorUpgrades.isUpgrade(step) && canAffordArmor(step))
				craftItems.add(step.itemId);
		if (supplies.batches > supplies.vials && PotionShardBudget.canBuyVial(this, inventory))
			craftItems.add(GAUNTLET_VIAL_EMPTY_HM);
		ArmorUpgrades.Step upgrade = findNextUpgrade();
		if (upgrade != null && canAffordArmor(upgrade) && upgrade.shards <= getUpgradeShardSpare())
			craftItems.add(upgrade.itemId);
		craftItems.removeIf(itemId -> !HomeCraftPlan.canFitRecipe(inventory, itemId));
	}

	/** Ready, or the equip task, or a full inventory with nothing craftable taking over; the wording is in Instructions. */
	private void chooseInventoryAction()
	{
		ready = gear.armorSteps.isEmpty() && gear.armorWorn && gear.perfected >= 2 && gear.wieldedPerfected
				&& !isShort(Requirement.COOKED) && !isShort(Requirement.POTIONS);
		boolean equip = !ready && (gear.armorToEquip || gear.perfected >= 1 && !gear.wieldedPerfected);
		inventoryAction = equip ? InventoryAction.EQUIP : InventoryAction.NONE;
		if (!ready && !equip && inventory.getFreeSlots() == 0 && !canCraft() && !isFullBagHarmless())
			inventoryAction = InventoryAction.MAKE_SPACE;
	}

	/** Deferred upgrades with a full bag: cooking fills no slot and the upgrades go in place, so only stored fish want one. */
	public boolean isFullBagHarmless()
	{
		return upgradesDeferred && (hasFishToCook() || storedFood == 0);
	}

	private void put(Requirement resource, int have, int target)
	{
		rows.put(resource, new Row(have, target));
	}

	/**
	 * The next affordable tier of the weapons HomeCraftPlan makes: the staff, and any other selected weapon once its
	 * upgrade component is held, so a spare frame waits for the drop that decides it. A staff outside the pair still
	 * makes the demi hunt, up to attuned.
	 */
	private void addWeaponCrafts(Gear gear, boolean craftBasic)
	{
		for (WeaponType type : gear.desired)
		{
			boolean hasComponent = inventory.getHeld(type.getComponent()) > 0;
			if (type == WeaponType.STAFF || hasComponent)
				addWeaponCraft(gear, type, craftBasic, hasComponent);
		}
		if (!gear.desired.contains(WeaponType.STAFF) && needsOpeningStaff())
			addWeaponCraft(gear, WeaponType.STAFF, craftBasic, false);
	}

	/** The basic tier (from a frame) or the shard/component upgrade: the tier above the one owned (Gear.tiers). */
	private void addWeaponCraft(Gear gear, WeaponType type, boolean craftBasic, boolean canPerfect)
	{
		Tier owned = gear.getOwnedTier(type);
		Tier next = owned == null ? Tier.BASIC : owned.getNext();
		if (next == null)
			return;
		if (craftBasic ? next == Tier.BASIC && inventory.getHeld(GAUNTLET_GENERIC_COMPONENT_HM) > 0
				: next == Tier.ATTUNED
				&& inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) >= HomeCraftPlan.ATTUNE_SHARDS
				|| next == Tier.PERFECTED && canPerfect)
			craftItems.add(type.getItem(next));
	}

	private boolean isShort(Requirement resource) { return getRow(resource).getMissing() > 0; }

	/** The armor goal's crafts not yet made from held or worn pieces (ground pieces not counted), cheapest first. */
	public List<ArmorUpgrades.Step> findArmorSteps() { return ArmorUpgrades.owned(inventory).findSteps(getArmorGoal()); }

	/** The T1 base's crafts not yet made: a basic piece in every slot, whatever goal above None. The None goal has no base. */
	public List<ArmorUpgrades.Step> findBaseArmorSteps() { return ArmorUpgrades.owned(inventory).findSteps(ArmorUpgrades.getBaseGoal(getArmorGoal())); }

	/** Every slot holds or wears a piece (crafted pieces on the ground count): the T1 base the upgrades build on. */
	public boolean isBaseArmorComplete()
	{
		return ArmorUpgrades.owned(inventory).withGround(groundArmor).isComplete(ArmorUpgrades.getBaseGoal(getArmorGoal()));
	}

	/** The goal's armor upgrades still to make (steps above basic), cheapest first. */
	public List<ArmorUpgrades.Step> findArmorUpgrades()
	{
		List<ArmorUpgrades.Step> upgrades = new ArrayList<>();
		for (ArmorUpgrades.Step step : ArmorUpgrades.owned(inventory).withGround(groundArmor).findSteps(getArmorGoal()))
			if (ArmorUpgrades.isUpgrade(step))
				upgrades.add(step);
		return upgrades;
	}

	/** The goal's next upgrade once the T1 base and the opening staff are made, else null. */
	public ArmorUpgrades.Step findNextUpgrade()
	{
		List<ArmorUpgrades.Step> upgrades = findArmorUpgrades();
		boolean staffReady = !needsOpeningStaff() || hasAttunedStaff();
		return upgrades.isEmpty() || !isBaseArmorComplete() || !staffReady ? null : upgrades.get(0);
	}

	/** These shards would pay for the next upgrade the held shards can't, with its materials already held. */
	public boolean canShardsFundNextUpgrade(int shards)
	{
		ArmorUpgrades.Step next = findNextUpgrade();
		if (next == null || getUpgradeShardSpare() >= next.shards || getUpgradeShardSpare() + shards < next.shards)
			return false;
		return inventory.getHeld(GAUNTLET_ORE_HM) >= next.materials && inventory.getHeld(GAUNTLET_BARK_HM) >= next.materials
				&& inventory.getHeld(GAUNTLET_FIBRE_HM) >= next.materials;
	}

	/**
	 * Held shards beyond everything the plan still needs except the armor upgrades: the staff, T1 armor, both
	 * weapons' attuned tiers, vials and dust are all done or funded first, so upgrades only spend what is left.
	 */
	public int getUpgradeShardSpare()
	{
		int others = getRow(Requirement.SHARDS).target - ArmorUpgrades.sumShards(findArmorUpgrades());
		return inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) - Math.max(0, others);
	}

	public int getArmorGoal() { return goals.getArmorGoal(); }

	/** The held shards and materials pay for this armor craft on their own. */
	private boolean canAffordArmor(ArmorUpgrades.Step step)
	{
		return inventory.getHeld(GAUNTLET_ORE_HM) >= step.materials && inventory.getHeld(GAUNTLET_BARK_HM) >= step.materials
				&& inventory.getHeld(GAUNTLET_FIBRE_HM) >= step.materials && inventory.getHeld(GAUNTLET_CRYSTAL_SHARD_HM) >= step.shards;
	}

	/**
	 * The same plan aimed at a lower food target (FoodTargetLatch, once the attempt's quota was met). Stored food and ground
	 * armor credits are not carried over; callers apply them again.
	 */
	public Requirements withFoodTarget(int target)
	{
		return new Requirements(inventory, goals.withFoodTarget(target));
	}

	public Requirements withStoredFood(int quantity)
	{
		Requirements copy = new Requirements(this);
		copy.storedFood = Math.max(0, quantity);
		copy.put(Requirement.FISH, inventory.getHeld(GAUNTLET_RAW_FOOD) + inventory.getHeldCookedFood() + copy.storedFood, foodTarget);
		return copy;
	}

	/**
	 * Armor slots whose piece lies on the ground (crafted into a full inventory): the bowl no longer offers that
	 * piece and its materials/shards leave the targets, so the plan picks it up instead of remaking it.
	 */
	public Requirements withGroundArmor(Set<ArmorSlot> slots)
	{
		if (slots.isEmpty() && groundArmor.isEmpty())
			return this;
		Requirements copy = new Requirements(this);
		copy.groundArmor = Collections.unmodifiableSet(new HashSet<>(slots));
		if (slots.isEmpty())
			return copy;
		copy.craftItems.removeIf(itemId -> slots.contains(ArmorUpgrades.getSlot(itemId)));
		List<ArmorUpgrades.Step> held = findArmorSteps();
		List<ArmorUpgrades.Step> withGround = ArmorUpgrades.owned(inventory).withGround(slots).findSteps(getArmorGoal());
		int materials = ArmorUpgrades.sumMaterials(held) - ArmorUpgrades.sumMaterials(withGround);
		int shards = ArmorUpgrades.sumShards(held) - ArmorUpgrades.sumShards(withGround);
		for (Requirement resource : Requirement.MATERIALS)
			copy.put(resource, getRow(resource).have, getRow(resource).target - materials);
		copy.put(Requirement.SHARDS, getRow(Requirement.SHARDS).have, getRow(Requirement.SHARDS).target - shards);
		return copy;
	}

	/** Deferred upgrades (time low) leave the gate: their pieces, shards and materials no longer hold back the food. */
	public boolean isReadyForStoredFood()
	{
		boolean armor = upgradesDeferred ? findBaseArmorSteps().isEmpty() : getRow(Requirement.ARMOR).isComplete();
		boolean gathering = upgradesDeferred ? isGatheringCompleteBesidesUpgrades() : isGatheringGearComplete();
		return armor && getRow(Requirement.WEAPONS).isComplete()
				&& getRow(Requirement.POTIONS).isComplete() && getRow(Requirement.FISH).isComplete()
				&& gathering && getRow(Requirement.GRYM_LEAVES).isComplete()
				&& getRow(Requirement.VIALS).isComplete() && getRow(Requirement.DUST).isComplete()
				&& !isInventoryAction();
	}

	/**
	 * Gear, potions and the fish quota are done, counting crafted armor that still lies on the ground: what remains is
	 * picking it up, equipping, cooking and food. Unlike isReadyForStoredFood (the stored-fish pickup gate), this doesn't
	 * wait for that pickup or an Equip task, so unneeded items get drop cues as soon as they stop being needed.
	 */
	public boolean isOnlyFoodLeft()
	{
		return ArmorUpgrades.owned(inventory).withGround(groundArmor).isComplete(getArmorGoal()) && getRow(Requirement.WEAPONS).isComplete()
				&& getRow(Requirement.POTIONS).isComplete() && getRow(Requirement.FISH).isComplete()
				&& isGatheringGearComplete() && getRow(Requirement.GRYM_LEAVES).isComplete()
				&& getRow(Requirement.VIALS).isComplete() && getRow(Requirement.DUST).isComplete();
	}

	/** isGatheringGearComplete without the armor upgrades' shards and materials. */
	private boolean isGatheringCompleteBesidesUpgrades()
	{
		for (Requirement resource : new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM, Requirement.SHARDS})
			if (getRow(resource).getMissing() > 0 && !isShortOnlyForUpgrades(resource))
				return false;
		return getRow(Requirement.FRAMES).isComplete() && getRow(Requirement.UPGRADES).isComplete();
	}

	/** The resource is short, but only by what the armor upgrades still need. */
	boolean isShortOnlyForUpgrades(Requirement resource)
	{
		List<ArmorUpgrades.Step> upgrades = findArmorUpgrades();
		int forUpgrades = resource == Requirement.SHARDS ? ArmorUpgrades.sumShards(upgrades) : ArmorUpgrades.sumMaterials(upgrades);
		Row row = getRow(resource);
		return row.getMissing() > 0 && row.have >= row.target - forUpgrades;
	}

	/**
	 * Time low with armor upgrades still to make (T2 goal): they move after cooking and the final food retrieval, and
	 * the bowl doesn't offer them while fish wait to be cooked or retrieved. The goal and the food target are unchanged.
	 */
	public Requirements withUpgradesDeferred()
	{
		if (upgradesDeferred || findArmorUpgrades().isEmpty())
			return this;
		Requirements copy = new Requirements(this);
		copy.upgradesDeferred = true;
		if (copy.isFoodBeforeUpgrades())
			copy.craftItems.removeIf(this::isArmorUpgrade);
		copy.toolDropsContinued = false;
		copy.equipAfterCrafts = false;
		copy.chooseInventoryAction();
		return copy;
	}

	public boolean isUpgradesDeferred() { return upgradesDeferred; }

	/** Deferred upgrades wait while raw fish need cooking or stored food can be retrieved. */
	private boolean isFoodBeforeUpgrades()
	{
		return upgradesDeferred && (hasFishToCook() || storedFood > 0 && isReadyForStoredFood());
	}

	private boolean hasFishToCook()
	{
		return inventory.getHeld(GAUNTLET_RAW_FOOD) > 0 && inventory.getHeld(GAUNTLET_FOOD) < foodTarget;
	}

	private boolean isArmorUpgrade(int itemId)
	{
		return findArmorUpgrades().stream().anyMatch(step -> step.itemId == itemId);
	}

	public boolean isGatheringGearComplete()
	{
		return getRow(Requirement.ORE).isComplete() && getRow(Requirement.BARK).isComplete()
				&& getRow(Requirement.LINUM_TIRINUM).isComplete() && getRow(Requirement.SHARDS).isComplete()
				&& getRow(Requirement.FRAMES).isComplete() && getRow(Requirement.UPGRADES).isComplete();
	}

	public Row getRow(Requirement resource) { return rows.get(resource); }
	/** The row from the inventory alone, before stored food and ground armor are credited. */
	Row getHeldRow(Requirement resource) { return heldRows.get(resource); }
	public boolean isReady() { return ready; }
	public int getFreeSlots() { return inventory.getFreeSlots(); }
	public boolean needsExploration()
	{
		return Arrays.stream(new Requirement[]{Requirement.ORE, Requirement.BARK, Requirement.LINUM_TIRINUM,
				Requirement.GRYM_LEAVES, Requirement.FISH, Requirement.SHARDS, Requirement.FRAMES,
				Requirement.UPGRADES})
				.anyMatch(this::isShort);
	}

	public boolean hasAttunedStaff()
	{
		return inventory.getOwned(GAUNTLET_MAGIC_T2_HM) > 0 || inventory.getOwned(GAUNTLET_MAGIC_T3_HM) > 0;
	}

	/**
	 * Any staff tier is owned. A basic staff is enough for the demi hunt: when the first home visit's shards don't
	 * cover the attuned upgrade, it waits for a home visit that happens anyway, normally funded by the demis' drops.
	 */
	public boolean hasStaff()
	{
		return inventory.getOwned(GAUNTLET_MAGIC_T1_HM) > 0 || hasAttunedStaff();
	}

	/** A staff is owned or its frame is held, so the first home visit can leave with one. */
	public boolean isStaffInHand()
	{
		return hasStaff() || inventory.getHeld(GAUNTLET_GENERIC_COMPONENT_HM) > 0;
	}

	public boolean needsStaffOrb() { return goals.isStaffRequired() && !hasStaffUpgrade(); }

	/**
	 * Whether a bow or halberd upgrade component is still worth collecting for the selected pair. Staff +
	 * either (default): the bowstring is primary and a spike is only a fallback while no bowstring is held; once either
	 * is held that is the second weapon. Fixed or committed pairs follow their selection.
	 */
	public boolean needsUpgrade(WeaponType type)
	{
		if (getRow(Requirement.UPGRADES).isComplete() || hasUpgrade(type))
			return false;
		if (goals.getSelection().size() >= 2)
			return goals.getSelection().contains(type);
		if (goals.isStaffRequired() && type != WeaponType.STAFF)
			return !hasUpgrade(WeaponType.BOW) && !hasUpgrade(WeaponType.HALBERD);
		return true;
	}

	/** Staff + either, nothing committed yet: the bow is the preferred second weapon. */
	public boolean isBowPreferredSecond()
	{
		return goals.isStaffRequired() && goals.getSelection().size() < 2 && !hasUpgrade(WeaponType.BOW) && !hasUpgrade(WeaponType.HALBERD);
	}

	public boolean hasStaffUpgrade()
	{
		return inventory.getHeld(GAUNTLET_MAGIC_COMPONENT_HM) > 0 || inventory.getOwned(GAUNTLET_MAGIC_T3_HM) > 0;
	}

	public Goals getGoals() { return goals; }
	public boolean isStaffRequired() { return goals.isStaffRequired(); }
	public WeaponPair getWeaponPair() { return goals.getWeaponPair(); }
	public Set<WeaponType> getSelection() { return goals.getSelection(); }
	public boolean needsOpeningStaff()
	{
		return gear.desired.contains(WeaponType.STAFF) || !getRow(Requirement.UPGRADES).isComplete();
	}

	public int getFoodTarget() { return foodTarget; }
	public int getFoodCap() { return foodCap; }
	public boolean isFoodCapped() { return goals.getFoodTarget() > foodCap; }

	public int getLeafTarget() { return goals.getLeafTarget(); }
	public int getCraftedPotions() { return goals.getCraftedPotions(); }
	public int getStoredFood() { return storedFood; }
	public Inventory getInventory() { return inventory; }
	public List<WeaponType> getDesiredWeaponTypes() { return gear.desired; }
	public boolean hasUpgrade(WeaponType type)
	{
		return inventory.getHeld(type.getComponent()) > 0 || inventory.getOwned(type.getPerfected()) > 0;
	}

	public Set<WeaponType> getMissingUpgrades()
	{
		Set<WeaponType> result = EnumSet.noneOf(WeaponType.class);
		for (WeaponType type : gear.desired)
			if (!hasUpgrade(type))
				result.add(type);
		return result;
	}

	/** Optional tool drops must not replace the next prep task, even at 28 occupied slots. */
	public Requirements withToolDropsContinued()
	{
		if (inventoryAction != InventoryAction.MAKE_SPACE)
			return this;
		Requirements copy = new Requirements(this);
		copy.inventoryAction = InventoryAction.NONE;
		copy.toolDropsContinued = true;
		return copy;
	}

	/** The bowl stays the task and the equip task becomes Next (HomeVisit.craftingBeforeEquip decides when). */
	Requirements withEquipAfterCrafts()
	{
		Requirements copy = new Requirements(this);
		copy.inventoryAction = InventoryAction.NONE;
		copy.equipAfterCrafts = true;
		return copy;
	}

	boolean isToolDropsContinued() { return toolDropsContinued; }
	boolean isEquipAfterCrafts() { return equipAfterCrafts; }
	Set<ArmorSlot> getGroundArmor() { return groundArmor; }

	/** The whole home visit's crafts, in bowl order, from the current holdings. */
	public HomeCraftPlan computeHomeCrafts()
	{
		return computeHomeCrafts(inventory);
	}

	private HomeCraftPlan computeHomeCrafts(Inventory holdings)
	{
		return new HomeCraftPlan(holdings, getRow(Requirement.VIALS).getMissing(), needsOpeningStaff(), gear.desired, groundArmor,
				isFoodBeforeUpgrades() ? Collections.emptyList() : findArmorUpgrades(), getUpgradeShardSpare(), ArmorUpgrades.getBaseGoal(getArmorGoal()));
	}

	/** The home visit's crafts still short of a slot once these items are dropped. */
	public int countCraftsWaitingAfterDrops(Map<Integer, Integer> drops)
	{
		return computeHomeCrafts(inventory.without(drops)).countWaitingForSpace();
	}

	public boolean canCraft() { return !craftItems.isEmpty(); }

	/** The next affordable recipes, labeled only on request: the trip search reads canCraft and never this. */
	public List<Craft> getCrafts()
	{
		List<Craft> crafts = new ArrayList<>(craftItems.size());
		for (int itemId : craftItems)
			crafts.add(new Craft(itemId, Instructions.getCraftLabel(this, itemId)));
		return Collections.unmodifiableList(crafts);
	}

	/**
	 * Whether this attempt still wants the bowl recipe at all, ready or not: vials while the potion target is short, the
	 * armor goal's upgrade steps not yet reached, the opening staff up to attuned, and every tier of a selected weapon
	 * not yet owned. Anything else (teleport crystal, armor above the goal, unselected weapons) is never crafted; the
	 * craft overlay grays it out.
	 */
	public boolean needsCraft(int itemId)
	{
		if (itemId == GAUNTLET_VIAL_EMPTY_HM)
			return getRow(Requirement.VIALS).getMissing() > 0;
		if (ArmorUpgrades.getSlot(itemId) != null)
		{
			List<ArmorUpgrades.Step> steps = ArmorUpgrades.owned(inventory).withGround(groundArmor).findSteps(getArmorGoal());
			return steps.stream().anyMatch(step -> step.itemId == itemId);
		}
		for (WeaponType type : WeaponType.values())
			for (Tier tier : Tier.values())
			{
				if (itemId != type.getItem(tier))
					continue;
				boolean wanted = gear.desired.contains(type) || (type == WeaponType.STAFF && tier != Tier.PERFECTED && needsOpeningStaff());
				for (Tier owned = tier; owned != null && wanted; owned = owned.getNext())
					wanted = inventory.getOwned(type.getItem(owned)) == 0;
				return wanted;
			}
		return false;
	}

	/**
	 * The recipe a bowl slot showing this armor piece stands for: upgrades share the basic piece's slots, so any tier's
	 * icon means the slot's next goal step. Other items, and slots whose goal is reached, keep their own id.
	 */
	public int getArmorRecipe(int itemId)
	{
		ArmorSlot slot = ArmorUpgrades.getSlot(itemId);
		if (slot == null)
			return itemId;
		for (ArmorUpgrades.Step step : ArmorUpgrades.owned(inventory).withGround(groundArmor).findSteps(getArmorGoal()))
			if (step.slot == slot)
				return step.itemId;
		return itemId;
	}

	public boolean needsWater() { return needsWater; }
	/** Raw or cooked paddlefish held: the range has a cook or store job at home. */
	public boolean hasFish()
	{
		return inventory.getHeld(GAUNTLET_RAW_FOOD) > 0 || inventory.getHeld(GAUNTLET_FOOD) > 0;
	}
	public InventoryAction getInventoryAction() { return inventoryAction; }
	public boolean isInventoryAction() { return inventoryAction != InventoryAction.NONE; }
	/** The current task is making inventory space (full, nothing craftable); home drop cues may then apply. */
	public boolean needsInventorySpace() { return inventoryAction == InventoryAction.MAKE_SPACE; }
	int getSupplySlotsShortfall() { return supplySlotsShortfall; }

	@Override
	public boolean equals(Object other)
	{
		if (!(other instanceof Requirements))
			return false;
		Requirements that = (Requirements) other;
		return goals.equals(that.goals) && inventory.getHeldItems().equals(that.inventory.getHeldItems())
				&& inventory.getEquippedItems().equals(that.inventory.getEquippedItems()) && inventory.getFreeSlots() == that.inventory.getFreeSlots()
				&& rows.equals(that.rows) && heldRows.equals(that.heldRows) && craftItems.equals(that.craftItems)
				&& storedFood == that.storedFood && groundArmor.equals(that.groundArmor) && upgradesDeferred == that.upgradesDeferred
				&& ready == that.ready && inventoryAction == that.inventoryAction
				&& toolDropsContinued == that.toolDropsContinued && equipAfterCrafts == that.equipAfterCrafts;
	}

	@Override
	public int hashCode() { return Objects.hash(goals, rows, craftItems, storedFood, inventoryAction); }
}
