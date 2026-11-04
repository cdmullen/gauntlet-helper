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

/** Running-time estimate in game ticks. Equal-time teleports keep the single-use crystal. */
public final class TeleportEstimate
{
	/**
	 * Ticks from using the teleport crystal to standing at home. Measured in recorded runs: the
	 * crystal leaves the inventory exactly 3 ticks before the position changes, in all 32 teleports of 27 runs.
	 */
	public static final int ACTIVATION_TICKS = 3;

	private TeleportEstimate() { }
	public static int getRunningTicks(int tiles) { return (Math.max(0, tiles) + 1) / 2; }
	public static boolean isWorthwhile(int walkingTiles, int activationTicks, int arrivalWalkingTiles)
	{
		return walkingTiles >= 0 && arrivalWalkingTiles >= 0
				&& getRunningTicks(walkingTiles) > Math.max(1, activationTicks) + getRunningTicks(arrivalWalkingTiles);
	}
}
