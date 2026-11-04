/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, cdmullen
 * Copyright (c) 2023, InfernoStats
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

package com.gauntlethelper.boss;

import net.runelite.api.gameval.SpotanimID;

/**
 * Graphics for Hunllef's prayer-disable orb. The raid choices are the projectile styles of the Hunllef Projectiles
 * plugin (Plugin Hub: hunllef-projectiles), which takes them from the Vardorvis Projectiles plugin; THIRD_PARTY_NOTICES.md
 * has both. Original keeps the game's own orb.
 */
public enum HunllefProjectileStyle
{
	ORIGINAL("Original", -1),
	CRYSTALLINE("Crystalline", SpotanimID.CRYSTAL_HUNLLEF_PRAYER_TRAVEL),
	CORRUPTED("Corrupted", SpotanimID.CRYSTAL_HUNLLEF_PRAYER_TRAVEL_HM),
	INFERNO("Inferno", SpotanimID.INFERNO_SPLITTER_MAGE),
	COX("CoX", SpotanimID.OLM_WEAK_MAGE_PROJ),
	TOB("ToB", SpotanimID.TOB_SOTETSEG_MAGING),
	TOA("ToA", SpotanimID.FX_WARDENS_BOMB01);

	private final String name;
	private final int spotanimId;

	HunllefProjectileStyle(String name, int spotanimId)
	{
		this.name = name;
		this.spotanimId = spotanimId;
	}

	/** The replacement graphic, or -1 to leave the orb alone. */
	public int getSpotanimId() { return spotanimId; }

	@Override
	public String toString() { return name; }
}
