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

package com.gauntlethelper.map;

import com.gauntlethelper.game.GauntletIds;
import net.runelite.api.gameval.ObjectID;

/** Corrupted room unlock nodes; boss barriers are deliberately excluded. */
public final class RoomUnlock
{
	private RoomUnlock() { }

	/** A Corrupted or regular unlit door (GauntletIds.object). */
	public static boolean isNode(int id)
	{
		id = GauntletIds.object(id);
		return id == ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_01_HM || id == ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_02_HM;
	}

	public static boolean canLight(int id, String[] actions)
	{
		if (!isNode(id) || actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if ("Light".equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}
}
