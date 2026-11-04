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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import net.runelite.api.Point;
import net.runelite.api.TileObject;

/**
 * World captions with a full 1px black outline, so a yellow "Pick grym leaf" stays readable on the yellow grym root and
 * the yellow path under it. OverlayUtil.renderTextLocation only adds a drop shadow.
 */
public final class OutlinedText
{
	/** Gap in pixels between a resource node's top edge on screen and its caption's baseline. */
	static final int ABOVE_MODEL_GAP = 6;

	/** The fixed caption height offset, the anchor when an object has no clickbox. */
	static final int DEFAULT_Z_OFFSET = 40;

	private OutlinedText() {}

	/**
	 * Draws text at a baseline point: black at the eight surrounding 1px offsets, then the color on top at full
	 * opacity (as renderTextLocation does). The Graphics2D color is restored afterwards.
	 */
	public static void draw(Graphics2D graphics, Point location, String text, Color color)
	{
		if (location == null || text == null || text.isEmpty())
			return;
		Color previous = graphics.getColor();
		int x = location.getX();
		int y = location.getY();
		graphics.setColor(Color.BLACK);
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				if (dx != 0 || dy != 0)
					graphics.drawString(text, x + dx, y + dy);
		graphics.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
		graphics.drawString(text, x, y);
		graphics.setColor(previous);
	}

	/**
	 * Where a resource node's caption goes: centered on the node, with its baseline just above the node's clickbox,
	 * so the text floats over the model instead of on it at any camera pitch. Without a clickbox it uses the fixed
	 * offset.
	 */
	public static Point findAboveModel(Graphics2D graphics, TileObject object, String text)
	{
		Point anchor = object.getCanvasTextLocation(graphics, text, DEFAULT_Z_OFFSET);
		if (anchor == null)
			return null;
		return findAboveShape(anchor, object.getClickbox());
	}

	static Point findAboveShape(Point anchor, Shape model)
	{
		if (model == null)
			return anchor;
		Rectangle bounds = model.getBounds();
		if (bounds.isEmpty())
			return anchor;
		return new Point(anchor.getX(), Math.min(anchor.getY(), bounds.y - ABOVE_MODEL_GAP));
	}
}
