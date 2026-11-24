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
import com.gauntlethelper.config.RoomMapStyle;
import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.Requirement;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * A 7x7 map of the dungeon's rooms, drawn only from what the plugin has observed: lit rooms and the lit doors between
 * them, the player's room, home, Hunllef's room, resource nodes seen in lit rooms and each lit room's demi-boss. An
 * unlit room shows only the public layout: the four guaranteed demi rooms red, the other edge and corner rooms orange,
 * the rest dark. Nothing inside an unlit room is ever drawn. The plan decides which resources stay marked and outlines
 * the task rooms with their route, all within the same rule (RoomMapGuide). Only the background and the unlit rooms
 * follow the opacity setting; everything else is drawn solid. This class draws the Simple style; GauntletMapStyle draws
 * the default Classic style, Gauntlet Map's tiles, from the same rules.
 */
public class RoomMapOverlay extends Overlay
{
	static final Color BACKGROUND = new Color(0, 0, 0, 180);
	static final Color UNLIT_ROOM = new Color(48, 48, 48);
	static final Color LIT_ROOM = new Color(122, 114, 100);
	static final Color GUARANTEED_DEMI_ROOM = new Color(170, 35, 35);
	static final Color POSSIBLE_DEMI_ROOM = new Color(205, 115, 30);
	static final Color HUNLLEF_ROOM = new Color(70, 22, 22);
	static final Color MELEE_ICON = new Color(225, 60, 45);
	static final Color RANGED_ICON = new Color(70, 200, 70);
	static final Color MAGIC_ICON = new Color(70, 140, 255);
	static final Color HOME_ICON = MinimapOverlay.SPAWN_CRYSTAL;
	static final Color HUNLLEF_ICON = MinimapOverlay.HUNLLEF_CRYSTAL;
	/** Space around the grid, in pixels. */
	static final int PADDING = 3;
	/** The demi icons' round strokes, one per width; the width follows the tile size setting. */
	private static final Map<Float, Stroke> ROUND_STROKES = new ConcurrentHashMap<>();

	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	private final Guidance guidance;
	private final ItemManager itemManager;

