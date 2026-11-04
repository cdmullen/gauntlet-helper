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
 * The inventory cue texts. InventoryAdvice and the plugin write them; InventoryOverlay reads them back for each
 * cue's color and caption. Recordings hold the same text (the recorder's "cues"), so keep it unchanged.
 */
public final class InventoryCue
{
	public static final String EQUIP = "Equip";
	public static final String FILL = "Fill";
	public static final String MIX = "Mix";
	private static final String DROP = "Drop ";
	private static final String GRIND = "Grind ";

	private InventoryCue() { }

	/** Drop this many of the item. */
	public static String drop(int count) { return DROP + count; }
	/** Grind this many shards into dust. */
	public static String grind(int shards) { return GRIND + shards; }
	public static boolean isDrop(String cue) { return cue != null && cue.startsWith(DROP); }
	public static boolean isGrind(String cue) { return cue != null && cue.startsWith(GRIND); }

	/** The shards a grind cue names, 0 for any other cue. */
	public static int getGrindShards(String cue)
	{
		if (!isGrind(cue))
			return 0;
		try
		{
			return Integer.parseInt(cue.substring(GRIND.length()).trim());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
