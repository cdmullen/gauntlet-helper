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

package com.gauntlethelper.config;

import com.gauntlethelper.plan.WeaponType;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Weapon pair policy; "either" choices commit to whichever upgrade appears first, so getWeapons holds only the weapons
 * the choice fixes up front (WeaponSelection adds the rest during the attempt).
 */
public enum WeaponPair
{
	STAFF_EITHER("Staff + either", EnumSet.of(WeaponType.STAFF)),
	STAFF_BOW("Staff + bow", EnumSet.of(WeaponType.STAFF, WeaponType.BOW)),
	STAFF_HALBERD("Staff + halberd", EnumSet.of(WeaponType.STAFF, WeaponType.HALBERD)),
	BOW_HALBERD("Bow + halberd", EnumSet.of(WeaponType.BOW, WeaponType.HALBERD)),
	ANY_TWO("Any two", EnumSet.noneOf(WeaponType.class));
	private final String label;
	private final Set<WeaponType> weapons;

	WeaponPair(String label, Set<WeaponType> weapons)
	{
		this.label = label;
		this.weapons = Collections.unmodifiableSet(weapons);
	}

	public Set<WeaponType> getWeapons() { return weapons; }
	public boolean isFixed() { return weapons.size() == 2; }
	@Override
	public String toString() { return label; }
}
