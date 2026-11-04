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

import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.Tier;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.StringJoiner;

/** Rank observed demi rooms by missing loadout upgrades. */
public final class DemiBossPlan
{
	private final Requirements plan;
	private final DemiBossTracker history;

	public DemiBossPlan(Requirements plan, DemiBossTracker history)
	{
		this.plan = plan;
		this.history = history;
	}

	/**
	 * Whether this weapon's component has already dropped: it is owned, it was seen on the ground (held, left there or
	 * gone since), or its demi type has an observed kill (a first kill drops it unless it had already dropped).
	 */
	private boolean hasReceivedUpgrade(WeaponType type)
	{
		return plan.hasUpgrade(type) || history != null && (history.hasDropped(type) || history.count(EnemyType.ofDrop(type)) > 0);
	}

	/**
	 * Whether this demi drops an alternate component rather than its own: its own has already dropped, so a dark beast
	 * killed after a second bear's bowstring, picked up or not, drops the orb.
	 */
	private boolean dropsAlternateUpgrade(WeaponType type) { return hasReceivedUpgrade(type); }

	/** Components already dropped or owned. A repeat or alternate drop is a component not among these. */
	private Set<WeaponType> computeReceivedUpgrades()
	{
		Set<WeaponType> received = EnumSet.noneOf(WeaponType.class);
		for (WeaponType type : WeaponType.values())
			if (hasReceivedUpgrade(type))
				received.add(type);
		return received;
	}

	/** Components an alternate drop of this type can be; every unowned one if all were already received. */
	private Set<WeaponType> computeAlternates(WeaponType type)
	{
		Set<WeaponType> unreceived = EnumSet.allOf(WeaponType.class);
		unreceived.removeAll(computeReceivedUpgrades());
		unreceived.remove(type);
		if (!unreceived.isEmpty())
			return unreceived;
		Set<WeaponType> unowned = EnumSet.allOf(WeaponType.class);
		unowned.removeIf(plan::hasUpgrade);
		unowned.remove(type);
		return unowned;
	}

	private Set<WeaponType> computeMissingUpgrades()
	{
		if (plan.getRow(Requirement.UPGRADES).isComplete())
			return EnumSet.noneOf(WeaponType.class);
		Set<WeaponType> missing = EnumSet.allOf(WeaponType.class);
		missing.removeIf(plan::hasUpgrade);
		Inventory inv = plan.getInventory();
		Set<WeaponType> committed = EnumSet.noneOf(WeaponType.class);
		for (WeaponType type : plan.getDesiredWeaponTypes())
			for (Tier tier : Tier.values())
				if (inv.getOwned(type.getItem(tier)) > 0)
				{
					committed.add(type);
					break;
				}
		if (plan.getWeaponPair().isFixed())
			return plan.getMissingUpgrades();
		if (plan.isStaffRequired())
		{
			Set<WeaponType> wanted = EnumSet.noneOf(WeaponType.class);
			if (plan.needsStaffOrb())
				wanted.add(WeaponType.STAFF);
			if (plan.getRow(Requirement.UPGRADES).getMissing() > (plan.needsStaffOrb() ? 1 : 0))
			{
				Set<WeaponType> second = findCommon(committed, WeaponType.SECOND_WEAPONS);
				wanted.addAll(second.isEmpty() ? WeaponType.SECOND_WEAPONS : second);
			}
			return findCommon(wanted, missing);
		}
		return committed.size() >= 2 ? findCommon(missing, committed) : missing;
	}

	/** The weapon types in both sets. */
	private static Set<WeaponType> findCommon(Set<WeaponType> first, Set<WeaponType> second)
	{
		Set<WeaponType> common = EnumSet.noneOf(WeaponType.class);
		common.addAll(first);
		common.retainAll(second);
		return common;
	}

	public int rank(EnemyType enemy)
	{
		WeaponType type = enemy.getDrop();
		Set<WeaponType> wanted = computeMissingUpgrades();
		if (type == null || wanted.isEmpty())
			return Integer.MAX_VALUE;
		int kills = history == null ? 0 : history.count(EnemyType.ofDrop(type));
		boolean alternate = dropsAlternateUpgrade(type);
		boolean nativeNeeded = wanted.contains(type);
		// Staff + either: dark beast (bowstring) before bear (spike), which stays a fallback.
		if (nativeNeeded && !alternate)
			return (plan.needsStaffOrb() || plan.hasStaff() && plan.getDesiredWeaponTypes().contains(WeaponType.STAFF))
					&& type == WeaponType.STAFF ? 0
					: type == WeaponType.HALBERD && plan.isBowPreferredSecond() ? 2 : 1;
		// A repeat, or a demi whose own component is owned, yields a component not yet received. Only a single
		// remaining alternative is specific; on a first kill a dragon's own orb still comes first.
		Set<WeaponType> alternatives = computeAlternates(type);
		boolean alternativeWanted = !Collections.disjoint(alternatives, wanted);
		if (alternate && alternativeWanted)
		{
			if (alternatives.size() != 1 || !alternatives.contains(WeaponType.STAFF) || !plan.needsStaffOrb())
				return 2;
			return kills > 0 ? 0 : 1;
		}
		if (nativeNeeded || !plan.hasUpgrade(type) || alternativeWanted)
			return 3; // Missing earlier loot/history never proves which drop comes next.
		return Integer.MAX_VALUE;
	}

