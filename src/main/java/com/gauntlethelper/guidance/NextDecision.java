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

import com.gauntlethelper.game.ObjectKind;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;

/**
 * What a Next step decided, which NextTask.publish writes to GuidanceState's Next fields. A step returns null when it
 * doesn't decide. A null field leaves what is already published. The path isn't here: publish works it out from the
 * object, continuing from the current path's end.
 */
final class NextDecision
{
	/** Decided with nothing to show: the steps stop here and Next stays empty. */
	static final NextDecision NOTHING = new NextDecision(null, null, null, null);

	final TileObject target;
	final ObjectKind kind;
	/** Next text in place of the kind's label. */
	final String label;
	/** Next is a visible demi, an NPC with no path, instead of an object. */
	final NPC enemy;

	private NextDecision(TileObject target, ObjectKind kind, String label, NPC enemy)
	{
		this.target = target;
		this.kind = kind;
		this.label = label;
		this.enemy = enemy;
	}

	/** Next is an object to use and its kind. */
	static NextDecision object(TileObject target, ObjectKind kind)
	{
		return new NextDecision(target, kind, null, null);
	}

	/** Next is this text alone; null text shows nothing. */
	static NextDecision text(String label)
	{
		return new NextDecision(null, null, label, null);
	}

	/** Next is a visible demi, under this text. */
	static NextDecision fight(NPC enemy, String label)
	{
		return new NextDecision(null, null, label, enemy);
	}

	NextDecision withLabel(String text)
	{
		return new NextDecision(target, kind, text, enemy);
	}
}
