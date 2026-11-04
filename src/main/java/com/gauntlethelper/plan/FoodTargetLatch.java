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

/**
 * Attempt-local fish quota latch. Until the fish row (held raw + cooked plus observed stored food) first reaches the
 * food target, counts follow the fish owned as usual. From then on the requirement stays met for the attempt: a fish
 * eaten later lowers the food target to the fish still owned instead of asking for more fishing, because eating during
 * prep is the player's own choice.
 */
public final class FoodTargetLatch
{
	private boolean met;

	/** The plan to guide by this tick: the same plan, or once the quota was met, one aimed at the fish still owned. */
	public Requirements apply(Requirements plan)
	{
		Requirements.Row fish = plan.getRow(Requirement.FISH);
		if (fish.isComplete())
			met = true;
		if (!met || fish.isComplete())
			return plan;
		return plan.withFoodTarget(fish.have).withStoredFood(plan.getStoredFood());
	}

	public boolean isMet() { return met; }

	public void reset()
	{
		met = false;
	}
}
