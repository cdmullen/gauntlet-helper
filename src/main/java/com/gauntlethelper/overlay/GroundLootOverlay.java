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
import com.gauntlethelper.plan.GroundLoot;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ItemLayer;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.grounditems.GroundItemsConfig;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Only the real item name, above its live ground pile; no extra pickup instruction captions.
 * With RuneLite Ground Items on, each name is drawn over the line Ground Items stacks for that item.
 */
public class GroundLootOverlay extends Overlay
{
	private static final Logger log = LoggerFactory.getLogger(GroundLootOverlay.class);

	private final Client client;
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	private final IntFunction<String> names;
	private final GroundItemsSettings groundItems;

	@Inject
	GroundLootOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config, ItemManager items, ConfigManager configManager)
	{
		this(client, plugin, config, id -> items.getItemComposition(id).getName(), readGroundItems(configManager));
	}

	private static GroundItemsSettings readGroundItems(ConfigManager configManager)
	{
		try
		{
			return new GroundItemsSettings(() -> !"false".equals(configManager.getConfiguration("runelite", "grounditemsplugin")),
					configManager.getConfig(GroundItemsConfig.class));
		}
		catch (RuntimeException | LinkageError e)
		{
			log.debug("Ground Items settings unavailable; drawing needed names above the stack", e);
			return GroundItemsSettings.disabled();
		}
	}

	GroundLootOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config, IntFunction<String> names, GroundItemsSettings groundItems)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.names = names;
		this.groundItems = groundItems;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		Map<WorldPoint, Set<Integer>> needed = findLabeledLoot(plugin, config);
		if (needed == null)
			return null;
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.getScene() == null)
			return null;
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		boolean mirrorGroundItems = groundItems.isEnabled();
		int gap = mirrorGroundItems ? GroundItemsSettings.LINE_GAP : Math.max(16, graphics.getFontMetrics().getHeight() + 2);
		for (Map.Entry<WorldPoint, Set<Integer>> pile : needed.entrySet())
		{
			LocalPoint local = LocalPoint.fromWorld(view, pile.getKey());
			if (local == null)
				continue;
			int x = local.getSceneX(), y = local.getSceneY();
			if (x < 0 || x >= tiles.length || y < 0 || y >= tiles[x].length)
				continue;
			Tile tile = tiles[x][y];
			if (tile == null || tile.getItemLayer() == null || tile.getGroundItems() == null)
				continue;
			// Remembered loot remains useful to routing, but an absent pile must not get a ghost label.
			Map<Integer, Integer> live = new LinkedHashMap<>();
			for (TileItem item : tile.getGroundItems())
				if (item.getQuantity() > 0)
					live.merge(item.getId(), item.getQuantity(), Integer::sum);
			List<Integer> stack = plugin.getLoot().getStack(pile.getKey(), live.keySet());
			// The pile holds the game's own ids (and names); the needed set holds them as the plan reads them.
			Set<Integer> neededHere = new LinkedHashSet<>();
			for (int id : live.keySet())
				if (pile.getValue().contains(GauntletIds.item(id)))
					neededHere.add(id);
			Map<Integer, Integer> rows = computeStackRows(stack, neededHere,
					id -> mirrorGroundItems && groundItems.isShown(getItemName(id), live.get(id)));
			ItemLayer layer = tile.getItemLayer();
			for (Map.Entry<Integer, Integer> row : rows.entrySet())
			{
				String name = getItemName(row.getKey());
				if (name.isEmpty())
					continue;
				int quantity = live.get(row.getKey());
				if (quantity > 1)
					name += " (" + QuantityFormatter.quantityToStackSize(quantity) + ")";
				Point anchor = layer.getCanvasTextLocation(graphics, name, layer.getHeight() + 20);
				if (anchor == null)
					continue;
				Point baseline = new Point(anchor.getX(), anchor.getY() - row.getValue() * gap);
				Graphics2D label = (Graphics2D) graphics.create();
				try
				{
					OutlinedText.draw(label, baseline, name, config.currentActionColor());
				}
				finally
				{
					label.dispose();
				}
			}
		}
		return null;
	}

	/**
	 * The item ids that get a teal name, per remembered pile tile, or null while no names are drawn at all (outside
	 * prep, during a teleport home, or with Needed ground items off). The Take menu priority reads the same map.
	 */
	public static Map<WorldPoint, Set<Integer>> findLabeledLoot(GauntletHelperPlugin plugin, GauntletHelperConfig config)
	{
		if (!plugin.arePrepCuesAllowed() || plugin.isTeleportSuggested() || !config.highlightNeededGroundItems())
			return null;
		Map<WorldPoint, Set<Integer>> needed = new LinkedHashMap<>();
		for (GroundLoot.Entry entry : plugin.getLoot().getEntries())
			if (plugin.isNeededLoot(entry))
				needed.computeIfAbsent(entry.point, p -> new LinkedHashSet<>()).add(entry.id);
		return needed;
	}

	/**
	 * True when a ground item's Take belongs above the other Takes (setting "Reorder ground items"):
	 * names are being drawn and this item at this tile has a teal one.
	 */
	public static boolean isTakenFirst(Map<WorldPoint, Set<Integer>> labeled, WorldPoint tile, int id)
	{
		return labeled != null && labeled.getOrDefault(tile, Collections.emptySet()).contains(GauntletIds.item(id));
	}

	/**
	 * The Take entries in menu order (bottom first, so the last one is the left click) with the needed ones moved to
	 * the top. Each group keeps its own order, so the client's choice among needed items, and among the rest, stands.
	 */
	public static <T> List<T> putNeededTakeOnTop(List<T> takes, Predicate<T> needed)
	{
		List<T> ordered = new ArrayList<>(takes.size());
		for (T take : takes)
			if (!needed.test(take))
				ordered.add(take);
		for (T take : takes)
			if (needed.test(take))
				ordered.add(take);
		return ordered;
	}

	private String getItemName(int id)
	{
		String name = names.apply(id);
		return name == null ? "" : Text.removeTags(name);
	}

	/**
	 * Stack row for each needed id in the tile's stack. Lines Ground Items draws take rows in stack order, so a needed
	 * name lands on its own Ground Items line; needed ids Ground Items does not draw go above that stack.
	 */
	static Map<Integer, Integer> computeStackRows(List<Integer> stack, Set<Integer> needed, IntPredicate shownByGroundItems)
	{
		Map<Integer, Integer> rows = new LinkedHashMap<>();
		int row = 0;
		for (int id : stack)
			if (shownByGroundItems.test(id))
			{
				if (needed.contains(id))
					rows.put(id, row);
				row++;
			}
		for (int id : stack)
			if (needed.contains(id) && !rows.containsKey(id))
				rows.put(id, row++);
		return rows;
	}
}
