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
import static net.runelite.api.gameval.ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_1;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_2;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_3;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_4;
import static net.runelite.api.gameval.ItemID.GAUNTLET_POTION_UNFINISHED;

/**
 * Attempt-local craft credit in potions, one per Grym leaf. Drinking, decanting and retrieving dropped potions do not
 * undo/add crafts.
 */
public final class PotionProgress
{
	private Inventory previous;
	private int crafted;

	/** Full potions the held bottles add up to: decanted bottles count by their contents, three doses a potion. */
	public static int countHeldPotions(Inventory inv)
	{
		return countPotionContents(inv) / 3;
	}

	/** Doses held, used only to tell a finished craft from decanting, which leaves this total unchanged. */
	private static int countPotionContents(Inventory inv)
	{
		return inv.getHeld(GAUNTLET_POTION_1) + 2 * inv.getHeld(GAUNTLET_POTION_2) + 3 * inv.getHeld(GAUNTLET_POTION_3) + 4 * inv.getHeld(GAUNTLET_POTION_4);
	}

	public int observe(Inventory inv)
	{
		if (previous == null)
			crafted = countHeldPotions(inv); // Enabling mid-attempt can only recover currently observed potions.
		else
		{
			int unfinishedUsed = Math.max(0, previous.getHeld(GAUNTLET_POTION_UNFINISHED) - inv.getHeld(GAUNTLET_POTION_UNFINISHED));
			int dustUsed = Math.max(0, previous.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM) - inv.getHeld(GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM));
			int finished = Math.min(unfinishedUsed, dustUsed / Recipes.DUST_PER_POTION);
			if (finished > 0 && countPotionContents(inv) > countPotionContents(previous))
				crafted += finished;
		}
		previous = inv;
		return crafted;
	}

	public void reset()
	{
		previous = null;
		crafted = 0;
	}
}
