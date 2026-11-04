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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;
import static net.runelite.api.gameval.ItemID.GAUNTLET_AXE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CHESTPLATE_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_GENERIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HARPOON_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HELMET_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_HERB_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MELEE_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PESTLE;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PICKAXE_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_PLATELEGS_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RANGED_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RAW_FOOD;
import static net.runelite.api.gameval.ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM;

/**
 * Observed ground stacks, retained through scene changes, never invented from a click. The client's item events say
 * which tiles changed, and each tick's observation reads those and the remembered tiles again (TickHandler). The events
 * also keep each tile's spawn order for the overlay, the way RuneLite's Ground Items plugin keeps its own table: one
 * entry per item id, first spawn fixes its position, partial despawns keep it and a full despawn removes it. Ground
 * Items stacks its name lines in this order, so following it puts helper names on the same line Ground Items draws.
 * Only the overlay reads it; the piles themselves are kept in item id order ({@link #observe}).
 */
public final class GroundLoot
{
	public static final class Entry
	{
		public final WorldPoint point;
		public final int id, quantity, expires;
		public final boolean home;

		public Entry(WorldPoint point, int id, int quantity, int expires, boolean home)
		{
			this.point = point;
			this.id = id;
			this.quantity = quantity;
			this.expires = expires;
			this.home = home;
		}
	}

	private static final Comparator<Entry> PILE_ORDER = Comparator.<Entry>comparingInt(e -> e.id)
		.thenComparingInt(e -> e.quantity)
		.thenComparingInt(e -> e.expires);

	private final Map<WorldPoint, List<Entry>> tiles = new HashMap<>();
	private final Set<WorldPoint> deferredFood = new HashSet<>();
	private final Set<WorldPoint> changedTiles = new HashSet<>();
	private final Map<WorldPoint, LinkedHashMap<Integer, Integer>> spawnOrder = new HashMap<>();

	public void deferDroppedFood(WorldPoint point)
	{
		if (point != null)
			deferredFood.add(point);
	}

	public boolean isStoredFood(Entry entry)
	{
		return isFood(entry.id) && (entry.home || deferredFood.contains(entry.point));
	}

	public static boolean isFood(int id) { return id == GAUNTLET_RAW_FOOD || id == GAUNTLET_FOOD; }
	public void reset()
	{
		tiles.clear();
		deferredFood.clear();
		changedTiles.clear();
	}

	/**
	 * Whether a bare tile really is bare. The client shows ground items only within three 8x8 zones of the player, so
	 * a loaded tile farther out proves nothing, whichever item event or scan led to reading it. Two zones leaves a margin.
	 */
	public static boolean isEmptyTileTrusted(WorldPoint tile, WorldPoint player)
	{
		return player != null && Math.max(Math.abs((tile.getX() >> 3) - (player.getX() >> 3)), Math.abs((tile.getY() >> 3) - (player.getY() >> 3))) <= 2;
	}

	/**
	 * Replaces what is remembered at a tile; an empty observation forgets it (expire would drop it anyway). A pile is
	 * kept in item id order: the client's tile list has an order a recording doesn't hold, and pickups and the trip
	 * plan read this table, so they must not depend on it.
	 */
	public void observe(WorldPoint p, List<Entry> entries)
	{
		if (entries.isEmpty())
		{
			tiles.remove(p);
			return;
		}
		List<Entry> pile = new ArrayList<>(entries);
		pile.sort(PILE_ORDER);
		tiles.put(p, pile);
	}

	public void recordSpawn(WorldPoint point, int id, int quantity)
	{
		changedTiles.add(point);
		spawnOrder.computeIfAbsent(point, p -> new LinkedHashMap<>()).merge(id, quantity, Integer::sum);
	}

	public void recordDespawn(WorldPoint point, int id, int quantity)
	{
		changedTiles.add(point);
		Map<Integer, Integer> items = spawnOrder.get(point);
		Integer held = items == null ? null : items.get(id);
		if (held == null)
			return;
		if (held <= quantity)
			items.remove(id);
		else
			items.put(id, held - quantity);
		if (items.isEmpty())
			spawnOrder.remove(point);
	}

	public void recordQuantityChange(WorldPoint point, int id, int oldQuantity, int newQuantity)
	{
		changedTiles.add(point);
		Map<Integer, Integer> items = spawnOrder.get(point);
		if (items != null && items.containsKey(id))
			items.put(id, items.get(id) + newQuantity - oldQuantity);
	}

	/** The scene is gone, so the next piles spawn in a new order. Remembered piles stay. */
	public void clearSpawnOrder() { spawnOrder.clear(); }

