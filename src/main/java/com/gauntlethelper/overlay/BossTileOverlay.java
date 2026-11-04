/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, cdmullen
 * Copyright (c) 2023, rdutta <https://github.com/rdutta>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.gauntlethelper.overlay;

import com.gauntlethelper.GauntletHelperPlugin;
import com.gauntlethelper.boss.PrayerTileMode;
import com.gauntlethelper.boss.TornadoTileMode;
import com.gauntlethelper.config.GauntletHelperConfig;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Adapted from The Gauntlet BossOverlay: current prayer colors and tornado true tiles. The room tile markers are a
 * Ground Markers tile pack, not part of the reference overlay.
 */
public class BossTileOverlay extends Overlay
{
	// A Ground Markers tile pack for Hunllef's room: region tiles on plane 1 of the instance
	// templates, 7512 for The Gauntlet and 7768 for the Corrupted Gauntlet. Two tiles in the middle of each side.
	private static final int[] TILE_PACK_REGIONS = {7512, 7768};
	private static final int[][] TILE_PACK_TILES = {{56, 59}, {55, 59}, {59, 56}, {59, 55}, {52, 56}, {52, 55},
			{56, 52}, {55, 52}};
	private static final List<WorldPoint> TILE_PACK = getTilePack();

	private final Client client;
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;

