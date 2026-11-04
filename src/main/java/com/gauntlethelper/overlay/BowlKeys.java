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

package com.gauntlethelper.overlay;

import com.gauntlethelper.game.ArmorSlot;
import com.gauntlethelper.game.Tier;
import com.gauntlethelper.plan.ArmorUpgrades;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.WeaponType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.runelite.api.gameval.ItemID;

/**
 * The singing bowl menu's number key for each recipe the plan crafts, for the key line under the bowl caption. The
 * menu's buttons are fixed (a recipe that can't be made yet keeps its button, dimmed), and each armor piece and weapon
 * has one button whose tier changes. The fixed keys cover every button
 * the plan presses and win over keys learned from the open menu's button positions (CraftOverlay); a learned key only
 * fills in an item without a fixed one, until the plugin shuts down. The plugin owns the one instance.
 */
public final class BowlKeys
{
	/** Fixed keys by button, in menu order: vial, helm, body, legs, halberd, staff, bow, Corrupted paddlefish, teleport crystal. */
	private static final Map<Integer, String> FIXED = new HashMap<>();

	static
	{
		FIXED.put(ItemID.GAUNTLET_VIAL_EMPTY_HM, "2");
		FIXED.put(getButton(ItemID.GAUNTLET_HELMET_T1_HM), "3");
		FIXED.put(getButton(ItemID.GAUNTLET_CHESTPLATE_T1_HM), "4");
		FIXED.put(getButton(ItemID.GAUNTLET_PLATELEGS_T1_HM), "5");
		FIXED.put(getButton(ItemID.GAUNTLET_MELEE_T1_HM), "6");
		FIXED.put(getButton(ItemID.GAUNTLET_MAGIC_T1_HM), "7");
		FIXED.put(getButton(ItemID.GAUNTLET_RANGED_T1_HM), "8");
		FIXED.put(ItemID.GAUNTLET_COMBO_FOOD_HM, "9");
		FIXED.put(ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM, "0");
	}

	/** Keys learned from the menu, by button: an armor slot or weapon type of any tier (getFamily), otherwise the item. */
	private final Map<Integer, String> learned = new ConcurrentHashMap<>();

	/** The key for this recipe, or null for an item without a fixed key whose button hasn't been seen in the menu. */
	public String getKey(int itemId)
	{
		int button = getButton(itemId);
		String key = FIXED.get(button);
		return key != null ? key : learned.get(button);
	}

	/** Records the open menu's buttons in reading order: each button's item takes its position's number key, 1 to 9 then 0. */
	public void learn(List<Integer> items)
	{
		for (int i = 0; i < items.size(); i++)
		{
			String key = getPositionKey(i);
			if (key != null)
				learned.put(getButton(items.get(i)), key);
		}
	}

	/** The number key of the button at this reading-order position, or null past the tenth. */
	static String getPositionKey(int index)
	{
		return index < 10 ? Integer.toString((index + 1) % 10) : null;
	}

	public void forget()
	{
		learned.clear();
	}

	/**
	 * The crafts as keys, one per click in the given order: "Keys: 2, 2, 3, 5". Null without crafts, or while any craft's
	 * button hasn't been seen in the menu.
	 */
	public String format(List<Requirements.Craft> crafts)
	{
		if (crafts == null)
			return null;
		List<String> tokens = new ArrayList<>();
		for (Requirements.Craft craft : crafts)
		{
			String key = getKey(craft.itemId);
			// A craft whose button hasn't been seen would leave a gap in the click order: no line rather than a wrong one.
			if (key == null)
				return null;
			tokens.add(key);
		}
		return tokens.isEmpty() ? null : "Keys: " + String.join(", ", tokens);
	}

	/** A craft's place in the keyboard's number order, 1 to 9 then 0; unknown keys last. */
	int getKeyRank(Requirements.Craft craft)
	{
		String key = getKey(craft.itemId);
		if (key == null)
			return Integer.MAX_VALUE;
		return "0".equals(key) ? 10 : Integer.parseInt(key);
	}

	/** Armor slot or weapon type of any tier, so every tier on one button counts together; -1 for anything else. */
	static int getFamily(int itemId)
	{
		ArmorSlot slot = ArmorUpgrades.getSlot(itemId);
		if (slot != null)
			return 100 + slot.ordinal();
		WeaponType type = findWeaponType(itemId);
		return type != null ? 200 + type.ordinal() : -1;
	}

	private static int getButton(int itemId)
	{
		int family = getFamily(itemId);
		return family >= 0 ? family : itemId;
	}

	private static WeaponType findWeaponType(int itemId)
	{
		for (WeaponType type : WeaponType.values())
			for (Tier tier : Tier.values())
				if (type.getItem(tier) == itemId)
					return type;
		return null;
	}
}