	@Inject
	public RoomMapOverlay(GauntletHelperPlugin plugin, GauntletHelperConfig config, Guidance guidance, ItemManager itemManager)
	{
		this.plugin = plugin;
		this.config = config;
		this.guidance = guidance;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.UNDER_WIDGETS);
		setPriority(TopLeftStack.MAP_PRIORITY);
		setMovable(true);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showRoomMap() || !plugin.arePrepCuesAllowed())
			return null;
		GauntletMap map = guidance.getGauntletMap();
		if (map == null || !map.isInitialized())
			return null;
		GauntletRoom currentRoom = guidance.getCurrentMapRoom();
		GauntletRoom playerRoom = getShownPlayerRoom(map, currentRoom);
		// The plan's parts stay hidden on the barrier's tiles, as in Hunllef's room, though the marker shows at home.
		boolean planShown = RoomMapGuide.isRouteShownWith(currentRoom);
		RoomMapGuide guide = planShown ? buildGuide() : RoomMapGuide.NONE;
		if (config.roomMapStyle() == RoomMapStyle.SIMPLE)
			return drawMap(graphics, map, playerRoom, guide, config);
		GauntletMapStyle.ItemIcons icons = planShown ? resource -> itemManager.getImage(resource.getItemId()) : null;
		return GauntletMapStyle.drawMap(graphics, map, playerRoom, plugin.getMode(), guide, icons, config);
	}

	/**
	 * The room the player is marked in. The map draws only during preparation, when the player is still on home's side of
	 * Hunllef's barrier, but the tiles just in front of the barrier fall inside Hunllef's room on the grid; a player
	 * counted there is marked at home.
	 */
	static GauntletRoom getShownPlayerRoom(GauntletMap map, GauntletRoom playerRoom)
	{
		if (playerRoom == null || !playerRoom.isHunllefRoom())
			return playerRoom;
		return map.getRoom(GauntletMap.HOME);
	}

	/** What the plan adds to the map this frame: which resources are still short, and the task rooms when shown. */
	private RoomMapGuide buildGuide()
	{
		boolean route = config.showRoomMapRoute();
		GauntletRoom destination = route ? guidance.getDestinationMapRoom() : null;
		GauntletRoom next = route && config.showNextTask() ? guidance.getNextMapRoom() : null;
		boolean resources = config.showRoomMapResources();
		return RoomMapGuide.of(resources ? plugin.getPlan() : null, config.hideCompletedMapResourceIcons(), destination, next);
	}

	/** Draws the Simple style with no plan. */
	static Dimension drawMap(Graphics2D graphics, GauntletMap map, GauntletRoom playerRoom, GauntletHelperConfig config)
	{
		return drawMap(graphics, map, playerRoom, RoomMapGuide.NONE, config);
	}

	/** Draws the Simple style with its top-left corner at the graphics origin and returns its size. */
	static Dimension drawMap(Graphics2D graphics, GauntletMap map, GauntletRoom playerRoom, RoomMapGuide guide, GauntletHelperConfig config)
	{
		if (!RoomMapGuide.isRouteShownWith(playerRoom))
			guide = RoomMapGuide.NONE;
		int tile = getTileSize(config);
		int side = GauntletMap.SIZE * tile + 2 * PADDING;
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			Composite faded = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getOpacity(config));
			g.setComposite(faded);
			g.setColor(BACKGROUND);
			g.fillRect(0, 0, side, side);
			g.setComposite(AlphaComposite.SrcOver);
			for (GauntletRoom room : map.getRooms())
				drawConnections(g, map, room, tile);
			for (GauntletRoom room : map.getRooms())
			{
				g.setComposite(isFaded(room) ? faded : AlphaComposite.SrcOver);
				drawRoom(g, room, tile, guide.getMarkedResources(room), config);
			}
			g.setComposite(AlphaComposite.SrcOver);
			RoomMapGuide.RoomBounds roomBounds = room -> getRoomBounds(room.getRow(), room.getColumn(), tile);
			RoomMapGuide.drawCurrentRoom(g, playerRoom, config, roomBounds);
			guide.draw(g, map, playerRoom, config, roomBounds);
		}
		finally
		{
			g.dispose();
		}
		return new Dimension(side, side);
	}

	/** The one Map tile size both styles use. */
	static int getTileSize(GauntletHelperConfig config)
	{
		return GauntletMapStyle.getTileSize(config);
	}

	/** The one Map opacity, for the background and the unlit rooms. */
	static float getOpacity(GauntletHelperConfig config)
	{
		return GauntletMapStyle.getOpacity(config);
	}

	/** The square a room fills: its grid cell less a gap on every side, where the doors between rooms are drawn. */
	static Rectangle getRoomBounds(int row, int column, int tile)
	{
		int gap = getGap(tile);
		return new Rectangle(PADDING + column * tile + gap, PADDING + row * tile + gap, tile - 2 * gap, tile - 2 * gap);
	}

	private static int getGap(int tile)
	{
		return Math.max(2, tile / 8);
	}

	/**
	 * A room's fill. Unlit rooms show only the fixed layout. A lit edge or corner room keeps its red or orange until
	 * its contents have been observed (a demi could still be there); a seen demi, an empty room or a killed demi shows
	 * as a plain lit room.
	 */
	static Color getRoomFill(GauntletRoom room)
	{
		if (room.isHunllefRoom())
			return HUNLLEF_ROOM;
		if (room.isDiscovered() && (!room.canHaveDemiSpawn() || room.getDemiStatus() != GauntletRoom.DemiStatus.UNKNOWN))
			return LIT_ROOM;
		if (room.isGuaranteedDemiSpawn())
			return GUARANTEED_DEMI_ROOM;
		if (room.canHaveDemiSpawn())
			return POSSIBLE_DEMI_ROOM;
		return UNLIT_ROOM;
	}

	/** Whether a room follows the opacity setting: an unlit room, red, orange or dark, as the Classic style's unlit tiles. */
	static boolean isFaded(GauntletRoom room)
	{
		return !room.isDiscovered() && !room.isHunllefRoom();
	}

	/** The living demi seen in a lit room, or null; never anything for an unlit room. */
	static EnemyType getShownDemi(GauntletRoom room)
	{
		if (!room.isDiscovered() || room.getDemiStatus() != GauntletRoom.DemiStatus.ALIVE)
			return null;
		for (GauntletRoom.Enemy enemy : room.getEnemies())
			if (enemy.isDemiBoss() && enemy.isAlive())
				return enemy.getType();
		return null;
	}

	/** The gathered resources with an active node seen in a lit room, in instruction order. */
	static List<Requirement> getShownResources(GauntletRoom room)
	{
		List<Requirement> shown = new ArrayList<>();
		if (!room.isDiscovered())
			return shown;
		for (Requirement resource : Requirement.GATHERED)
			if (room.countResourceNodes(resource) > 0)
				shown.add(resource);
		return shown;
	}

	/** Bear melee, dark beast ranged, dragon magic. */
	static Color getDemiColor(EnemyType type)
	{
		switch (type)
		{
			case BEAR:
				return MELEE_ICON;
			case DARK_BEAST:
				return RANGED_ICON;
			default:
				return MAGIC_ICON;
		}
	}

	private static void drawRoom(Graphics2D g, GauntletRoom room, int tile, List<Requirement> resources, GauntletHelperConfig config)
	{
		Rectangle bounds = getRoomBounds(room.getRow(), room.getColumn(), tile);
		g.setColor(getRoomFill(room));
		g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		if (room.isHunllefRoom())
		{
			drawCrystal(g, bounds, HUNLLEF_ICON);
			return;
		}
		if (!room.isDiscovered())
			return;
		if (room.isStartingRoom())
			drawCrystal(g, bounds, HOME_ICON);
		EnemyType demi = getShownDemi(room);
		if (demi != null)
			drawDemiIcon(g, bounds, demi);
		if (config.showRoomMapResources())
			drawResources(g, bounds, resources, config);
	}

	/** A bar across the gap for each lit door, drawn once from the room on its west or north side. */
	private static void drawConnections(Graphics2D g, GauntletMap map, GauntletRoom room, int tile)
	{
		if (!room.isDiscovered())
			return;
		int gap = getGap(tile);
		int width = Math.max(2, tile / 3);
		Rectangle bounds = getRoomBounds(room.getRow(), room.getColumn(), tile);
		g.setColor(LIT_ROOM);
		GauntletRoom east = map.getRoom(room.getRow(), room.getColumn() + 1);
		if (isLitDoor(room, east, GauntletRoom.Direction.EAST))
			g.fillRect(bounds.x + bounds.width, bounds.y + (bounds.height - width) / 2, 2 * gap, width);
		GauntletRoom south = map.getRoom(room.getRow() + 1, room.getColumn());
		if (isLitDoor(room, south, GauntletRoom.Direction.SOUTH))
			g.fillRect(bounds.x + (bounds.width - width) / 2, bounds.y + bounds.height, width, 2 * gap);
	}

	private static boolean isLitDoor(GauntletRoom room, GauntletRoom neighbor, GauntletRoom.Direction direction)
	{
		return neighbor != null && neighbor.isDiscovered() && room.getConnection(direction) == GauntletRoom.Connection.OPEN;
	}

	/** A black-outlined diamond in the room's middle, as the minimap's spawn and Hunllef crystals. */
	private static void drawCrystal(Graphics2D g, Rectangle bounds, Color fill)
	{
		double cx = bounds.getCenterX();
		double cy = bounds.getCenterY();
		double half = bounds.width * 0.28;
		Path2D diamond = new Path2D.Double();
		diamond.moveTo(cx, cy - half);
		diamond.lineTo(cx + half * 0.75, cy - half * 0.2);
		diamond.lineTo(cx, cy + half);
		diamond.lineTo(cx - half * 0.75, cy - half * 0.2);
		diamond.closePath();
		g.setColor(fill);
		g.fill(diamond);
		g.setColor(Color.BLACK);
		g.setStroke(OverlayStrokes.THIN);
		g.draw(diamond);
	}

	/** A sword for melee, an arrow for ranged and an orb for magic, in the style's color with a black outline. */
	private static void drawDemiIcon(Graphics2D g, Rectangle bounds, EnemyType type)
	{
		double cx = bounds.getCenterX();
		double cy = bounds.getCenterY();
		double half = bounds.width * 0.32;
		Color color = getDemiColor(type);
		if (type == EnemyType.BEAR)
		{
			Path2D sword = new Path2D.Double();
			sword.moveTo(cx - half, cy + half);
			sword.lineTo(cx + half, cy - half);
			sword.moveTo(cx - half * 0.75, cy + half * 0.1);
			sword.lineTo(cx - half * 0.1, cy + half * 0.75);
			drawOutlined(g, sword, color, bounds.width);
			return;
		}
		if (type == EnemyType.DARK_BEAST)
		{
			Path2D arrow = new Path2D.Double();
			arrow.moveTo(cx - half, cy + half);
			arrow.lineTo(cx + half, cy - half);
			arrow.moveTo(cx + half, cy - half);
			arrow.lineTo(cx + half * 0.15, cy - half);
			arrow.moveTo(cx + half, cy - half);
			arrow.lineTo(cx + half, cy - half * 0.15);
			drawOutlined(g, arrow, color, bounds.width);
			return;
		}
		Ellipse2D orb = new Ellipse2D.Double(cx - half * 0.8, cy - half * 0.8, half * 1.6, half * 1.6);
		g.setColor(color);
		g.fill(orb);
		g.setColor(Color.BLACK);
		g.setStroke(OverlayStrokes.THIN);
		g.draw(orb);
	}

	private static void drawOutlined(Graphics2D g, Path2D shape, Color color, int roomWidth)
	{
		float width = Math.max(2f, roomWidth / 8f);
		g.setColor(Color.BLACK);
		g.setStroke(getRoundStroke(width + 2));
		g.draw(shape);
		g.setColor(color);
		g.setStroke(getRoundStroke(width));
		g.draw(shape);
	}

	private static Stroke getRoundStroke(float width)
	{
		return ROUND_STROKES.computeIfAbsent(width, w -> new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
	}

	/** A small square per resource along the room's bottom edge, in each resource's outline color. */
	private static void drawResources(Graphics2D g, Rectangle bounds, List<Requirement> resources, GauntletHelperConfig config)
	{
		int size = Math.max(3, bounds.width / 5);
		int x = bounds.x + 1;
		int y = bounds.y + bounds.height - size - 1;
		for (Requirement resource : resources)
		{
			if (x + size > bounds.x + bounds.width)
				return;
			Color color = ResourceSettings.getResourceOutlineColor(config, resource);
			g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue()));
			g.fillRect(x, y, size, size);
			g.setColor(Color.BLACK);
			g.setStroke(OverlayStrokes.THIN);
			g.drawRect(x, y, size - 1, size - 1);
			x += size;
		}
	}
}
