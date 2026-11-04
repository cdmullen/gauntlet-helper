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

// Resource-model rendering adapted from The Gauntlet MazeOverlay; T1 visibility and integration for this plugin.
package com.gauntlethelper.overlay;

import com.gauntlethelper.GauntletHelperPlugin;
import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.config.ResourceSettings;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.GameObject;
import net.runelite.api.TileObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ColorUtil;

/** Model outlines do not depend on a projected clickbox or the current path target. */
public class ResourceOverlay extends Overlay
{
	static final int MODEL_OUTLINE_WIDTH = 1;
	static final int TILE_OUTLINE_WIDTH = 1;
	static final int TILE_FILL_ALPHA = 50;

	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	private final Guidance guidance;
	private final ModelOutlineRenderer modelOutlines;

	@Inject
	public ResourceOverlay(GauntletHelperPlugin plugin, GauntletHelperConfig config, Guidance guidance, ModelOutlineRenderer modelOutlines)
	{
		this.plugin = plugin;
		this.config = config;
		this.guidance = guidance;
		this.modelOutlines = modelOutlines;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_HIGH);
		setLayer(OverlayLayer.UNDER_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.arePrepCuesAllowed() || !config.highlightResourceNodes() || guidance.isInventorySpaceReturn())
		{
			return null;
		}
		Requirements plan = plugin.getPlan();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.getObjects().entrySet())
		{
			TileObject object = entry.getKey();
			Requirement resource = entry.getValue().resource;
			if (!(object instanceof GameObject)
					|| (!ResourceVisibility.isVisible(resource, plan, config) || !guidance.isResourceEligible(resource))
					|| !guidance.isActive(object, entry.getValue()))
			{
				continue;
			}
			GameObject node = (GameObject) object;
			Color outline = ResourceSettings.getResourceOutlineColor(config, resource);
			if (MODEL_OUTLINE_WIDTH > 0 && node.getRenderable() != null)
			{
				// The client's getModel can throw for an animated object, mostly right after a zone rebuild, and repeat for
				// a few frames. Only this node's model outline is lost; its tile and the other nodes still draw.
				try
				{
					modelOutlines.drawOutline(node, MODEL_OUTLINE_WIDTH, outline, 1);
				}
				catch (NullPointerException ignored)
				{
					// Skipped for this frame.
				}
			}
			if (TILE_OUTLINE_WIDTH > 0)
			{
				Polygon tile = node.getCanvasTilePoly();
				if (tile != null)
				{
					Color fill = ColorUtil.colorWithAlpha(outline, TILE_FILL_ALPHA);
					OverlayUtil.renderPolygon(graphics, tile, outline, fill, new BasicStroke(TILE_OUTLINE_WIDTH));
				}
			}
		}
		return null;
	}
}
