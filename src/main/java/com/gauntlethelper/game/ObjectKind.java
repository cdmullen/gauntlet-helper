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

import com.gauntlethelper.plan.Requirement;


/** What a guided scene object is (Guidance.objects): a resource node, a home station, a doorway or a fixture. */
public enum ObjectKind
{
	ORE("Mine ore", Requirement.ORE),
	BARK("Chop bark", Requirement.BARK),
	LINUM_TIRINUM("Pick linum tirinum", Requirement.LINUM_TIRINUM),
	GRYM_LEAVES("Pick grym leaf", Requirement.GRYM_LEAVES),
	FISH("Catch paddlefish", Requirement.FISH),
	BOWL("Craft at singing bowl", null),
	RANGE("Cook paddlefish", null),
	WATER("Fill vials", null),
	DOOR("Light room", null),
	PASSAGE("Walk through doorway", null),
	TOOLS("Take sceptre from tool storage", null),
	BARRIER("Pass the barrier to Hunllef", null);

	public final String label;
	public final Requirement resource;

	ObjectKind(String label, Requirement resource)
	{
		this.label = label;
		this.resource = resource;
	}

	/** Nominal items one fresh node is forecast to give. A planning estimate only, never inventory credit. */
	public int getNominalYield() { return this == GRYM_LEAVES ? 1 : this == FISH ? 4 : 3; }

	/** A home station: the singing bowl, the range or the water pump. */
	public boolean isHomeStation() { return this == BOWL || this == RANGE || this == WATER; }

	/** A way between rooms: a door to light or an open doorway. */
	public boolean isDoorway() { return this == DOOR || this == PASSAGE; }
}
