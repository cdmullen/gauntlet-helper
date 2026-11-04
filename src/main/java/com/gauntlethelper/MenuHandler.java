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

package com.gauntlethelper;

import com.gauntlethelper.overlay.GroundLootOverlay;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.HunllefEntryGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;

/**
 * The plugin's menu handling: clicked options, the needed-loot Take order and the Hunllef entry guard. Client thread
 * only.
 */
final class MenuHandler
{
	private final GauntletHelperPlugin plugin;

	MenuHandler(GauntletHelperPlugin plugin)
	{
		this.plugin = plugin;
	}

	void handleOptionClicked(MenuOptionClicked event)
	{
		if (plugin.inPrep && plugin.recorder != null && "Drop".equalsIgnoreCase(event.getMenuOption()))
			plugin.recorder.recordEvent("menu Drop item#" + event.getItemId());
		if (!plugin.inPrep ||!"Drop".equalsIgnoreCase(event.getMenuOption()) || !GroundLoot.isFood(event.getItemId()))
			return;
		Player player = plugin.client.getLocalPlayer();
		if (player != null && plugin.guidance.isHomeTile(player.getWorldLocation()))
		{
			plugin.loot.deferDroppedFood(player.getWorldLocation());
			if (event.getItemId() == ItemID.GAUNTLET_FOOD && plugin.snapshot != null)
				plugin.fishDrops.start(plugin.snapshot.getHeld(ItemID.GAUNTLET_FOOD), plugin.snapshot.getHeld(ItemID.GAUNTLET_RAW_FOOD), plugin.shownFoodDrop);
		}
	}

	/**
	 * GauntletHelperPlugin.onPostMenuSort: puts the Take of ground items with a teal name above the other Takes (setting
	 * "Reorder ground items"), so left click takes a needed item first while every item keeps a
	 * left-click Take above Walk here. Runs after the client sorts the menu, once every entry is in. Only the Take
	 * entries trade slots; every other entry keeps its place.
	 */
	void putNeededTakesFirst()
	{
		Client client = plugin.client;
		if (!plugin.inPrep || !plugin.config.reorderGroundItems() || client.isMenuOpen())
			return;
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		int takes = 0;
		for (MenuEntry entry : entries)
		{
			if (isGroundTake(entry))
				takes++;
		}
		if (takes < 2)
			return;
		Map<WorldPoint, Set<Integer>> labeled = GroundLootOverlay.findLabeledLoot(plugin, plugin.config);
		if (labeled == null || labeled.isEmpty())
			return;
		List<Integer> slots = new ArrayList<>();
		List<MenuEntry> found = new ArrayList<>();
		for (int i = 0; i < entries.length; i++)
		{
			if (isGroundTake(entries[i]))
			{
				slots.add(i);
				found.add(entries[i]);
			}
		}
		List<MenuEntry> ordered = GroundLootOverlay.putNeededTakeOnTop(found, entry -> isTakenFirst(labeled, entry));
		boolean moved = false;
		for (int i = 0; i < slots.size(); i++)
		{
			moved |= ordered.get(i) != found.get(i);
			entries[slots.get(i)] = ordered.get(i);
		}
		if (moved)
			client.getMenu().setMenuEntries(entries);
	}

	/** A ground item's Take still above Walk here (one another plugin deprioritized is left where it is). */
	private static boolean isGroundTake(MenuEntry entry)
	{
		return entry.getType() == MenuAction.GROUND_ITEM_THIRD_OPTION && "Take".equals(entry.getOption()) && !entry.isDeprioritized();
	}

	private boolean isTakenFirst(Map<WorldPoint, Set<Integer>> labeled, MenuEntry entry)
	{
		WorldView view = plugin.client.getWorldView(entry.getWorldViewId());
		if (view == null)
			return false;
		WorldPoint tile = WorldPoint.fromScene(view, entry.getParam0(), entry.getParam1(), view.getPlane());
		return GroundLootOverlay.isTakenFirst(labeled, tile, entry.getIdentifier());
	}

	/**
	 * GauntletHelperPlugin.onPostMenuSort: moves Pass and Quick-pass on Hunllef's barrier below Walk here while raw
	 * paddlefish are held (setting "Block Hunllef entry with raw fish"); HunllefEntryGuard.deprioritizeEntries says how.
	 */
	void deprioritizeHunllefEntry()
	{
		Client client = plugin.client;
		if (!plugin.inPrep || !plugin.config.blockHunllefEntryWithRawFish() || client.isMenuOpen())
			return;
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		int raw = inventory == null ? 0 : inventory.count(ItemID.GAUNTLET_RAW_FOOD);
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		MenuEntry[] ordered = HunllefEntryGuard.deprioritizeEntries(entries, true, raw);
		if (ordered != entries)
			client.getMenu().setMenuEntries(ordered);
	}
}
