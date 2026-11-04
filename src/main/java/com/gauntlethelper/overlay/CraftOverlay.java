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
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Requirements;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.util.ColorUtil;

/**
 * Read-only teal/yellow/gray states over the singing bowl recipe buttons, plain for a wanted recipe left for later
 * though makeable now.
 */
public class CraftOverlay extends Overlay
{
	/** Wanted, but the materials, shards or room aren't there yet (a wanted recipe makeable now is never yellow). */
	static final Color MISSING = new Color(255, 210, 0);
	static final Color MISSING_FILL = new Color(255, 210, 0, 35);
	/** Never crafted this attempt, or already made: dimmed like a disabled button. */
	static final Color DONE_FILL = new Color(40, 40, 40, 190);

	private final Client client;
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;

	@Inject
	CraftOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.arePrepCuesAllowed())
			return null;
		Widget root = client.getWidget(InterfaceID.Skillmulti.UNIVERSE);
		if (root == null || root.isHidden())
			return null;
		learnKeys();
		Requirements plan = plugin.getPlan();
		if (!config.highlightSingingBowlCrafting() || plan == null)
			return null;
		// At home every craft the visit has room for is marked at once (basic staff, attuned staff, armor, the held
		// upgrades' weapon lines, then vials), so a quick run of bowl clicks needs no guess. Away
		// from home the plan's own list marks only its first recipe.
		HomeVisit visit = plugin.getHomeVisit();
		HomeCraftPlan home = visit.getHomeCraftPlan();
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		if (home != null)
			for (Requirements.Craft craft : home.getCrafts())
				counts.merge(craft.itemId, 1, Integer::sum);
		else if (!visit.getCrafts().isEmpty())
			counts.put(visit.getCrafts().get(0).itemId, 1);
		// Right after a craft the menu already shows the new inventory while the plan waits for the next tick: leave
		// out what was just made, so a finished piece isn't offered again.
		Map<Integer, Integer> live = readHeld(client.getItemContainer(InventoryID.INV));
		if (live != null && plan.getInventory() != null && !live.equals(plan.getInventory().getHeldItems()))
			counts.replaceAll((item, count) -> count - Math.max(0, countMade(live, plan.getInventory().getHeldItems(), item)));
		// Teal: craft now. Yellow: wanted, not makeable yet. Gray: never made or finished.
		Color color = config.currentActionColor();
		for (int id = InterfaceID.Skillmulti.A; id <= InterfaceID.Skillmulti.R; id++)
		{
			Widget slot = client.getWidget(id);
			if (slot == null || slot.isHidden())
				continue;
			Widget icon = findItem(slot, Collections.newSetFromMap(new IdentityHashMap<>()));
			Rectangle bounds = slot.getBounds();
			if (icon == null || bounds == null || bounds.isEmpty())
				continue;
			// Armor upgrades share the basic piece's recipe slots: the slot stands for that piece's next goal step.
			int itemId = plan.getArmorRecipe(getRecipe(icon.getItemId()));
			int count = counts.getOrDefault(itemId, 0);
			if (count <= 0)
			{
				// A craft just made (marked, now used up) grays out at once rather than turning yellow for a tick.
				boolean wanted = !counts.containsKey(itemId) && plan.needsCraft(itemId);
				// Wanted and makeable now but left for later by the shard budget: plain, not yellow.
				if (wanted && HomeCraftPlan.canFitRecipe(plan.getInventory(), itemId))
					continue;
				graphics.setColor(wanted ? MISSING_FILL : DONE_FILL);
				graphics.fill(bounds);
				if (wanted)
				{
					graphics.setColor(MISSING);
					graphics.setStroke(OverlayStrokes.MEDIUM);
					graphics.draw(bounds);
				}
				continue;
			}
			graphics.setColor(ColorUtil.colorWithAlpha(color, 45));
			graphics.fill(bounds);
			graphics.setColor(color);
			graphics.setStroke(OverlayStrokes.MEDIUM);
			graphics.draw(bounds);
			// How many times to click the recipe in this visit, under its icon.
			Rectangle iconBounds = icon.getBounds();
			if (home != null && iconBounds != null && !iconBounds.isEmpty())
			{
				String text = Integer.toString(countClicks(counts, itemId));
				FontMetrics metrics = graphics.getFontMetrics();
				OverlayUtil.renderTextLocation(graphics, new Point(iconBounds.x
						+ (iconBounds.width - metrics.stringWidth(text)) / 2,
						iconBounds.y + iconBounds.height + metrics.getAscent()), text, color);
			}
		}
		return null;
	}

	/**
	 * The recipe a bowl button's icon stands for, as the plan names it (GauntletIds). The regular Gauntlet's bowl shows
	 * the regular empty vial, which GauntletIds leaves alone; the plan crafts it as the Corrupted one.
	 */
	static int getRecipe(int iconItemId)
	{
		int itemId = GauntletIds.item(iconItemId);
		return itemId == ItemID.GAUNTLET_VIAL_EMPTY ? ItemID.GAUNTLET_VIAL_EMPTY_HM : itemId;
	}

	/** How many more of a recipe the live inventory holds than the plan's; an empty vial of either Gauntlet counts. */
	static int countMade(Map<Integer, Integer> live, Map<Integer, Integer> planned, int itemId)
	{
		int made = live.getOrDefault(itemId, 0) - planned.getOrDefault(itemId, 0);
		if (itemId == ItemID.GAUNTLET_VIAL_EMPTY_HM)
			made += live.getOrDefault(ItemID.GAUNTLET_VIAL_EMPTY, 0) - planned.getOrDefault(ItemID.GAUNTLET_VIAL_EMPTY, 0);
		return made;
	}

	/** Learns each recipe button's key from its position in the open menu, for the bowl key line (BowlKeys). */
	private void learnKeys()
	{
		List<Widget> slots = new ArrayList<>();
		Map<Widget, Widget> icons = new IdentityHashMap<>();
		for (int id = InterfaceID.Skillmulti.A; id <= InterfaceID.Skillmulti.R; id++)
		{
			Widget slot = client.getWidget(id);
			if (slot == null || slot.isHidden() || slot.getBounds() == null || slot.getBounds().isEmpty())
				continue;
			Widget icon = findItem(slot, Collections.newSetFromMap(new IdentityHashMap<>()));
			if (icon == null)
				continue;
			slots.add(slot);
			icons.put(slot, icon);
		}
		// Number keys follow the buttons' reading order on screen.
		slots.sort(Comparator.comparingInt((Widget slot) -> slot.getBounds().y).thenComparingInt(slot -> slot.getBounds().x));
		List<Integer> items = new ArrayList<>();
		for (Widget slot : slots)
			items.add(getRecipe(icons.get(slot).getItemId()));
		plugin.getBowlKeys().learn(items);
	}

	/**
	 * Clicks on a recipe button this visit: its marked crafts, and for an armor or weapon button every tier of that
	 * piece or weapon the visit makes, since the bowl upgrades it one tier per click on the same button.
	 */
	static int countClicks(Map<Integer, Integer> counts, int itemId)
	{
		int family = BowlKeys.getFamily(itemId);
		if (family < 0)
			return counts.getOrDefault(itemId, 0);
		int clicks = 0;
		for (Map.Entry<Integer, Integer> craft : counts.entrySet())
			if (BowlKeys.getFamily(craft.getKey()) == family)
				clicks += Math.max(0, craft.getValue());
		return clicks;
	}

	/** Held items by id (as the plan reads them, GauntletIds) from the live inventory, or null when it isn't loaded. */
	static Map<Integer, Integer> readHeld(ItemContainer inventory)
	{
		if (inventory == null)
			return null;
		Map<Integer, Integer> live = new HashMap<>();
		for (Item item : inventory.getItems())
			if (item.getId() >= 0 && item.getQuantity() > 0)
				live.merge(GauntletIds.item(item.getId()), item.getQuantity(), Integer::sum);
		return live;
	}

	/** The recipe's output icon: the first visible descendant showing an item. */
	static Widget findItem(Widget widget, Set<Widget> visited)
	{
		return findItem(widget, -1, visited);
	}

	/** The first visible descendant showing this item, or any item when itemId is -1. */
	static Widget findItem(Widget widget, int itemId, Set<Widget> visited)
	{
		if (widget == null || !visited.add(widget) || widget.isHidden())
			return null;
		if (itemId == -1 ? widget.getItemId() > 0 : widget.getItemId() == itemId)
			return widget;
		Widget[][] groups = {widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()};
		for (Widget[] children : groups)
		{
			if (children == null)
				continue;
			for (Widget child : children)
			{
				Widget match = findItem(child, itemId, visited);
				if (match != null)
					return match;
			}
		}
		return null;
	}
}
