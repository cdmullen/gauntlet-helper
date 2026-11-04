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

/**
 * Which Gauntlet an attempt is in, read from the GAUNTLET_CORRUPTED varbit once the instance has loaded (the varbit is
 * still 0 on the entry tick before it, in both Gauntlets). Anything unknown is CORRUPTED, so the Corrupted Gauntlet's
 * behavior is the default everywhere.
 */
public enum GauntletMode
{
	CORRUPTED(750, "corrupted"),
	REGULAR(1000, "crystalline");

	private final int prepTicks;
	private final String enemyPrefix;

	GauntletMode(int prepTicks, String enemyPrefix)
	{
		this.prepTicks = prepTicks;
		this.enemyPrefix = enemyPrefix;
	}

	/** Prep ticks at entry: 750 (7:30) in the Corrupted Gauntlet, 1000 (10:00) in the regular one. */
	public int getPrepTicks() { return prepTicks; }

	/** The enemies' game-name prefix in guidance text: "corrupted rat", "crystalline rat". */
	public String getEnemyPrefix() { return enemyPrefix; }

	/** The mode for a GAUNTLET_CORRUPTED value: REGULAR only for an explicit 0 read inside the Gauntlet. */
	public static GauntletMode of(boolean inside, int corruptedVarbit)
	{
		return inside && corruptedVarbit == 0 ? REGULAR : CORRUPTED;
	}

	/** A recorded or saved name; CORRUPTED for null or anything unknown. */
	public static GauntletMode parse(String name)
	{
		return "REGULAR".equals(name) ? REGULAR : CORRUPTED;
	}
}
