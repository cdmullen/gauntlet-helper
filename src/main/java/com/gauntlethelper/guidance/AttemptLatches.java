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

package com.gauntlethelper.guidance;

import com.gauntlethelper.plan.Requirements;

/**
 * The attempt's guidance state that Guidance and its helpers share, apart from the target and path (GuidanceState).
 * Every change goes through a method named for what happens.
 */
final class AttemptLatches
{
	// Per update: the plan being followed.
	private Requirements navigationPlan;

	// Home return: where the player is and why this update's task heads home. The space return latches across
	// updates until home (or ready); the others are decided afresh each update.
	private boolean atHome;
	private boolean inventorySpaceReturn;
	private boolean homeReturn;
	private boolean staffReturn;
	private boolean tripTeleport;
	/** The demi search is heading outward, away from home. */
	private boolean outwardTrip;

	// Opening loop: its shard budget, its home visit and the outward room check that follows it; the fish allowance, which also limits the demi hunt.
	private int fishAllowance = Integer.MAX_VALUE;
	private int openingShardTarget;
	private boolean openingHomeFinished;
	private boolean outerRoomChecked;

	/** The first home visit after the circle. */
	private enum FirstHome
	{
		/** The opening circle is not over yet. */
		CIRCLE,
		/** The first home visit, arrived or still on the way. */
		VISIT,
		/** Left spawn after that visit. */
		LEFT
	}

	private FirstHome firstHome = FirstHome.CIRCLE;
	private boolean firstHomeArrived;

	// Instruction text: the current task's own wording, shown instead of the target's label.
	private String postLoopInstruction;

	/** Attempt reset: everything except the plan, which the next update replaces. */
	void reset()
	{
		atHome = false;
		inventorySpaceReturn = false;
		homeReturn = false;
		staffReturn = false;
		tripTeleport = false;
		outwardTrip = false;
		fishAllowance = Integer.MAX_VALUE;
		openingShardTarget = 0;
		openingHomeFinished = false;
		outerRoomChecked = false;
		firstHome = FirstHome.CIRCLE;
		firstHomeArrived = false;
		postLoopInstruction = null;
	}

	/** Start of each update: the per-update state goes; the latched space return and the opening progress stay. */
	void beginUpdate(Requirements plan)
	{
		navigationPlan = plan;
		homeReturn = false;
		fishAllowance = Integer.MAX_VALUE;
		atHome = false;
		clearTrip();
	}

	/** Guidance paused or replaced: the trip flags and instruction go until the next task sets its own. */
	void clearTrip()
	{
		postLoopInstruction = null;
		staffReturn = false;
		tripTeleport = false;
		outwardTrip = false;
		homeReturn = false;
	}

	Requirements getNavigationPlan() { return navigationPlan; }

	boolean isAtHome() { return atHome; }
	boolean isInventorySpaceReturn() { return inventorySpaceReturn; }
	boolean isHomeReturn() { return homeReturn; }
	boolean isStaffReturn() { return staffReturn; }
	boolean isTripTeleport() { return tripTeleport; }
	boolean isOutwardTrip() { return outwardTrip; }

	boolean isReturningHome() { return inventorySpaceReturn || homeReturn || !atHome && (staffReturn || tripTeleport); }

	/** Where the player stands this update (spawn room, or beside the bowl before the grid is known). */
	void observeHome(boolean home) { atHome = home; }
	void clearAtHome() { atHome = false; }
	void latchInventorySpaceReturn() { inventorySpaceReturn = true; }
	void clearInventorySpaceReturn() { inventorySpaceReturn = false; }
	void startHomeReturn() { homeReturn = true; }
	void startStaffReturn() { staffReturn = true; }
	void startTripTeleport() { tripTeleport = true; }
	void startOutwardTrip() { outwardTrip = true; }

	int getFishAllowance() { return fishAllowance; }
	int getOpeningShardTarget() { return openingShardTarget; }
	boolean isOpeningHomeFinished() { return openingHomeFinished; }
	boolean isOuterRoomChecked() { return outerRoomChecked; }

	void planOpeningShards(int target) { openingShardTarget = target; }
	void allowFish(int allowance) { fishAllowance = allowance; }
	void finishOpeningHome() { openingHomeFinished = true; }
	void markOuterRoomChecked() { outerRoomChecked = true; }

	/**
	 * The fifth room ended with no home work: the opening's home visit counts as done once the staff is settled, and
	 * the fish allowance lifts.
	 */
	void endOpeningWithoutHome(boolean staffSettled)
	{
		openingHomeFinished = staffSettled;
		fishAllowance = Integer.MAX_VALUE;
	}

	/**
	 * Advances the first home visit after the circle, with atHome already observed this update. True while crafting
	 * is limited to the first-home plan: at home before leaving spawn after that visit.
	 */
	boolean advanceFirstHome(boolean openingActive)
	{
		if (firstHome == FirstHome.CIRCLE && !openingActive)
			firstHome = FirstHome.VISIT;
		if (firstHome == FirstHome.LEFT)
			return false;
		if (atHome)
		{
			if (firstHome == FirstHome.VISIT)
				firstHomeArrived = true;
			return true;
		}
		if (firstHome == FirstHome.VISIT && firstHomeArrived)
			firstHome = FirstHome.LEFT;
		return false;
	}

	String getInstruction() { return postLoopInstruction; }

	void setInstruction(String instruction) { postLoopInstruction = instruction; }
	void appendInstruction(String suffix) { postLoopInstruction += suffix; }
}