	@Inject
	public BossTileOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_HIGH);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	public static Color getPrayerColor(int id)
	{
		switch (id)
		{
			case NpcID.CRYSTAL_HUNLLEF_MELEE_HM:
			case NpcID.CRYSTAL_HUNLLEF_MELEE:
				return Color.RED;
			case NpcID.CRYSTAL_HUNLLEF_RANGED_HM:
			case NpcID.CRYSTAL_HUNLLEF_RANGED:
				return Color.GREEN;
			case NpcID.CRYSTAL_HUNLLEF_MAGIC_HM:
			case NpcID.CRYSTAL_HUNLLEF_MAGIC:
				return Color.BLUE;
			case NpcID.CRYSTAL_HUNLLEF_DEATH_HM:
			case NpcID.CRYSTAL_HUNLLEF_DEATH:
				return Color.WHITE;
			default:
				return null;
		}
	}

	/**
	 * Hunllef's tile color in the chosen mode: the prayer color (On), the reference plugin's Inverted swap of
	 * green and blue, or the plain outline color (Off). Null for any other NPC.
	 */
	public static Color getTileColor(int id, PrayerTileMode mode, Color plain)
	{
		Color prayer = getPrayerColor(id);
		if (prayer == null || mode == PrayerTileMode.ON)
			return prayer;
		if (mode == PrayerTileMode.OFF)
			return plain;
		if (Color.GREEN.equals(prayer))
			return Color.BLUE;
		if (Color.BLUE.equals(prayer))
			return Color.GREEN;
		return prayer;
	}

	/** The tile pack's template points, as Ground Markers stores them (region, region x/y, plane). */
	public static List<WorldPoint> getTilePack()
	{
		List<WorldPoint> points = new ArrayList<>();
		for (int region : TILE_PACK_REGIONS)
			for (int[] tile : TILE_PACK_TILES)
				points.add(WorldPoint.fromRegion(region, tile[0], tile[1], 1));
		return points;
	}

	/** Ground Markers' default look: the fill is the outline color at a fifth of its opacity (50 of 255). */
	public static Color getMarkerFill(Color color)
	{
		return new Color(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() * 50 / 255);
	}

	public static boolean isTornado(int id)
	{
		return id == NpcID.CRYSTAL_HUNLLEF_CRYSTALS || id == NpcID.CRYSTAL_HUNLLEF_CRYSTALS_HM;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.isInBossFight() || client.getGameState() != GameState.LOGGED_IN)
			return null;
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.npcs() == null)
			return null;
		Color previousColor = graphics.getColor();
		Stroke previousStroke = graphics.getStroke();
		try
		{
			List<NPC> tornadoes = new ArrayList<>();
			for (NPC npc : view.npcs())
				if (!npc.isDead() && isTornado(npc.getId()))
					tornadoes.add(npc);
			renderTileMarkers(graphics, view);
			renderTornadoes(graphics, view, tornadoes);
			for (NPC npc : view.npcs())
			{
				if (npc.isDead())
					continue;
				Color color = getTileColor(npc.getId(), config.colorTileByPrayer(), config.hunllefOutlineColor());
				Polygon polygon = null;
				if (color != null && config.outlineHunllefTile())
				{
					WorldArea area = npc.getWorldArea();
					LocalPoint corner = area == null ? null : LocalPoint.fromWorld(view, area.toWorldPoint());
					if (corner != null)
						polygon = Perspective.getCanvasTileAreaPoly(client,
								corner.plus((area.getWidth() - 1) * Perspective.LOCAL_HALF_TILE_SIZE,
										(area.getHeight() - 1) * Perspective.LOCAL_HALF_TILE_SIZE),
								area.getWidth());
					else
						polygon = npc.getCanvasTilePoly();
				}
				if (polygon != null)
				{
					Color fill = config.hunllefFillColor();
					if (fill != null && fill.getAlpha() > 0)
					{
						graphics.setColor(fill);
						graphics.fill(polygon);
					}
					graphics.setColor(color);
					graphics.setStroke(new BasicStroke(config.hunllefTileOutlineWidth()));
					graphics.draw(polygon);
				}
			}
		}
		finally
		{
			graphics.setColor(previousColor);
			graphics.setStroke(previousStroke);
		}
		return null;
	}

	// Drawn first, under the tornado and Hunllef tiles. toLocalInstance maps each template tile to wherever the
	// instance placed (and rotated) its chunk, as Ground Markers does.
	private void renderTileMarkers(Graphics2D graphics, WorldView view)
	{
		if (!config.showHunllefRoomTileMarkers())
			return;
		Color color = config.hunllefRoomTileMarkerColor();
		if (color == null)
			return;
		Color fill = getMarkerFill(color);
		Stroke border = OverlayStrokes.MEDIUM;
		for (WorldPoint template : TILE_PACK)
			for (WorldPoint point : WorldPoint.toLocalInstance(view, template))
			{
				LocalPoint local = LocalPoint.fromWorld(view, point);
				if (local == null)
					continue;
				Polygon polygon = Perspective.getCanvasTilePoly(client, local);
				if (polygon != null)
					OverlayUtil.renderPolygon(graphics, polygon, color, fill, border);
			}
	}

	// Reference renderTornadoes copied with local configuration names and the current WorldView lookup.
	private void renderTornadoes(final Graphics2D graphics2D, final WorldView view, final List<NPC> tornadoes)
	{
		if (config.outlineTornadoTile() == TornadoTileMode.OFF || tornadoes.isEmpty())
		{
			return;
		}

		final boolean trueTile = config.outlineTornadoTile() == TornadoTileMode.TRUE_TILE;

		for (final NPC tornado : tornadoes)
		{
			final Polygon polygon;

			if (trueTile)
			{
				final WorldPoint worldPoint = tornado.getWorldLocation();

				if (worldPoint == null)
				{
					continue;
				}

				final LocalPoint localPoint = LocalPoint.fromWorld(view, worldPoint);

				if (localPoint == null)
				{
					continue;
				}

				polygon = Perspective.getCanvasTilePoly(client, localPoint);
			}
			else
			{
				polygon = Perspective.getCanvasTilePoly(client, tornado.getLocalLocation());
			}

			if (polygon == null)
			{
				continue;
			}

			OverlayUtil.renderPolygon(graphics2D, polygon, config.tornadoOutlineColor(), config.tornadoFillColor(),
					new BasicStroke(config.tornadoTileOutlineWidth()));
		}
	}
}
