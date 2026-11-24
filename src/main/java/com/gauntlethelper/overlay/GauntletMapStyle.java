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

import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.Requirement;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.runelite.client.ui.overlay.components.BackgroundComponent;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.util.ImageUtil;

/**
 * The room map in the look of the Gauntlet Map plugin: its room tiles (resources under gauntletmap/, BSD 2-Clause,
 * Copyright (c) 2023, Tyler; see LICENSE.txt there) in a standard RuneLite overlay panel. As in Gauntlet Map, a lit
 * room with a living demi-boss or with fish or grym leaves shows that tile instead of the plain lit one. Every tile
 * follows the same observed-only rules as the Simple style: an unlit room shows only the fixed layout. A demi room not
 * yet ruled out keeps its ordinary tile with a border on its floor's edge, red for the four guaranteed demi rooms and a
 * dim orange for the other edge and corner rooms. Only the panel and the unlit rooms follow the opacity setting; every
 * marked room and border is drawn solid. The plan's parts (RoomMapGuide) go on top: which resources stay marked, bark,
 * linum tirinum and ore as item icons in a grid with the room's fish and grym leaves, and the task rooms' outlines and
 * route.
 */
final class GauntletMapStyle
{
	/** The tiles' own size; other sizes are scaled from it. */
	static final int NATIVE_TILE = 34;
	static final int BORDER = ComponentConstants.STANDARD_BORDER;
	/** The four guaranteed demi rooms' border, and the dimmer one of the other edge and corner rooms. */
	static final Color GUARANTEED_DEMI_BORDER = RoomMapOverlay.GUARANTEED_DEMI_ROOM;
	static final Color POSSIBLE_DEMI_BORDER = new Color(205, 115, 30, 150);
	/** A demi room border's width at the tiles' own size; it scales with the tile, never under DEMI_BORDER_MIN_WIDTH. */
	static final float DEMI_BORDER_WIDTH = 2.4f;
	static final float DEMI_BORDER_MIN_WIDTH = 1.5f;
	/**
	 * The player's room with Show current room resources on, in native tile pixels: the player mark shrunk on the left, the
	 * resource icons in a column on the right, all inside the area the home tile's outline leaves free.
	 */
	static final Rectangle CURRENT_ROOM_AREA = new Rectangle(7, 9, 21, 17);
	static final double CURRENT_ROOM_MARK_SCALE = 0.6;
	static final double CURRENT_ROOM_ICON_SCALE = 0.55;
	static final int CURRENT_ROOM_MARK_LEFT = 8;
	static final int CURRENT_ROOM_ICON_LEFT = 18;
	static final int CURRENT_ROOM_MIDDLE = 17;
	static final int CURRENT_ROOM_GAP = 1;
	/**
	 * The room's floor inside a tile, in native pixels: where the resource icons go when more than fish and grym show,
	 * and the edge a demi room's border follows.
	 */
	static final Rectangle ICON_AREA = new Rectangle(6, 6, 22, 22);

	/** Every tile and variant built so far, by key; filled only through {@link #cached}. */
	private static Map<String, BufferedImage> tiles = new ConcurrentHashMap<>();
	/** The demi borders' strokes, one per width; the width follows the tile size setting. */
	private static final Map<Float, Stroke> BORDER_STROKES = new ConcurrentHashMap<>();

	/** What a room's tile shows; the images for each come from the tile set of the Gauntlet being run. */
	enum Tile
	{
		INACTIVE,
		ACTIVE,
		HUNLLEF,
		PLAYER,
		PLAYER_START,
		START_ROOM,
		FISH,
		GRYM,
		GRYM_FISH,
		MELEE_DEMI,
		RANGED_DEMI,
		MAGIC_DEMI
	}

	/** The client's item icon for a resource; it may still be loading, and then draws blank. */
	interface ItemIcons
	{
		Image getIcon(Requirement resource);
	}

	private GauntletMapStyle()
	{
	}

	/** Draws the map with no plan and no item icons. */
	static Dimension drawMap(Graphics2D graphics, GauntletMap map, GauntletRoom playerRoom, GauntletMode mode, GauntletHelperConfig config)
	{
		return drawMap(graphics, map, playerRoom, mode, RoomMapGuide.NONE, null, config);
	}

