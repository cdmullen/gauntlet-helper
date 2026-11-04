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
 * Holds a started home fish drop until the requested count is on the floor. Each dropped fish frees a
 * slot, which could make a recipe fit and swap the storage task for a craft task mid-drop.
 */
public final class FishDropHold
{
	private boolean active;
	private int keepCooked, lastCooked, lastRaw;

	/** The first Drop click on a cued cooked fish fixes how many cooked fish stay in the inventory. */
	public void start(int heldCooked, int heldRaw, int shownDrop)
	{
		if (active || shownDrop <= 0)
			return;
		active = true;
		keepCooked = Math.max(0, heldCooked - shownDrop);
		lastCooked = heldCooked;
		lastRaw = heldRaw;
	}

	/**
	 * Cooked fish still to drop; 0 once done or abandoned (left home, or picked up fish). Fish that finish cooking
	 * during the drop (the drop cue shows while the rest cook) are kept, not added to the drop.
	 */
	public int updateRemaining(boolean atHome, int heldCooked, int heldRaw)
	{
		if (!active)
			return 0;
		int cookedNow = Math.max(0, lastRaw - heldRaw);
		keepCooked += cookedNow;
		lastCooked += cookedNow;
		lastRaw = heldRaw;
		if (!atHome || heldCooked > lastCooked || heldCooked <= keepCooked)
		{
			reset();
			return 0;
		}
		lastCooked = heldCooked;
		return heldCooked - keepCooked;
	}

	public void reset() { active = false; }
}
