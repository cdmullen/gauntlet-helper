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
 * The three crafted tiers weapons and armor share, lowest first: a type's items are indexed by ordinal(). Wherever an
 * owned tier is kept, null means no tier is owned.
 */
public enum Tier
{
	BASIC("Basic"),
	ATTUNED("Attuned"),
	PERFECTED("Perfected");

	private final String label;

	Tier(String label) { this.label = label; }

	/** The tier's name in craft labels, e.g. "Attuned". */
	public String getLabel() { return label; }

	/** The tier above this one, or null above PERFECTED. */
	public Tier getNext() { return this == PERFECTED ? null : values()[ordinal() + 1]; }

	/** The tier below this one, or null below BASIC. */
	public Tier getBelow() { return this == BASIC ? null : values()[ordinal() - 1]; }

	/** Whether this tier is above an owned tier, where null means no tier is owned. */
	public boolean isAbove(Tier owned) { return owned == null || compareTo(owned) > 0; }
}
