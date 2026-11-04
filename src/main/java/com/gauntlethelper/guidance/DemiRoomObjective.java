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

import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.DemiBossTracker;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.WeaponType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** A room-level stay/loot cue, based only on an observed useful demi and actual drops. */
final class DemiRoomObjective
{
	// The death event may precede the ground-item event; this is a short observation grace.
	static final int DROP_GRACE_TICKS = 3;
	private GauntletRoom room;
	private int deathTick = -1;
	private Set<WeaponType> heldUpgrades = Collections.emptySet();
	private Set<WeaponType> wantedDrops = Collections.emptySet();
	private boolean sawNeededLoot;

	GauntletRoom getRoom() { return room; }
	void reset()
	{
		room = null;
		deathTick = -1;
		heldUpgrades = Collections.emptySet();
		wantedDrops = Collections.emptySet();
		sawNeededLoot = false;
	}

	void update(Requirements plan, GauntletMap map, GauntletRoom current, GauntletRoom destination, DemiBossTracker history, int tick)
	{
		if (plan == null || plan.isReady())
		{
			reset();
			return;
		}
		DemiBossPlan needed = new DemiBossPlan(plan, history);
		if (room != null && (isEligible(map, current, needed) && current != room || room != current && room != destination))
			reset();
		if (room != null)
		{
			if (room.hasLivingDemiBoss())
			{
				if (needed.isUseful(map, room))
				{
					wantedDrops = needed.computeWantedDrops(room);
					return;
				}
				reset();
			}
			else if (room.getDemiStatus() == GauntletRoom.DemiStatus.DEFEATED)
			{
				if (deathTick < 0)
					deathTick = tick;
				boolean hasLoot = hasNeededLoot(plan, room);
				if (hasLoot)
				{
					sawNeededLoot = true;
					return;
				}
				if (!sawNeededLoot && computeHeldUpgrades(plan).equals(heldUpgrades) && tick - deathTick < DROP_GRACE_TICKS)
					return;
				reset();
			}
			else
				reset(); // A fully observed absent room is not a confirmed kill.
		}
		GauntletRoom next = isEligible(map, current, needed) ? current : isEligible(map, destination, needed) ? destination : null;
		if (next != null)
		{
			room = next;
			heldUpgrades = computeHeldUpgrades(plan);
			wantedDrops = needed.computeWantedDrops(next);
		}
	}

	boolean isAwaitingLoot(GauntletRoom current)
	{
		return room != null && room == current && room.getDemiStatus() == GauntletRoom.DemiStatus.DEFEATED;
	}

	/** The wanted upgrades the demi just killed can drop, during the grace before its ground items are seen; else empty. */
	Set<WeaponType> getAwaitedUpgrades()
	{
		boolean awaiting = room != null && room.getDemiStatus() == GauntletRoom.DemiStatus.DEFEATED && !sawNeededLoot;
		return awaiting ? wantedDrops : Collections.emptySet();
	}

	private static boolean isEligible(GauntletMap map, GauntletRoom room, DemiBossPlan needed)
	{
		return room != null && room.isDiscovered() && needed.isUseful(map, room);
	}

	private static boolean hasNeededLoot(Requirements plan, GauntletRoom room)
	{
		GroundLoot policy = new GroundLoot();
		for (GroundLoot.Entry entry : room.getLoot())
			if (!GroundLoot.isFood(entry.id) && policy.isNeeded(entry, plan, plan.getInventory(), false))
				return true;
		return false;
	}

	/** Weapon types whose upgrade the plan holds (staff orb, bow bowstring, halberd spike). */
	static Set<WeaponType> computeHeldUpgrades(Requirements plan)
	{
		Set<WeaponType> result = EnumSet.allOf(WeaponType.class);
		result.removeIf(type -> !plan.hasUpgrade(type));
		return result;
	}
}
