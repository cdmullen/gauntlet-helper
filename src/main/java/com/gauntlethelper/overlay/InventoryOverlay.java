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

package com.gauntlethelper.overlay;

import com.gauntlethelper.GauntletHelperPlugin;
import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.game.GauntletIds;
import com.gauntlethelper.plan.InventoryCue;
import com.gauntlethelper.plan.Recipes;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Point;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/** Filled drop/store squares, per-action outlines and an explicit Fill cue on empty vials. */
public class InventoryOverlay extends WidgetItemOverlay
{
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;

	@Inject
	InventoryOverlay(GauntletHelperPlugin plugin, GauntletHelperConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int widgetItemId, WidgetItem item)
	{
		if (!plugin.arePrepCuesAllowed())
			return;
		// The cues are keyed by the ids the plan reads (GauntletIds).
		int itemId = GauntletIds.item(widgetItemId);
		boolean teleportCue = itemId == ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM && config.highlightInventoryActions() && plugin.isTeleportSuggested();
		String cue = plugin.getInventoryCue(itemId, item.getWidget() == null ? -1 : item.getWidget().getIndex());
		if (!teleportCue && cue == null)
			return;
		Rectangle bounds = item.getCanvasBounds();
		if (bounds == null || bounds.isEmpty())
			return;
		Color previousColor = graphics.getColor();
		Stroke previousStroke = graphics.getStroke();
		try
		{
			boolean isDropCue = InventoryCue.isDrop(cue);
			graphics.setColor(getColor(cue));
			if (isDropCue)
				graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			else
			{
				graphics.setStroke(OverlayStrokes.MEDIUM);
				graphics.drawRect(bounds.x, bounds.y, bounds.width, bounds.height);
				if (InventoryCue.FILL.equals(cue) && (itemId == ItemID.GAUNTLET_VIAL_EMPTY_HM || itemId == ItemID.GAUNTLET_VIAL_EMPTY))
					OverlayUtil.renderTextLocation(graphics, new Point(bounds.x + 3, bounds.y + bounds.height - 3), "Fill", config.fillOutlineColor());
				// How many ten-shard grinds, on the pestle only (the shards carry the same cue).
				int grinds = countGrinds(cue);
				if (itemId == ItemID.GAUNTLET_PESTLE && grinds > 0)
					OverlayUtil.renderTextLocation(graphics, new Point(bounds.x + 3, bounds.y + bounds.height - 3), Integer.toString(grinds),
							config.grindOutlineColor());
			}
		}
		finally
		{
			graphics.setColor(previousColor);
			graphics.setStroke(previousStroke);
		}
	}

	/** Pestle uses for a "Grind n" cue, n shards in ten-shard batches; 0 for any other cue. */
	static int countGrinds(String cue)
	{
		return (InventoryCue.getGrindShards(cue) + Recipes.GRIND_BATCH - 1) / Recipes.GRIND_BATCH;
	}

	/** One color per action; every drop is red, fish stored at home too. Null cue: crystal,
	 *  in the current action color. */
	Color getColor(String cue)
	{
		if (cue == null)
			return config.currentActionColor();
		if (InventoryCue.isDrop(cue))
			return config.dropSquareColor();
		if (cue.equals(InventoryCue.EQUIP))
			return config.equipOutlineColor();
		if (cue.equals(InventoryCue.FILL))
			return config.fillOutlineColor();
		if (cue.equals(InventoryCue.MIX))
			return config.mixOutlineColor();
		if (InventoryCue.isGrind(cue))
			return config.grindOutlineColor();
		return config.currentActionColor();
	}
}
