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

package com.gauntlethelper.game;

import com.gauntlethelper.plan.WeaponType;
import java.util.Locale;
import net.runelite.api.gameval.NpcID;

/** Every Gauntlet NPC the plugin recognizes (by its Corrupted or regular id, GauntletIds.npc) and what each kind is for in prep. */
public enum EnemyType
{
	RAT(Category.WEAK, null),
	SPIDER(Category.WEAK, null),
	BAT(Category.WEAK, null),
	UNICORN(Category.LARGE, null),
	SCORPION(Category.LARGE, null),
	WOLF(Category.LARGE, null),
	DRAGON(Category.DEMI, WeaponType.STAFF),
	DARK_BEAST(Category.DEMI, WeaponType.BOW),
	BEAR(Category.DEMI, WeaponType.HALBERD),
	HUNLLEF(Category.HUNLLEF, null);

	public enum Category
	{
		/** Rat, spider, bat: the weak monsters that drop the first frame, the ordinary prep kills. */
		WEAK,
		/** Unicorn, scorpion, wolf: large shard drops, otherwise avoided. */
		LARGE,
		/** Dragon, dark beast, bear: the demi-bosses that drop the weapon upgrade components. */
		DEMI,
		HUNLLEF
	}

	private final Category category;
	private final WeaponType drop;
	private final String label;

	EnemyType(Category category, WeaponType drop)
	{
		this.category = category;
		this.drop = drop;
		this.label = name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	public Category getCategory() { return category; }

	/** A rat, spider or bat: the weak monsters that drop the first frame. */
	public boolean isWeak() { return category == Category.WEAK; }

	/** A unicorn, scorpion or wolf. */
	public boolean isLarge() { return category == Category.LARGE; }

	public boolean isDemiBoss() { return category == Category.DEMI; }

	/** The name in instructions, e.g. "dark beast". */
	public String getLabel() { return label; }

	/** The weapon type whose component this demi drops on a first kill; null for anything but a demi. */
	public WeaponType getDrop() { return drop; }

	/** The demi that drops this weapon type's component. */
	public static EnemyType ofDrop(WeaponType type)
	{
		for (EnemyType enemy : values())
			if (enemy.drop == type)
				return enemy;
		return null;
	}

	/** The type of a Corrupted or regular Gauntlet NPC id (GauntletIds.npc), or null. */
	public static EnemyType fromNpcId(int id)
	{
		switch (GauntletIds.npc(id))
		{
			case NpcID.CRYSTAL_RAT_HM:
				return RAT;
			case NpcID.CRYSTAL_SPIDER_HM:
				return SPIDER;
			case NpcID.CRYSTAL_BAT_HM:
				return BAT;
			case NpcID.CRYSTAL_UNICORN_HM:
				return UNICORN;
			case NpcID.CRYSTAL_SCORPION_HM:
				return SCORPION;
			case NpcID.CRYSTAL_WOLF_HM:
				return WOLF;
			case NpcID.CRYSTAL_DRAGON_HM:
				return DRAGON;
			case NpcID.CRYSTAL_DARK_BEAST_HM:
				return DARK_BEAST;
			case NpcID.CRYSTAL_BEAR_HM:
				return BEAR;
			case NpcID.CRYSTAL_HUNLLEF_MELEE_HM:
			case NpcID.CRYSTAL_HUNLLEF_RANGED_HM:
			case NpcID.CRYSTAL_HUNLLEF_MAGIC_HM:
			case NpcID.CRYSTAL_HUNLLEF_DEATH_HM:
				return HUNLLEF;
			default:
				return null; // Tornadoes and unrelated NPCs are not prep enemies.
		}
	}
}