	/** Live ids in remembered spawn order; ids seen live but never tracked follow in their tile order. */
	public List<Integer> getStack(WorldPoint point, Collection<Integer> live)
	{
		List<Integer> result = new ArrayList<>();
		Map<Integer, Integer> items = spawnOrder.get(point);
		if (items != null)
			for (Integer id : items.keySet())
				if (live.contains(id))
					result.add(id);
		for (Integer id : live)
			if (!result.contains(id))
				result.add(id);
		return result;
	}

	/**
	 * The tiles this tick's observation reads: those changed since the last one and those remembered, which is every
	 * tile that can hold a pile the table doesn't already match. Takes the changed tiles; see {@link #markUnread}.
	 */
	public List<WorldPoint> findTilesToRead()
	{
		Set<WorldPoint> read = new HashSet<>(tiles.keySet());
		read.addAll(changedTiles);
		changedTiles.clear();
		return new ArrayList<>(read);
	}

	/** A pile that can't be remembered yet (its room isn't discovered): the tile is read again next tick. */
	public void markUnread(WorldPoint point) { changedTiles.add(point); }

	/** A full scan reads every loaded tile, so the changes so far need no reading of their own. */
	public void clearChangedTiles() { changedTiles.clear(); }

	public void expire(int tick)
	{
		for (List<Entry> list : tiles.values())
			list.removeIf(e -> e.expires > 0 && e.expires <= tick);
		tiles.values().removeIf(List::isEmpty);
	}

	public List<Entry> getEntries()
	{
		List<Entry> all = new ArrayList<>();
		tiles.values().forEach(all::addAll);
		return all;
	}

	public boolean hasExpiringHomeFood(int tick)
	{
		return getEntries().stream().anyMatch(e -> isStoredFood(e) && e.expires > tick && e.expires - tick <= 100);
	}

	public int countHomeFood()
	{
		return getEntries().stream().filter(this::isStoredFood).mapToInt(e -> e.quantity).sum();
	}

	public int computePickupQuantity(Entry e, Requirements plan, Inventory inv, boolean retrieveFood)
	{
		if (!isNeeded(e, plan, inv, retrieveFood))
			return 0;
		int wanted = e.quantity;
		Requirement resource = Requirement.ofItem(e.id);
		if (resource == Requirement.FISH)
			wanted = Math.min(wanted, isStoredFood(e)
					? Math.max(0, plan.getFoodTarget() - inv.getHeld(GAUNTLET_RAW_FOOD) - inv.getHeldCookedFood())
					: plan.getRow(Requirement.FISH).getMissing());
		else
			wanted = Math.min(wanted, resource == null ? 1 : plan.getRow(resource).getMissing());
		boolean stacked = Simulation.isStacked(e.id);
		return Math.min(wanted, stacked ? (inv.getHeld(e.id) > 0 || inv.getFreeSlots() > 0 ? wanted : 0) : inv.getFreeSlots());
	}

	/**
	 * computePickupQuantity for every entry of e's item on e's tile together. Unstacked items are one entry each, so the
	 * stored fish read "Pick up 1 cooked fish" with 8 on the tile and 7 wanted.
	 */
	public int computeTilePickupQuantity(Entry e, Requirements plan, Inventory inv, boolean retrieveFood)
	{
		int total = 0;
		for (Entry other : tiles.getOrDefault(e.point, Collections.emptyList()))
			if (other.id == e.id && other.home == e.home)
				total += other.quantity;
		return computePickupQuantity(new Entry(e.point, e.id, Math.max(total, e.quantity), e.expires, e.home), plan, inv, retrieveFood);
	}

	public boolean isNeeded(Entry e, Requirements plan, Inventory inv, boolean retrieveFood)
	{
		Requirement resource = Requirement.ofItem(e.id);
		if (resource == Requirement.FISH)
		{
			// Fish an enemy dropped count as gathering while the quota is short.
			if (!isStoredFood(e))
				return plan.getRow(Requirement.FISH).getMissing() > 0;
			return (retrieveFood && plan.isReadyForStoredFood())
					&& inv.getHeld(GAUNTLET_RAW_FOOD) + inv.getHeld(GAUNTLET_FOOD) < plan.getRow(Requirement.FISH).target;
		}
		if (resource != null)
			return plan.getRow(resource).getMissing() > 0;
		// A tool only while its work is left (Simulation.isToolDone) and none is held.
		Requirement tooled = Requirement.ofTool(e.id);
		if (tooled != null)
			return !Simulation.isToolDone(plan, tooled) && inv.getHeld(e.id) == 0;
		switch (e.id)
		{
			case GAUNTLET_MAGIC_COMPONENT_HM:
				return plan.getRow(Requirement.UPGRADES).getMissing() > 0 && !plan.hasStaffUpgrade();
			case GAUNTLET_RANGED_COMPONENT_HM:
				return plan.needsUpgrade(WeaponType.BOW);
			// Spike only as the fallback: never while a wanted bowstring is lying in an observed pile.
			case GAUNTLET_MELEE_COMPONENT_HM:
				return plan.needsUpgrade(WeaponType.HALBERD) && !(plan.isBowPreferredSecond() && plan.needsUpgrade(WeaponType.BOW)
						&& getEntries().stream().anyMatch(o -> o.id == GAUNTLET_RANGED_COMPONENT_HM && o.quantity > 0));
			// A dropped crystal only while none is held, a slot is spare after the remaining fish and work is left away from home.
			case GAUNTLET_TELEPORT_CRYSTAL_HM:
				return inv.getHeld(e.id) == 0 && !plan.isReady() && !plan.isReadyForStoredFood() && !plan.isUpgradesDeferred() && hasAwayWorkLeft(plan)
						&& inv.getFreeSlots() > plan.getRow(Requirement.FISH).getMissing();
			// Crafting armor with a full inventory puts the piece on the ground (its ore/bark/linum tirinum stack frees no
			// slot). Pick it up rather than remake it.
			default:
				ArmorSlot slot = ArmorUpgrades.getSlot(e.id);
				return slot != null && !ArmorUpgrades.owned(inv).hasPiece(slot);
		}
	}

