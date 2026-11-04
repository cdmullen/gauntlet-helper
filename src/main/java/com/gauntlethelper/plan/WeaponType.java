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

import com.gauntlethelper.game.Tier;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MAGIC_T3_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MELEE_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MELEE_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MELEE_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_MELEE_T3_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RANGED_COMPONENT_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RANGED_T1_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RANGED_T2_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_RANGED_T3_HM;

/** The weapon types with their crafted items by tier and the upgrade component each demi-boss drops. */
public enum WeaponType
{
	STAFF("staff", "orb", GAUNTLET_MAGIC_T1_HM, GAUNTLET_MAGIC_T2_HM, GAUNTLET_MAGIC_T3_HM, GAUNTLET_MAGIC_COMPONENT_HM),
	BOW("bow", "bowstring", GAUNTLET_RANGED_T1_HM, GAUNTLET_RANGED_T2_HM, GAUNTLET_RANGED_T3_HM, GAUNTLET_RANGED_COMPONENT_HM),
	HALBERD("halberd", "spike", GAUNTLET_MELEE_T1_HM, GAUNTLET_MELEE_T2_HM, GAUNTLET_MELEE_T3_HM, GAUNTLET_MELEE_COMPONENT_HM);

	/** The second weapon of a staff pair. */
	public static final Set<WeaponType> SECOND_WEAPONS = Collections.unmodifiableSet(EnumSet.of(BOW, HALBERD));

	private final String label;
	private final String componentLabel;
	/** The crafted items by Tier ordinal. */
	private final int[] items;
	private final int component;

	WeaponType(String label, String componentLabel, int basic, int attuned, int perfected, int component)
	{
		this.label = label;
		this.componentLabel = componentLabel;
		this.items = new int[]{basic, attuned, perfected};
		this.component = component;
	}

	/** The name in craft lists, e.g. "staff". */
	public String getLabel() { return label; }

	/** The upgrade component's name in demi objectives, e.g. "orb". */
	public String getComponentLabel() { return componentLabel; }

	public int getItem(Tier tier) { return items[tier.ordinal()]; }

	public int getPerfected() { return items[Tier.PERFECTED.ordinal()]; }

	public int getComponent() { return component; }
}