	/** Draws the map with its top-left corner at the graphics origin and returns its size. */
	static Dimension drawMap(Graphics2D graphics, GauntletMap map, GauntletRoom playerRoom, GauntletMode mode, RoomMapGuide guide, ItemIcons icons,
			GauntletHelperConfig config)
	{
		// Nothing of the plan in Hunllef's room.
		if (!RoomMapGuide.isRouteShownWith(playerRoom))
		{
			guide = RoomMapGuide.NONE;
			icons = null;
		}
		int tile = getTileSize(config);
		int side = GauntletMap.SIZE * tile + 2 * BORDER;
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			Composite faded = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, getOpacity(config));
			g.setComposite(faded);
			new BackgroundComponent(ComponentConstants.STANDARD_BACKGROUND_COLOR, new Rectangle(0, 0, side, side), true).render(g);
			for (GauntletRoom room : map.getRooms())
			{
				Rectangle bounds = getTileBounds(room.getRow(), room.getColumn(), tile);
				Tile kind = getTile(room, playerRoom, guide, config);
				g.setComposite(kind == Tile.INACTIVE ? faded : AlphaComposite.SrcOver);
				List<Requirement> marked = getGridResources(kind, room, guide, icons, config);
				if (!marked.isEmpty())
				{
					g.drawImage(getRoomImage(Tile.ACTIVE, room, mode, config, tile), bounds.x, bounds.y, null);
					drawIconGrid(g, bounds, marked, icons);
					continue;
				}
				g.drawImage(getRoomImage(kind, room, mode, guide, config, tile), bounds.x, bounds.y, null);
			}
			g.setComposite(AlphaComposite.SrcOver);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			for (GauntletRoom room : map.getRooms())
				drawDemiBorder(g, room, getTileBounds(room.getRow(), room.getColumn(), tile));
			RoomMapGuide.RoomBounds tileBounds = room -> getTileBounds(room.getRow(), room.getColumn(), tile);
			RoomMapGuide.drawCurrentRoom(g, playerRoom, config, tileBounds);
			guide.draw(g, map, playerRoom, config, tileBounds);
		}
		finally
		{
			g.dispose();
		}
		return new Dimension(side, side);
	}

	static int getTileSize(GauntletHelperConfig config)
	{
		return Math.max(8, Math.min(NATIVE_TILE, config.gauntletMapTileSize()));
	}

	static float getOpacity(GauntletHelperConfig config)
	{
		return Math.max(10, Math.min(100, config.gauntletMapOpacity())) / 100f;
	}

	/** A room's tile: the panel's border, then the 7x7 grid with north at the top and no gaps. */
	static Rectangle getTileBounds(int row, int column, int tile)
	{
		return new Rectangle(BORDER + column * tile, BORDER + row * tile, tile, tile);
	}

	/** Which tile a room shows with no plan. */
	static Tile getTile(GauntletRoom room, GauntletRoom playerRoom, GauntletHelperConfig config)
	{
		return getTile(room, playerRoom, RoomMapGuide.NONE, config);
	}

	/**
	 * Which tile a room shows. The player's tile replaces the room's own, as in Gauntlet Map. An unlit room is only ever
	 * layout: the inactive tile, with a demi room's border drawn over it (getDemiBorder). A lit room shows its living
	 * demi-boss, or else its fish and grym leaves while the guide marks them.
	 */
	static Tile getTile(GauntletRoom room, GauntletRoom playerRoom, RoomMapGuide guide, GauntletHelperConfig config)
	{
		if (room.isHunllefRoom())
			return Tile.HUNLLEF;
		if (isPlayerRoom(room, playerRoom))
			return room.isStartingRoom() ? Tile.PLAYER_START : Tile.PLAYER;
		if (!room.isDiscovered())
			return Tile.INACTIVE;
		if (room.isStartingRoom())
			return Tile.START_ROOM;
		EnemyType demi = RoomMapOverlay.getShownDemi(room);
		// Their demi tiles exist only for the edge and corner rooms, the only rooms a demi-boss spawns in.
		if (demi != null && !getPositionSuffix(room.getRow(), room.getColumn()).isEmpty())
			return getDemiTile(demi);
		return getResourceTile(getShownResources(room, guide, config));
	}

	/**
	 * A demi room's border color while the Simple style still fills it red or orange (its demi not yet seen or ruled
	 * out): red for a guaranteed demi room, dim orange for another edge or corner room; null for every other room.
	 */
	static Color getDemiBorder(GauntletRoom room)
	{
		Color fill = RoomMapOverlay.getRoomFill(room);
		if (fill == RoomMapOverlay.GUARANTEED_DEMI_ROOM)
			return GUARANTEED_DEMI_BORDER;
		if (fill == RoomMapOverlay.POSSIBLE_DEMI_ROOM)
			return POSSIBLE_DEMI_BORDER;
		return null;
	}

	/** The square a demi room's border follows: the floor's edge (ICON_AREA) at the tile's size. */
	static Rectangle2D getDemiBorderBounds(Rectangle bounds)
	{
		double scale = bounds.width / (double) NATIVE_TILE;
		double inset = getDemiBorderWidth(bounds.width) / 2.0;
		return new Rectangle2D.Double(bounds.x + ICON_AREA.x * scale + inset, bounds.y + ICON_AREA.y * scale + inset,
				ICON_AREA.width * scale - 2 * inset, ICON_AREA.height * scale - 2 * inset);
	}

	static float getDemiBorderWidth(int tile)
	{
		return Math.max(DEMI_BORDER_MIN_WIDTH, DEMI_BORDER_WIDTH * tile / NATIVE_TILE);
	}

	private static void drawDemiBorder(Graphics2D g, GauntletRoom room, Rectangle bounds)
	{
		Color color = getDemiBorder(room);
		if (color == null)
			return;
		g.setColor(color);
		g.setStroke(getBorderStroke(getDemiBorderWidth(bounds.width)));
		g.draw(getDemiBorderBounds(bounds));
	}

	private static Stroke getBorderStroke(float width)
	{
		return BORDER_STROKES.computeIfAbsent(width, w -> new BasicStroke(w, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
	}

	private static boolean isPlayerRoom(GauntletRoom room, GauntletRoom playerRoom)
	{
		return playerRoom != null && playerRoom.getRow() == room.getRow() && playerRoom.getColumn() == room.getColumn();
	}

	/** Bear melee, dark beast ranged, dragon magic. */
	static Tile getDemiTile(EnemyType demi)
	{
		switch (demi)
		{
			case BEAR:
				return Tile.MELEE_DEMI;
			case DARK_BEAST:
				return Tile.RANGED_DEMI;
			default:
				return Tile.MAGIC_DEMI;
		}
	}

	private static Tile getResourceTile(List<Requirement> resources)
	{
		boolean fish = resources.contains(Requirement.FISH);
		boolean grym = resources.contains(Requirement.GRYM_LEAVES);
		if (fish && grym)
			return Tile.GRYM_FISH;
		if (fish)
			return Tile.FISH;
		return grym ? Tile.GRYM : Tile.ACTIVE;
	}

	/** The fish and grym leaves marked on a lit room with no plan. */
	static List<Requirement> getShownResources(GauntletRoom room, GauntletHelperConfig config)
	{
		return getShownResources(room, RoomMapGuide.NONE, config);
	}

	/**
	 * The fish and grym leaf rooms marked on a lit room, as Gauntlet Map marks them, while the guide marks them (Hide
	 * completed resource icons); the other resources are not.
	 */
	static List<Requirement> getShownResources(GauntletRoom room, RoomMapGuide guide, GauntletHelperConfig config)
	{
		List<Requirement> shown = new ArrayList<>();
		if (!config.showRoomMapResources())
			return shown;
		for (Requirement resource : guide.getMarkedResources(room))
			if (resource == Requirement.FISH || resource == Requirement.GRYM_LEAVES)
				shown.add(resource);
		return shown;
	}

	/**
	 * The resources a lit room shows as a grid of icons: only when the guide marks bark, linum tirinum or ore there
	 * (with item icons to draw), and then with its marked fish and grym leaves too. Empty when the room keeps a
	 * tile of its own: their fish, grym or plain tile, a demi, home, the player or any unlit room.
	 */
	static List<Requirement> getGridResources(Tile kind, GauntletRoom room, RoomMapGuide guide, ItemIcons icons, GauntletHelperConfig config)
	{
		List<Requirement> marked = new ArrayList<>();
		boolean plainTile = kind == Tile.ACTIVE || kind == Tile.FISH || kind == Tile.GRYM || kind == Tile.GRYM_FISH;
		if (icons == null || !plainTile || !room.isDiscovered() || !config.showRoomMapResources())
			return marked;
		boolean planResource = false;
		for (Requirement resource : guide.getMarkedResources(room))
		{
			marked.add(resource);
			planResource |= RoomMapGuide.isPlanResource(resource);
		}
		if (!planResource)
			marked.clear();
		return marked;
	}

	/**
	 * Where each resource's icon goes on a room, in native tile pixels. One icon fills the middle. Two to four take the
	 * corners, the grym leaf top right and the fish bottom left as on their grym-and-fish tile, the others the corners
	 * left (diagonal first); with five, all are smaller and the fifth takes the middle.
	 */
	static List<Rectangle> getIconSlots(List<Requirement> resources)
	{
		int count = resources.size();
		if (count == 1)
			return Arrays.asList(new Rectangle(ICON_AREA.x + 4, ICON_AREA.y + 4, ICON_AREA.width - 8, ICON_AREA.height - 8));
		int size = count > 4 ? 7 : 10;
		int near = count > 4 ? ICON_AREA.x : ICON_AREA.x + 1;
		int far = ICON_AREA.x + ICON_AREA.width - size - (count > 4 ? 0 : 1);
		int middle = ICON_AREA.x + (ICON_AREA.width - size) / 2;
		// Top right, bottom left, top left, bottom right, middle.
		Rectangle[] places = {
			new Rectangle(far, near, size, size),
			new Rectangle(near, far, size, size),
			new Rectangle(near, near, size, size),
			new Rectangle(far, far, size, size),
			new Rectangle(middle, middle, size, size)
		};
		boolean[] taken = new boolean[places.length];
		Rectangle[] chosen = new Rectangle[count];
		for (int i = 0; i < count; i++)
		{
			int preferred = getPreferredPlace(resources.get(i));
			if (preferred >= 0)
			{
				chosen[i] = places[preferred];
				taken[preferred] = true;
			}
		}
		for (int i = 0; i < count; i++)
		{
			if (chosen[i] != null)
				continue;
			int place = 0;
			while (taken[place])
				place++;
			chosen[i] = places[place];
			taken[place] = true;
		}
		return Arrays.asList(chosen);
	}

	/** The grym leaf top right and the fish bottom left, where their grym-and-fish tile has them; -1 for the others. */
	private static int getPreferredPlace(Requirement resource)
	{
		if (resource == Requirement.GRYM_LEAVES)
			return 0;
		return resource == Requirement.FISH ? 1 : -1;
	}

	/** The icons of a room's resources, each fitted into its place and scaled with the tile. */
	private static void drawIconGrid(Graphics2D g, Rectangle bounds, List<Requirement> resources, ItemIcons icons)
	{
		Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		List<Rectangle> places = getIconSlots(resources);
		double scale = bounds.width / (double) NATIVE_TILE;
		for (int i = 0; i < resources.size(); i++)
		{
			Image icon = getGridIcon(resources.get(i), icons);
			int width = icon == null ? -1 : icon.getWidth(null);
			int height = icon == null ? -1 : icon.getHeight(null);
			if (width <= 0 || height <= 0)
				continue;
			Rectangle place = places.get(i);
			double fit = Math.min(place.width / (double) width, place.height / (double) height) * scale;
			int w = Math.max(1, (int) Math.round(width * fit));
			int h = Math.max(1, (int) Math.round(height * fit));
			int x = bounds.x + (int) Math.round((place.x + place.width / 2.0) * scale - w / 2.0);
			int y = bounds.y + (int) Math.round((place.y + place.height / 2.0) * scale - h / 2.0);
			g.drawImage(icon, x, y, w, h, null);
		}
		if (interpolation != null)
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
	}

	/** Their fish and grym leaf cut from their tiles; the client's item icon for bark, linum tirinum and ore. */
	static Image getGridIcon(Requirement resource, ItemIcons icons)
	{
		if (resource == Requirement.FISH)
			return getCutIcon(Tile.FISH);
		if (resource == Requirement.GRYM_LEAVES)
			return getCutIcon(Tile.GRYM);
		return icons.getIcon(resource);
	}

	/** A resource's icon cut from its own tile at full size. */
	static BufferedImage getCutIcon(Tile resource)
	{
		return cached("cut:" + resource, () -> cut(getTileImage(resource, 3, 3, GauntletMode.CORRUPTED), getIconMask(resource)));
	}

	/** The pixels where a resource tile differs from the plain lit tile: its icon. */
	private static boolean[][] getIconMask(Tile resource)
	{
		BufferedImage marked = getTileImage(resource, 3, 3, GauntletMode.CORRUPTED);
		BufferedImage plain = getTileImage(Tile.ACTIVE, 3, 3, GauntletMode.CORRUPTED);
		boolean[][] mask = new boolean[NATIVE_TILE][NATIVE_TILE];
		for (int x = 0; x < NATIVE_TILE; x++)
			for (int y = 0; y < NATIVE_TILE; y++)
				mask[x][y] = marked.getRGB(x, y) != plain.getRGB(x, y);
		return mask;
	}

	/** The resources drawn small beside the player mark in the player's room, with Show current room resources on. */
	static List<Requirement> getCurrentRoomResources(GauntletRoom room, RoomMapGuide guide, GauntletHelperConfig config)
	{
		if (!config.showCurrentRoomResources())
			return new ArrayList<>();
		return getShownResources(room, guide, config);
	}

	/** The file name ending for a room's place in the grid: edges and corners have no door stubs facing out. */
	static String getPositionSuffix(int row, int column)
	{
		String vertical = "";
		if (row == 0)
			vertical = "top";
		else if (row == GauntletMap.SIZE - 1)
			vertical = "bottom";
		String horizontal = "";
		if (column == 0)
			horizontal = "left";
		else if (column == GauntletMap.SIZE - 1)
			horizontal = "right";
		if (vertical.isEmpty() && horizontal.isEmpty())
			return "";
		if (vertical.isEmpty() || horizontal.isEmpty())
			return "_" + vertical + horizontal;
		return "_" + vertical + "_" + horizontal;
	}

	/** The resource path of a tile, relative to this class. */
	static String getTilePath(Tile kind, int row, int column, GauntletMode mode)
	{
		String set = mode == GauntletMode.REGULAR ? "gauntletmap/regular/" : "gauntletmap/corrupted/";
		String position = getPositionSuffix(row, column);
		switch (kind)
		{
			case INACTIVE:
				return "gauntletmap/inactive" + position + ".png";
			case ACTIVE:
				return "gauntletmap/active" + position + ".png";
			case HUNLLEF:
				return set + "hunllef.png";
			case PLAYER:
				return set + "player" + position + ".png";
			case PLAYER_START:
				return set + "player_start.png";
			case FISH:
				return "gauntletmap/resources/fish" + position + ".png";
			case GRYM:
				return "gauntletmap/resources/grym" + position + ".png";
			case GRYM_FISH:
				// Their top-left tile of this kind is named grym_fish_active_top_left.png.
				if (position.equals("_top_left"))
					return "gauntletmap/resources/grym_fish_active_top_left.png";
				return "gauntletmap/resources/grym_fish" + position + ".png";
			case MELEE_DEMI:
				return "gauntletmap/demiboss/melee" + position + ".png";
			case RANGED_DEMI:
				return "gauntletmap/demiboss/ranged" + position + ".png";
			case MAGIC_DEMI:
				return "gauntletmap/demiboss/magic" + position + ".png";
			default:
				return set + "start_room.png";
		}
	}

	/** A tile at its own 34-pixel size. */
	static BufferedImage getTileImage(Tile kind, int row, int column, GauntletMode mode)
	{
		return loadTile(getTilePath(kind, row, column, mode));
	}

	private static BufferedImage loadTile(String path)
	{
		return cached(path, () -> ImageUtil.loadImageResource(GauntletMapStyle.class, path));
	}

	/**
	 * The cached image for a key, built and stored on first use. It is built outside the map: a builder asks for other
	 * cached tiles, and ConcurrentHashMap.computeIfAbsent throws "Recursive update" when its function writes a key in the
	 * same bin. Two threads may both build a missing tile; the first one stored wins.
	 */
	static BufferedImage cached(String key, Supplier<BufferedImage> build)
	{
		BufferedImage image = tiles.get(key);
		if (image != null)
			return image;
		image = build.get();
		BufferedImage stored = tiles.putIfAbsent(key, image);
		return stored != null ? stored : image;
	}

	/** Swaps the tile cache for the given map and returns the one it replaces. */
	static Map<String, BufferedImage> replaceCache(Map<String, BufferedImage> cache)
	{
		Map<String, BufferedImage> old = tiles;
		tiles = cache;
		return old;
	}

	/** What a room draws at the given size with no plan. */
	static BufferedImage getRoomImage(Tile kind, GauntletRoom room, GauntletMode mode, GauntletHelperConfig config, int size)
	{
		return getRoomImage(kind, room, mode, RoomMapGuide.NONE, config, size);
	}

	/** What a room draws at the given size: its tile, with the player's room's marked resources added when that is on. */
	static BufferedImage getRoomImage(Tile kind, GauntletRoom room, GauntletMode mode, RoomMapGuide guide, GauntletHelperConfig config, int size)
	{
		int row = room.getRow();
		int column = room.getColumn();
		String key = getTilePath(kind, row, column, mode) + ":" + kind;
		boolean player = kind == Tile.PLAYER || kind == Tile.PLAYER_START;
		List<Requirement> resources = player ? getCurrentRoomResources(room, guide, config) : new ArrayList<>();
		boolean fish = resources.contains(Requirement.FISH);
		boolean grym = resources.contains(Requirement.GRYM_LEAVES);
		if (fish || grym)
			key += (fish ? ":fish" : "") + (grym ? ":grym" : "");
		BufferedImage tile;
		if (fish || grym)
			tile = cached("current:" + key, () -> addCurrentRoomIcons(kind, row, column, mode, fish, grym));
		else
			tile = getTileImage(kind, row, column, mode);
		if (size == NATIVE_TILE)
			return tile;
		BufferedImage full = tile;
		return cached("scaled:" + key + "@" + size, () -> scale(full, size));
	}

	/** Scaled as Gauntlet Map scales its tiles. */
	static BufferedImage scale(BufferedImage image, int size)
	{
		Image scaled = image.getScaledInstance(size, size, Image.SCALE_DEFAULT);
		BufferedImage result = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = result.createGraphics();
		try
		{
			g.drawImage(scaled, 0, 0, null);
		}
		finally
		{
			g.dispose();
		}
		return result;
	}

	/**
	 * The player's tile with the room's resources added: the player mark moves left at a smaller size and the grym leaf
	 * and fish take a column on its right, so neither covers the other or the home outline.
	 */
	static BufferedImage addCurrentRoomIcons(Tile kind, int row, int column, GauntletMode mode, boolean fish, boolean grym)
	{
		BufferedImage playerTile = getTileImage(kind, row, column, mode);
		// The home tile has one shape for every position, the center one.
		BufferedImage floor = kind == Tile.PLAYER ? getTileImage(Tile.ACTIVE, row, column, mode) : getTileImage(Tile.ACTIVE, 3, 3, mode);
		boolean[][] mark = getPlayerMarkMask(mode);
		// Copied pixel for pixel, the mark replaced by the plain floor: drawing the tile would round its see-through edges.
		BufferedImage result = new BufferedImage(NATIVE_TILE, NATIVE_TILE, BufferedImage.TYPE_INT_ARGB);
		for (int x = 0; x < NATIVE_TILE; x++)
			for (int y = 0; y < NATIVE_TILE; y++)
				result.setRGB(x, y, mark[x][y] ? floor.getRGB(x, y) : playerTile.getRGB(x, y));
		Graphics2D g = result.createGraphics();
		try
		{
			drawAt(g, getSmallPlayerMark(mode), getCurrentRoomBounds(Tile.PLAYER, mode, fish, grym));
			if (grym)
				drawAt(g, getSmallIcon(Tile.GRYM), getCurrentRoomBounds(Tile.GRYM, mode, fish, grym));
			if (fish)
				drawAt(g, getSmallIcon(Tile.FISH), getCurrentRoomBounds(Tile.FISH, mode, fish, grym));
		}
		finally
		{
			g.dispose();
		}
		return result;
	}

	private static void drawAt(Graphics2D g, BufferedImage image, Rectangle bounds)
	{
		g.drawImage(image, bounds.x, bounds.y, null);
	}

	/**
	 * Where the player mark (PLAYER), the grym leaf (GRYM) or the fish (FISH) goes in the player's room with Current
	 * room resources on. The mark is centered on the left; one icon is centered on the right, two are stacked there.
	 */
	static Rectangle getCurrentRoomBounds(Tile part, GauntletMode mode, boolean fish, boolean grym)
	{
		if (part == Tile.PLAYER)
		{
			BufferedImage mark = getSmallPlayerMark(mode);
			return new Rectangle(CURRENT_ROOM_MARK_LEFT, CURRENT_ROOM_MIDDLE - mark.getHeight() / 2, mark.getWidth(), mark.getHeight());
		}
		BufferedImage icon = getSmallIcon(part);
		int y = CURRENT_ROOM_MIDDLE - icon.getHeight() / 2;
		if (fish && grym)
		{
			int leafHeight = getSmallIcon(Tile.GRYM).getHeight();
			int fishHeight = getSmallIcon(Tile.FISH).getHeight();
			int top = CURRENT_ROOM_MIDDLE - (leafHeight + CURRENT_ROOM_GAP + fishHeight) / 2;
			y = part == Tile.GRYM ? top : top + leafHeight + CURRENT_ROOM_GAP;
		}
		return new Rectangle(CURRENT_ROOM_ICON_LEFT, y, icon.getWidth(), icon.getHeight());
	}

	/**
	 * The player mark's pixels: where the bottom-right player tile differs from the bottom-right lit tile, the one
	 * position whose two tiles share the same floor. The mark sits in the same place on every player tile.
	 */
	static boolean[][] getPlayerMarkMask(GauntletMode mode)
	{
		BufferedImage player = getTileImage(Tile.PLAYER, GauntletMap.SIZE - 1, GauntletMap.SIZE - 1, mode);
		BufferedImage plain = getTileImage(Tile.ACTIVE, GauntletMap.SIZE - 1, GauntletMap.SIZE - 1, mode);
		boolean[][] mask = new boolean[NATIVE_TILE][NATIVE_TILE];
		for (int x = 0; x < NATIVE_TILE; x++)
			for (int y = 0; y < NATIVE_TILE; y++)
				mask[x][y] = player.getRGB(x, y) != plain.getRGB(x, y);
		return mask;
	}

	/** The player mark cut from its tile, at the current room's size. */
	static BufferedImage getSmallPlayerMark(GauntletMode mode)
	{
		return cached("small:mark:" + mode, () ->
		{
			BufferedImage player = getTileImage(Tile.PLAYER, GauntletMap.SIZE - 1, GauntletMap.SIZE - 1, mode);
			return shrink(cut(player, getPlayerMarkMask(mode)), CURRENT_ROOM_MARK_SCALE);
		});
	}

	/** A resource's icon cut from its tile (the pixels that differ from the plain lit tile), at the current room's size. */
	static BufferedImage getSmallIcon(Tile resource)
	{
		return cached("small:" + resource, () -> shrink(getCutIcon(resource), CURRENT_ROOM_ICON_SCALE));
	}

	/** The masked pixels of a tile, cropped to their bounding box, the rest transparent. */
	static BufferedImage cut(BufferedImage tile, boolean[][] mask)
	{
		int left = NATIVE_TILE;
		int top = NATIVE_TILE;
		int right = -1;
		int bottom = -1;
		for (int x = 0; x < NATIVE_TILE; x++)
		{
			for (int y = 0; y < NATIVE_TILE; y++)
			{
				if (!mask[x][y])
					continue;
				left = Math.min(left, x);
				top = Math.min(top, y);
				right = Math.max(right, x);
				bottom = Math.max(bottom, y);
			}
		}
		BufferedImage cut = new BufferedImage(right - left + 1, bottom - top + 1, BufferedImage.TYPE_INT_ARGB);
		for (int x = left; x <= right; x++)
			for (int y = top; y <= bottom; y++)
				if (mask[x][y])
					cut.setRGB(x - left, y - top, tile.getRGB(x, y));
		return cut;
	}

	private static BufferedImage shrink(BufferedImage image, double scale)
	{
		int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
		BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = small.createGraphics();
		try
		{
			g.drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null);
		}
		finally
		{
			g.dispose();
		}
		return small;
	}
}
