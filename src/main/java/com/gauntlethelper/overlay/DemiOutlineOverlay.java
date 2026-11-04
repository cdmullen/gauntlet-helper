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
import com.gauntlethelper.game.EnemyType;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Every living demi in view, in its type's color, on its true tile. Separate from the needed-demi clickbox, which
 * stays the guidance cue.
 */
public class DemiOutlineOverlay extends Overlay
{
	private static final float OUTLINE_WIDTH = 1;

	private final Client client;
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;

	@Inject
	public DemiOutlineOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.highlightDemiBossesTrueTiles() || !plugin.arePrepCuesAllowed())
			return null;
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.npcs() == null)
			return null;
		Color previousColor = graphics.getColor();
		Stroke previousStroke = graphics.getStroke();
		try
		{
			graphics.setStroke(new BasicStroke(OUTLINE_WIDTH));
			for (NPC npc : view.npcs())
			{
				EnemyType type = npc.isDead() ? null : EnemyType.fromNpcId(npc.getId());
				Polygon tile = type == null || !type.isDemiBoss() ? null : computeTrueTile(view, npc);
				if (tile == null)
					continue;
				Color color = getColor(config, type);
				graphics.setColor(color);
				graphics.draw(tile);
			}
		}
		finally
		{
			graphics.setColor(previousColor);
			graphics.setStroke(previousStroke);
		}
		return null;
	}

	/** The server-side footprint, as for Hunllef's tile; the drawn tile when the world area is unknown. */
	private Polygon computeTrueTile(WorldView view, NPC npc)
	{
		WorldArea area = npc.getWorldArea();
		LocalPoint corner = area == null ? null : LocalPoint.fromWorld(view, area.toWorldPoint());
		if (corner == null)
			return npc.getCanvasTilePoly();
		return Perspective.getCanvasTileAreaPoly(client,
				corner.plus((area.getWidth() - 1) * Perspective.LOCAL_HALF_TILE_SIZE, (area.getHeight() - 1) * Perspective.LOCAL_HALF_TILE_SIZE),
				area.getWidth());
	}

	/** Dark beast, dragon and bear colors, defaults as in The Gauntlet plugin. */
	static Color getColor(GauntletHelperConfig config, EnemyType type)
	{
		switch (type)
		{
			case DARK_BEAST:
				return config.darkBeastColor();
			case DRAGON:
				return config.dragonColor();
			default:
				return config.bearColor();
		}
	}
}
