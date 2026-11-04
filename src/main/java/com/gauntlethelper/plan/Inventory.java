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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;

/** Immutable observed inventory/equipment. */
public final class Inventory
{
	/** Slots in the player's inventory. */
	public static final int SIZE = 28;

	private final Map<Integer, Integer> inventory;
	private final Map<Integer, Integer> equipment;
	private final int usedSlots;

	public Inventory(Map<Integer, Integer> inventory, Map<Integer, Integer> equipment, int usedSlots)
	{
		this.inventory = Collections.unmodifiableMap(new HashMap<>(inventory));
		this.equipment = Collections.unmodifiableMap(new HashMap<>(equipment));
		this.usedSlots = usedSlots;
	}

	public Map<Integer, Integer> getHeldItems() { return inventory; }
	public Map<Integer, Integer> getEquippedItems() { return equipment; }
	public int getHeld(int id) { return inventory.getOrDefault(id, 0); }
	public int getEquipped(int id) { return equipment.getOrDefault(id, 0); }
	public int getOwned(int id) { return getHeld(id) + getEquipped(id); }
	/**
	 * Cooked fish held, a Corrupted paddlefish included: the bowl makes one from a cooked paddlefish, and it is food for
	 * Hunllef like the fish it came from.
	 */
	public int getHeldCookedFood() { return getHeld(ItemID.GAUNTLET_FOOD) + getHeld(ItemID.GAUNTLET_COMBO_FOOD_HM); }
	public int getFreeSlots() { return Math.max(0, SIZE - usedSlots); }

	/** These holdings with the given quantities gone: a stack frees its slot only when all of it goes. */
	public Inventory without(Map<Integer, Integer> items)
	{
		Map<Integer, Integer> held = new HashMap<>(inventory);
		int used = usedSlots;
		for (Map.Entry<Integer, Integer> item : items.entrySet())
		{
			int id = item.getKey();
			int gone = Math.min(item.getValue(), getHeld(id));
			int left = getHeld(id) - gone;
			held.put(id, left);
			if (Simulation.isStacked(id))
				used -= gone > 0 && left == 0 ? 1 : 0;
			else
				used -= gone;
		}
		return new Inventory(held, equipment, used);
	}
}
