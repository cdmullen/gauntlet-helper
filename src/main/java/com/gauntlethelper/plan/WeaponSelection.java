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

import com.gauntlethelper.config.WeaponPair;
import java.util.EnumSet;
import java.util.Set;

/** Attempt-local stable adaptive selection; only actual usable upgrades commit a type. */
public final class WeaponSelection
{
	private WeaponPair policy;
	private final EnumSet<WeaponType> committed = EnumSet.noneOf(WeaponType.class);

	public void reset()
	{
		policy = null;
		committed.clear();
	}

	/** The weapons committed so far, as a copy: later observations don't change it. */
	public Set<WeaponType> observe(WeaponPair pair, Inventory inv)
	{
		if (pair != policy)
		{
			policy = pair;
			committed.clear();
			committed.addAll(pair.getWeapons());
		}
		if (pair.isFixed())
			return committed.clone();
		while (committed.size() < 2)
		{
			WeaponType best = null;
			for (WeaponType type : WeaponType.values())
				if (!committed.contains(type) && computeScore(type, inv) > 0 && (best == null || computeScore(type, inv) > computeScore(best, inv)))
					best = type;
			if (best == null)
				break;
			committed.add(best);
		}
		return committed.clone();
	}

	private static int computeScore(WeaponType type, Inventory inv)
	{
		if (inv.getOwned(type.getPerfected()) > 0)
			return 100;
		return inv.getHeld(type.getComponent()) > 0 ? 10 : 0;
	}
}