	/** Armor slots whose piece lies on an observed tile while none is held or worn. */
	public Set<ArmorSlot> findArmorOnGround(Inventory inv)
	{
		Set<ArmorSlot> slots = EnumSet.noneOf(ArmorSlot.class);
		for (Entry e : getEntries())
		{
			ArmorSlot slot = ArmorUpgrades.getSlot(e.id);
			if (slot != null && !ArmorUpgrades.owned(inv).hasPiece(slot))
				slots.add(slot);
		}
		return slots;
	}

	/**
	 * Something still to gather or win away from home, so another trip out may need a crystal home. Fish alone don't
	 * count: the last fish never justify fetching a crystal.
	 */
	private static boolean hasAwayWorkLeft(Requirements plan)
	{
		for (Requirement resource : new Requirement[]{Requirement.UPGRADES, Requirement.ORE, Requirement.BARK,
				Requirement.LINUM_TIRINUM, Requirement.GRYM_LEAVES, Requirement.FRAMES, Requirement.SHARDS})
			if (plan.getRow(resource).getMissing() > 0)
				return true;
		return false;
	}

	/**
	 * Whether a needed pile may send the player to another room. A spare crystal is never worth the walk, nor is fish an
	 * enemy dropped: the fishing spots cover the quota. Stored fish is the final food and stays worth it.
	 */
	public boolean isWorthRoomTrip(Entry e)
	{
		return e.id != GAUNTLET_TELEPORT_CRYSTAL_HM && (!isFood(e.id) || isStoredFood(e));
	}

	/** The short name of a needed item for a pickup line: "orb", "weapon frame", "grym leaf". */
	public static String getLootName(int id)
	{
		switch (id)
		{
			case GAUNTLET_RAW_FOOD:
				return "raw fish";
			case GAUNTLET_FOOD:
				return "cooked fish";
			case GAUNTLET_MAGIC_COMPONENT_HM:
				return "orb";
			case GAUNTLET_RANGED_COMPONENT_HM:
				return "bowstring";
			case GAUNTLET_MELEE_COMPONENT_HM:
				return "spike";
			case GAUNTLET_TELEPORT_CRYSTAL_HM:
				return "teleport crystal";
			// Named, not "needed item", so the pickup line says what it is.
			case GAUNTLET_GENERIC_COMPONENT_HM:
				return "weapon frame";
			case GAUNTLET_CRYSTAL_SHARD_HM:
				return "shards";
			case GAUNTLET_AXE_HM:
				return "axe";
			case GAUNTLET_PICKAXE_HM:
				return "pickaxe";
			case GAUNTLET_HARPOON_HM:
				return "harpoon";
			case GAUNTLET_PESTLE:
				return "pestle";
			case GAUNTLET_HERB_HM:
				return "grym leaf";
			case GAUNTLET_HELMET_T1_HM:
				return "helm";
			case GAUNTLET_CHESTPLATE_T1_HM:
				return "body";
			case GAUNTLET_PLATELEGS_T1_HM:
				return "legs";
			default:
				return "needed item";
		}
	}

	/** The count and name for a pickup line: "1 grym leaf", "3 grym leaves", "25 shards", "7 cooked fish". */
	public static String formatLootCount(int id, int quantity)
	{
		return quantity + " " + pluralize(getLootName(id), quantity);
	}

	private static String pluralize(String name, int quantity)
	{
		if (quantity <= 1 || name.endsWith("fish") || name.endsWith("s"))
			return name;
		if (name.endsWith("leaf"))
			return name.substring(0, name.length() - 1) + "ves";
		return name + "s";
	}
}