	public int rank(GauntletRoom room)
	{
		if (computeMissingUpgrades().isEmpty())
			return Integer.MAX_VALUE;
		if (room.hasLivingDemiBoss())
		{
			int best = Integer.MAX_VALUE;
			for (GauntletRoom.Enemy e : room.getEnemies())
				if (e.isAlive())
					best = Math.min(best, rank(e.getType()));
			return best;
		}
		return room.isGuaranteedDemiSpawn() ? 4 : room.isPerimeter() ? 5 : Integer.MAX_VALUE;
	}

	public String getLabel(GauntletRoom room)
	{
		EnemyType chosen = chooseEnemyType(room);
		if (chosen == null)
			return room.isGuaranteedDemiSpawn() ? "Check guaranteed demi room" : "Check demi room";
		String name = chosen.getLabel();
		Set<WeaponType> drops = computeDropsFor(chosen.getDrop());
		if (drops.isEmpty())
			return "Check " + name + " for missing alternate upgrade";
		Set<WeaponType> wanted = findCommon(drops, computeMissingUpgrades());
		return wanted.isEmpty() ? "Check " + name + " room" : "Check " + name + " room for " + describeComponents(wanted);
	}

	/**
	 * In-room cue for the chosen living demi: "Defeat bear and collect spike". A repeat kill, or one whose own
	 * component is already owned, drops one of the components not yet received other than its own, so every wanted
	 * possibility is listed, e.g. "orb/bowstring". A drop that isn't wanted is not named: a first dark beast with the
	 * spike held for staff + either is just "Defeat dark beast", killed so the next one drops the orb.
	 */
	public String getObjective(GauntletRoom room)
	{
		EnemyType chosen = chooseEnemyType(room);
		if (chosen == null)
			return getLabel(room);
		String name = chosen.getLabel();
		Set<WeaponType> drops = computeDrops(room);
		if (drops.isEmpty())
			return "Defeat " + name + " and collect the upgrade";
		Set<WeaponType> wanted = computeWantedDrops(room);
		return wanted.isEmpty() ? "Defeat " + name : "Defeat " + name + " and collect " + describeComponents(wanted);
	}

	/** The chosen demi's possible drops (computeDrops) that are still wanted; empty for a fallback kill. */
	public Set<WeaponType> computeWantedDrops(GauntletRoom room)
	{
		return findCommon(computeDrops(room), computeMissingUpgrades());
	}

	/** The components of these weapon types, e.g. "orb/bowstring". */
	private static String describeComponents(Set<WeaponType> drops)
	{
		StringJoiner names = new StringJoiner("/");
		for (WeaponType type : WeaponType.values())
			if (drops.contains(type))
				names.add(type.getComponentLabel());
		return names.toString();
	}

	/** The living demi in room the hunt fights first (best rank); null when none is needed. */
	public EnemyType chooseEnemyType(GauntletRoom room)
	{
		EnemyType chosen = null;
		int best = Integer.MAX_VALUE;
		for (GauntletRoom.Enemy enemy : room.getEnemies())
			if (enemy.isAlive() && rank(enemy.getType()) < best)
			{
				best = rank(enemy.getType());
				chosen = enemy.getType();
			}
		return chosen;
	}

	/**
	 * Components the chosen demi can drop, by weapon type (staff orb, bow bowstring, halberd spike): its own on a first
	 * kill while that is not owned, else every component not yet received other than its own. Empty when no demi is
	 * chosen.
	 */
	public Set<WeaponType> computeDrops(GauntletRoom room)
	{
		EnemyType chosen = chooseEnemyType(room);
		return chosen == null ? EnumSet.noneOf(WeaponType.class) : computeDropsFor(chosen.getDrop());
	}

	/** Components a kill of this demi type can drop (see computeDrops). */
	private Set<WeaponType> computeDropsFor(WeaponType type)
	{
		if (!dropsAlternateUpgrade(type))
			return EnumSet.of(type);
		return computeAlternates(type);
	}

	public boolean isUseful(GauntletRoom room)
	{
		return room != null && room.hasLivingDemiBoss() && rank(room) < Integer.MAX_VALUE;
	}

	/** Useful and not outranked (see isOutranked) by a living demi already spotted in another room. */
	public boolean isUseful(GauntletMap map, GauntletRoom room)
	{
		return isUseful(room) && !isOutranked(map, chooseEnemyType(room), room);
	}

	/**
	 * Whether a better-ranked living demi spotted in another room drops every wanted component this demi could: a
	 * fallback dark beast whose bowstring is not wanted gives way to a dragon seen next door for the orb, but a dark
	 * beast for a wanted bowstring stays beside a dragon for the orb.
	 */
	public boolean isOutranked(GauntletMap map, EnemyType enemy, GauntletRoom self)
	{
		WeaponType type = enemy.getDrop();
		if (map == null || type == null)
			return false;
		int rank = rank(enemy);
		Set<WeaponType> useful = findCommon(computeDropsFor(type), computeMissingUpgrades());
		for (GauntletRoom room : map.getRooms())
		{
			EnemyType other = room == self ? null : chooseEnemyType(room);
			if (other != null && rank(other) < rank && computeDropsFor(other.getDrop()).containsAll(useful))
				return true;
		}
		return false;
	}
}
