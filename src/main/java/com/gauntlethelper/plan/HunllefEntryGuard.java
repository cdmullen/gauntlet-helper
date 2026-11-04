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

import com.gauntlethelper.game.GauntletIds;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.ObjectID;

/**
 * Moves Pass and Quick-pass on Hunllef's barrier below Walk here while raw paddlefish are held: raw fish can't be eaten
 * in the fight, so a left click walks instead of entering. Both stay in the right-click menu.
 */
public final class HunllefEntryGuard
{
	private HunllefEntryGuard() { }

	/** Hunllef's barrier in either Gauntlet (GauntletIds.object). */
	public static boolean isBarrier(int objectId)
	{
		objectId = GauntletIds.object(objectId);
		return objectId == ObjectID.GAUNTLET_BLOCKADE_ENTER_HM || objectId == ObjectID.GAUNTLET_BLOCKADE_HM;
	}

	/** One of an object's own menu options (Pass, Quick-pass...), not an item used on it or a walk click. */
	public static boolean isObjectOption(MenuAction action)
	{
		return action == MenuAction.GAME_OBJECT_FIRST_OPTION || action == MenuAction.GAME_OBJECT_SECOND_OPTION
				|| action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
				|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
	}

	/** A barrier's own Pass or Quick-pass entry. */
	public static boolean isEntry(MenuEntry entry)
	{
		String option = entry.getOption();
		return isObjectOption(entry.getType()) && isBarrier(entry.getIdentifier())
				&& ("Pass".equalsIgnoreCase(option) || "Quick-pass".equalsIgnoreCase(option));
	}

	/**
	 * The menu with the barrier's Pass and Quick-pass at the bottom (the start of the array, above only Cancel), so Walk
	 * here and every other entry sit above them; the same array when there is nothing to move.
	 */
	public static MenuEntry[] deprioritizeEntries(MenuEntry[] entries, boolean enabled, int rawFish)
	{
		if (!enabled || rawFish <= 0)
			return entries;
		List<MenuEntry> bottom = new ArrayList<>();
		List<MenuEntry> guarded = new ArrayList<>();
		List<MenuEntry> rest = new ArrayList<>();
		for (MenuEntry entry : entries)
		{
			if (isEntry(entry))
				guarded.add(entry);
			else if (entry.getType() == MenuAction.CANCEL && guarded.isEmpty() && rest.isEmpty())
				bottom.add(entry);
			else
				rest.add(entry);
		}
		if (guarded.isEmpty())
			return entries;
		bottom.addAll(guarded);
		bottom.addAll(rest);
		MenuEntry[] ordered = bottom.toArray(new MenuEntry[0]);
		for (int i = 0; i < ordered.length; i++)
		{
			if (ordered[i] != entries[i])
				return ordered;
		}
		return entries;
	}
}
