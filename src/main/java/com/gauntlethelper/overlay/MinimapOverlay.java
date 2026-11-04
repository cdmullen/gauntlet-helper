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
import com.gauntlethelper.config.ResourceSettings;
import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** The minimap markers: the route, the spawn and Hunllef room crystals, resource icons and the demi-boss icons. */
public class MinimapOverlay extends Overlay
{
	/** Spawn room crystal (teal) and Hunllef room crystal (red). */
	static final Color SPAWN_CRYSTAL = new Color(0, 220, 220);
	static final Color HUNLLEF_CRYSTAL = new Color(220, 40, 40);
	static final int CRYSTAL_SIZE = 13;
	private static final BufferedImage SPAWN_ICON = createCrystal(SPAWN_CRYSTAL);
	private static final BufferedImage HUNLLEF_ICON = createCrystal(HUNLLEF_CRYSTAL);

	private final Client client;
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	private final Guidance guidance;
	private final ItemManager itemManager;
	private final SkillIconManager skillIcons;

	@Inject
	public MinimapOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config, Guidance guidance,
			ItemManager itemManager, SkillIconManager skillIcons)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.guidance = guidance;
		this.itemManager = itemManager;
		this.skillIcons = skillIcons;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.arePrepCuesAllowed())
		{
			return null;
		}
		if (config.showDemiBossMinimapIcons())
		{
			drawDemiIcons(graphics);
		}
		if (!config.showMinimapMarkers())
		{
			return null;
		}
		drawRoute(graphics);
		drawRoomIcons(graphics);
		Requirements plan = plugin.getPlan();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.getObjects().entrySet())
		{
			TileObject object = entry.getKey();
			if (!guidance.isActive(object, entry.getValue()))
			{
				continue;
			}
			Point point = Perspective.localToMinimap(client, object.getLocalLocation());
			if (point == null)
			{
				continue;
			}
			Requirement resource = entry.getValue().resource;
			if (ResourceVisibility.isMinimapVisible(resource, plan, config))
			{
				drawResourceIcon(graphics, resource, point);
			}
		}
		return null;
	}

	/** The resource's gathering skill icon, as in The Gauntlet plugin; the item icon if there is none. */
	private void drawResourceIcon(Graphics2D graphics, Requirement resource, Point point)
	{
		Skill skill = getResourceSkill(resource);
		BufferedImage icon = skill == null || skillIcons == null ? null : skillIcons.getSkillImage(skill, true);
		if (icon != null)
			graphics.drawImage(icon, point.getX() - icon.getWidth() / 2, point.getY() - icon.getHeight() / 2, null);
		else
			graphics.drawImage(itemManager.getImage(resource.getItemId()), point.getX() - 6, point.getY() - 6, 12, 12, null);
	}

	/** Ore mining, bark woodcutting, linum tirinum farming, grym leaf herblore, fish fishing. */
	static Skill getResourceSkill(Requirement resource)
	{
		switch (resource)
		{
			case ORE:
				return Skill.MINING;
			case BARK:
				return Skill.WOODCUTTING;
			case LINUM_TIRINUM:
				return Skill.FARMING;
			case GRYM_LEAVES:
				return Skill.HERBLORE;
			case FISH:
				return Skill.FISHING;
			default:
				return null;
		}
	}

	/** The Gauntlet plugin's demi-boss minimap icons: a small combat skill icon on each living demi in view. */
	private void drawDemiIcons(Graphics2D graphics)
	{
		WorldView view = client.getTopLevelWorldView();
		if (skillIcons == null || view == null || view.npcs() == null)
			return;
		for (NPC npc : view.npcs())
		{
			EnemyType type = npc.isDead() ? null : EnemyType.fromNpcId(npc.getId());
			Point point = type == null || !type.isDemiBoss() ? null : npc.getMinimapLocation();
			if (point == null)
				continue;
			BufferedImage icon = skillIcons.getSkillImage(getDemiSkill(type), true);
			if (icon != null)
				graphics.drawImage(icon, point.getX() - icon.getWidth() / 2, point.getY() - icon.getHeight() / 2, null);
		}
	}

	/** Bear attack, dark beast ranged, dragon magic, as in The Gauntlet plugin. */
	static Skill getDemiSkill(EnemyType type)
	{
		switch (type)
		{
			case BEAR:
				return Skill.ATTACK;
			case DARK_BEAST:
				return Skill.RANGED;
			default:
				return Skill.MAGIC;
		}
	}

	/** Reuse the collision-aware current-task path; next-task hints never add another line. */
	private void drawRoute(Graphics2D graphics)
	{
		if (!config.showMinimapRouteLine() || plugin.isTeleportSuggested())
			return;
		WorldView view = client.getTopLevelWorldView();
		Ellipse2D area = view == null ? null : getMinimapArea();
		if (area == null)
			return;
		// Clip the stroke as well as its center so it cannot paint over the minimap frame.
		Graphics2D routeGraphics = (Graphics2D) graphics.create();
		try
		{
			routeGraphics.clip(area);
			Requirement resource = guidance.getTargetResource();
			routeGraphics.setColor(resource == null ? config.currentActionColor() : ResourceSettings.getResourceOutlineColor(config, resource));
			routeGraphics.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Point last = null;
			for (Point step : guidance.getPath())
			{
				LocalPoint local = LocalPoint.fromScene(step.getX(), step.getY(), view);
				// The widget clip, rather than the default fixed-radius projection, handles map size/zoom.
				Point next = Perspective.localToMinimap(client, local, 32767);
				if (last != null && next != null)
					routeGraphics.drawLine(last.getX(), last.getY(), next.getX(), next.getY());
				last = next;
			}
		}
		finally
		{
			routeGraphics.dispose();
		}
	}

	/** The visible minimap disc, inset from the frame; null while the minimap is hidden or absent. */
	private Ellipse2D getMinimapArea()
	{
		int layout = !client.isResized() ? InterfaceID.Toplevel.MINIMAP
				: client.getVarbitValue(VarbitID.RESIZABLE_STONE_ARRANGEMENT) == 1
				? InterfaceID.ToplevelPreEoc.MINIMAP : InterfaceID.ToplevelOsrsStretch.MINIMAP;
		Widget widget = client.getWidget(layout);
		if (widget == null || widget.isHidden())
			return null;
		Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.width <= 4 || bounds.height <= 4)
			return null;
		return new Ellipse2D.Double(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4);
	}

	/** A crystal at the center of the spawn room and of Hunllef's room, once the spawn side is known. */
	private void drawRoomIcons(Graphics2D graphics)
	{
		WorldView view = client.getTopLevelWorldView();
		Point hunllef = guidance.getOpening().getHunllefRoom();
		Ellipse2D area = view == null || hunllef == null ? null : getMinimapArea();
		if (area == null)
			return;
		Graphics2D iconGraphics = (Graphics2D) graphics.create();
		try
		{
			iconGraphics.clip(area);
			drawRoomIcon(iconGraphics, view, area, GauntletMap.HOME, SPAWN_ICON);
			drawRoomIcon(iconGraphics, view, area, hunllef, HUNLLEF_ICON);
		}
		finally
		{
			iconGraphics.dispose();
		}
	}

	private void drawRoomIcon(Graphics2D graphics, WorldView view, Ellipse2D area, Point room, BufferedImage icon)
	{
		// getRoomCenter is the spawn side doors' midpoint, up to two tiles off the room's middle. Rooms lie on the
		// 16-tile world grid, so draw between the room's two middle tiles (7 and 8).
		Point center = guidance.getOpening().getRoomCenter(room);
		int pitch = guidance.getOpening().getRoomPitch();
		int x = Math.floorDiv(center.getX(), pitch) * pitch + pitch / 2 - 1;
		int y = Math.floorDiv(center.getY(), pitch) * pitch + pitch / 2 - 1;
		LocalPoint tile = LocalPoint.fromWorld(view, new WorldPoint(x, y, view.getPlane()));
		LocalPoint local = tile == null ? null : new LocalPoint(tile.getX() + Perspective.LOCAL_HALF_TILE_SIZE,
				tile.getY() + Perspective.LOCAL_HALF_TILE_SIZE, view);
		Point point = local == null ? null : Perspective.localToMinimap(client, local, 32767);
		if (point == null || !area.contains(point.getX(), point.getY()))
			return;
		graphics.drawImage(icon, point.getX() - CRYSTAL_SIZE / 2, point.getY() - CRYSTAL_SIZE / 2, null);
	}

	/** A black-outlined diamond with a light left facet. */
	static BufferedImage createCrystal(Color fill)
	{
		BufferedImage image = new BufferedImage(CRYSTAL_SIZE, CRYSTAL_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Path2D diamond = new Path2D.Double();
			diamond.moveTo(6.5, 0.5);
			diamond.lineTo(11.5, 5.0);
			diamond.lineTo(6.5, 12.5);
			diamond.lineTo(1.5, 5.0);
			diamond.closePath();
			g.setColor(fill);
			g.fill(diamond);
			Path2D facet = new Path2D.Double();
			facet.moveTo(6.5, 1.5);
			facet.lineTo(6.5, 11.0);
			facet.lineTo(2.5, 5.0);
			facet.closePath();
			g.setColor(new Color(255, 255, 255, 110));
			g.fill(facet);
			g.setColor(Color.BLACK);
			g.draw(diamond);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
