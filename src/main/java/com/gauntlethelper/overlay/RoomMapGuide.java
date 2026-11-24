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
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Point;

/**
 * What the plan adds to the room map: which gathered resources are still short, and the rooms of the current and Next
 * tasks. Everything it draws stays inside the observed-only rule: the
 * route runs only over lit rooms and the doors seen from them, and an unlit room is outlined only where the fixed
 * layout or a door seen from a lit room already shows it.
 */
final class RoomMapGuide
{
	/** No plan: every seen resource is marked and there is no route. */
	static final RoomMapGuide NONE = new RoomMapGuide(false, EnumSet.copyOf(Arrays.asList(Requirement.GATHERED)), null, null);
	/**
	 * How far from the first room's center a route through more rooms starts, toward the door, as a share of half a room.
	 * A one-step route starts at the center, so its short line still shows before the arrowhead.
	 */
	static final double ROUTE_START_INSET = 0.6;
	/** How far an arrowhead's tip reaches into the task room, as a share of the way from its edge to its center. */
	static final double TIP_DEPTH = 1.0 / 3;
	/**
	 * Pixels an arrowhead's tip stops short of an unlit room's own edge, the head casing's reach plus antialiasing; never
	 * past the door, halfway between the rooms.
	 */
	static final double UNLIT_END_GAP = 3;
	/** A one-step route's arrowhead is at most this share of the step, so some line always shows before it. */
	static final double ONE_STEP_HEAD_SHARE = 0.5;
	/** The current route's arrowhead length as a share of the Map tile size; the Next route's is NEXT_HEAD_SCALE of it. */
	static final double HEAD_LENGTH_SHARE = 0.32;
	static final double NEXT_HEAD_SCALE = 0.75;
	/** An arrowhead's half width as a share of its length. */
	static final double HEAD_HALF_WIDTH = 0.6;
	/** The Current room highlight's fill: this share of the current action color's own alpha. */
	static final double CURRENT_ROOM_FILL_ALPHA = 0.32;
	/** Drawn under each line and outline, so a color close to the tiles' own (the Next color on tan) still reads. */
	static final Color CASING = new Color(0, 0, 0, 170);
	/** An outline's line runs this far inside the room, so it and its casing stay within the room's own square. */
	static final int OUTLINE_INSET = 2;
	private static final Stroke THIN_CASING = new BasicStroke(3);
	static final Stroke MEDIUM_CASING = new BasicStroke(4);
	// Round joins, so the casing doesn't spike past an arrowhead's sharp tip.
	private static final Stroke THIN_HEAD_CASING = new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	static final Stroke MEDIUM_HEAD_CASING = new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	/** The last current action color filled and its translucent fill, so a frame builds no new Color. */
	private static Color fillBase;
	private static Color fill;

	private final boolean hideCompleted;
	private final Set<Requirement> shortResources;
	private final GauntletRoom destination;
	private final GauntletRoom next;

	/** With hideCompleted, a resource the plan has enough of loses its icon; without it every resource seen keeps one. */
	RoomMapGuide(boolean hideCompleted, Set<Requirement> shortResources, GauntletRoom destination, GauntletRoom next)
	{
		this.hideCompleted = hideCompleted;
		this.shortResources = shortResources;
		this.destination = destination;
		this.next = next;
	}

	/**
	 * The guide for a plan, with Hide completed resource icons as given; with no plan nothing is known to be complete,
	 * so every resource seen is marked.
	 */
	static RoomMapGuide of(Requirements plan, boolean hideCompleted, GauntletRoom destination, GauntletRoom next)
	{
		if (plan == null)
			return new RoomMapGuide(false, NONE.shortResources, destination, next);
		Set<Requirement> missing = EnumSet.noneOf(Requirement.class);
		for (Requirement resource : Requirement.GATHERED)
			if (plan.getRow(resource) != null && !plan.getRow(resource).isComplete())
				missing.add(resource);
		return new RoomMapGuide(hideCompleted, missing, destination, next);
	}

	boolean isShort(Requirement resource) { return shortResources.contains(resource); }
	GauntletRoom getDestination() { return destination; }
	GauntletRoom getNext() { return next; }

	/** Bark, linum tirinum and ore: the resources the Classic style shows as item icons rather than tiles of their own. */
	static boolean isPlanResource(Requirement resource)
	{
		return resource == Requirement.ORE || resource == Requirement.BARK || resource == Requirement.LINUM_TIRINUM;
	}

	/** The resources marked on a lit room: every one seen there, or with hideCompleted only those still short. */
	List<Requirement> getMarkedResources(GauntletRoom room)
	{
		List<Requirement> marked = new ArrayList<>();
		for (Requirement resource : RoomMapOverlay.getShownResources(room))
			if (!hideCompleted || isShort(resource))
				marked.add(resource);
		return marked;
	}

	/** Whether the plan's parts are drawn at all: never with the player in Hunllef's room. */
	static boolean isRouteShownWith(GauntletRoom playerRoom)
	{
		return playerRoom == null || !playerRoom.isHunllefRoom();
	}

	/**
	 * Whether a task's room may be outlined: a lit room, a guaranteed demi room (fixed public layout), or an unlit room
	 * across a door seen from a lit neighbor. Never Hunllef's room, and never any other unlit room, since that would say
	 * where the plan expects to find something.
	 */
	static boolean canOutline(GauntletMap map, GauntletRoom room)
	{
		if (room == null || room.isHunllefRoom())
			return false;
		if (room.isDiscovered() || room.isGuaranteedDemiSpawn())
			return true;
		for (GauntletRoom.Direction direction : GauntletRoom.Direction.values())
		{
			// The neighbor on the opposite side of this room, whose door in this direction leads here.
			GauntletRoom neighbor = map.getRoom(room.getRow() + direction.getDy(), room.getColumn() - direction.getDx());
			if (neighbor != null && neighbor.isDiscovered() && isSeenDoor(neighbor.getConnection(direction)))
				return true;
		}
		return false;
	}

	/** A door the player has seen from a lit room: lit, or a dark doorway. */
	private static boolean isSeenDoor(GauntletRoom.Connection connection)
	{
		return connection == GauntletRoom.Connection.OPEN || connection == GauntletRoom.Connection.UNLIT;
	}

	/**
	 * The rooms of the planned route from one room to another that the map may show. It starts in a lit room and runs
	 * through lit rooms joined by lit doors; it ends at the first room it can't show: one hop into an unlit room through
	 * a door seen from the lit side, or nothing past a door never seen. Empty when there is nothing to draw.
	 */
	static List<GauntletRoom> findShownRoute(GauntletMap map, GauntletRoom from, GauntletRoom to)
	{
		if (from == null || to == null || isSameRoom(from, to) || from.isHunllefRoom() || to.isHunllefRoom())
			return Collections.emptyList();
		return clipRoute(map, map.findPath(map.getRelative(from), map.getRelative(to)));
	}

	/** A room path cut to the part the map may show (findShownRoute). */
	static List<GauntletRoom> clipRoute(GauntletMap map, List<Point> path)
	{
		List<GauntletRoom> rooms = new ArrayList<>();
		if (path.size() < 2)
			return rooms;
		GauntletRoom first = map.getRoom(path.get(0));
		if (first == null || !first.isDiscovered() || first.isHunllefRoom())
			return rooms;
		rooms.add(first);
		for (int i = 1; i < path.size(); i++)
		{
			GauntletRoom previous = rooms.get(rooms.size() - 1);
			GauntletRoom room = map.getRoom(path.get(i));
			GauntletRoom.Direction direction = getDirection(previous, room);
			if (room == null || room.isHunllefRoom() || direction == null)
				break;
			GauntletRoom.Connection connection = previous.getConnection(direction);
			if (room.isDiscovered() && connection == GauntletRoom.Connection.OPEN)
			{
				rooms.add(room);
				continue;
			}
			if (!room.isDiscovered() && isSeenDoor(connection))
				rooms.add(room);
			break;
		}
		if (rooms.size() < 2)
			rooms.clear();
		return rooms;
	}

	/** The side of one room the next room lies on, or null when they aren't neighbors. */
	static GauntletRoom.Direction getDirection(GauntletRoom from, GauntletRoom to)
	{
		if (from == null || to == null)
			return null;
		for (GauntletRoom.Direction direction : GauntletRoom.Direction.values())
			if (to.getRow() == from.getRow() - direction.getDy() && to.getColumn() == from.getColumn() + direction.getDx())
				return direction;
		return null;
	}

	static boolean isSameRoom(GauntletRoom room, GauntletRoom other)
	{
		return room != null && other != null && room.getRow() == other.getRow() && room.getColumn() == other.getColumn();
	}

	/** Where a room is drawn on the map. */
	interface RoomBounds
	{
		Rectangle getBounds(GauntletRoom room);
	}

	/**
	 * Draws the plan's outlines and routes: the Next task's room and the current task's room outlined in their colors,
	 * then the Next route and the current route from the player's room, each ending in an arrowhead. Nothing with the
	 * player in Hunllef's room.
	 */
	void draw(Graphics2D g, GauntletMap map, GauntletRoom playerRoom, GauntletHelperConfig config, RoomBounds bounds)
	{
		if (!isRouteShownWith(playerRoom))
			return;
		double head = getHeadLength(RoomMapOverlay.getTileSize(config));
		RouteLine nextLine = buildRouteLine(findShownRoute(map, destination, next), bounds, head * NEXT_HEAD_SCALE);
		RouteLine currentLine = buildRouteLine(findShownRoute(map, playerRoom, destination), bounds, head);
		boolean nextOutlined = isOutlined(map, next, playerRoom) && !isSameRoom(next, destination);
		boolean currentOutlined = isOutlined(map, destination, playerRoom);
		if (nextOutlined)
			drawOutline(g, bounds.getBounds(next), config.nextActionColor(), OverlayStrokes.THIN, THIN_CASING);
		if (currentOutlined)
			drawOutline(g, bounds.getBounds(destination), config.currentActionColor(), OverlayStrokes.MEDIUM, MEDIUM_CASING);
		// The routes go over the outlines, so an arrowhead crossing a room's edge stays whole.
		drawRoute(g, nextLine, config.nextActionColor(), OverlayStrokes.THIN, THIN_CASING, THIN_HEAD_CASING);
		drawRoute(g, currentLine, config.currentActionColor(), OverlayStrokes.MEDIUM, MEDIUM_CASING, MEDIUM_HEAD_CASING);
	}

	/**
	 * Current room highlight: the player's whole room filled with a translucent current action color, light enough that
	 * its tile and icons still read, with no outline, since outlines mark the task rooms. Drawn before the route so the
	 * route's line runs over it. The room is the shown one, never Hunllef's.
	 */
	static void drawCurrentRoom(Graphics2D g, GauntletRoom playerRoom, GauntletHelperConfig config, RoomBounds bounds)
	{
		if (playerRoom == null || playerRoom.isHunllefRoom() || !config.highlightCurrentRoom())
			return;
		Rectangle room = bounds.getBounds(playerRoom);
		g.setColor(getCurrentRoomFill(config.currentActionColor()));
		g.fillRect(room.x, room.y, room.width, room.height);
	}

	/** The current action color at CURRENT_ROOM_FILL_ALPHA of its own alpha, kept until the color changes. */
	static Color getCurrentRoomFill(Color color)
	{
		if (!color.equals(fillBase))
		{
			int alpha = (int) Math.round(color.getAlpha() * CURRENT_ROOM_FILL_ALPHA);
			fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
			fillBase = color;
		}
		return fill;
	}

	/** The current route's arrowhead length for a Map tile size. */
	static double getHeadLength(int tile)
	{
		return Math.max(4, tile * HEAD_LENGTH_SHARE);
	}

	/** Whether a task's room gets an outline: one the map may outline, other than the player's own room. */
	static boolean isOutlined(GauntletMap map, GauntletRoom room, GauntletRoom playerRoom)
	{
		return isRouteShownWith(playerRoom) && !isSameRoom(room, playerRoom) && canOutline(map, room);
	}

	private static void drawOutline(Graphics2D g, Rectangle bounds, Color color, Stroke stroke, Stroke casing)
	{
		g.setColor(CASING);
		g.setStroke(casing);
		g.drawRect(bounds.x + OUTLINE_INSET, bounds.y + OUTLINE_INSET, bounds.width - 2 * OUTLINE_INSET, bounds.height - 2 * OUTLINE_INSET);
		g.setColor(color);
		g.setStroke(stroke);
		g.drawRect(bounds.x + OUTLINE_INSET, bounds.y + OUTLINE_INSET, bounds.width - 2 * OUTLINE_INSET, bounds.height - 2 * OUTLINE_INSET);
	}

	private static Point2D getCenter(RoomBounds bounds, GauntletRoom room)
	{
		Rectangle rectangle = bounds.getBounds(room);
		return new Point2D.Double(rectangle.getCenterX(), rectangle.getCenterY());
	}

	/** A route's line and the arrowhead at its end: the line stops at the head's base, the tip points along the last step. */
	static final class RouteLine
	{
		final Path2D line;
		final Path2D head;
		final Point2D tip;
		final Point2D base;

		RouteLine(Path2D line, Path2D head, Point2D tip, Point2D base)
		{
			this.line = line;
			this.head = head;
			this.tip = tip;
			this.base = base;
		}
	}

	/**
	 * The route's line and arrowhead: from the first room's center (just past it toward the door when the route passes
	 * through more rooms), through each room's center, to the head. Its tip is TIP_DEPTH of the way from the last room's
	 * edge to its center, or at the door but short of the room when the last room is unlit. Null for an empty route.
	 */
	static RouteLine buildRouteLine(List<GauntletRoom> route, RoomBounds bounds, double headLength)
	{
		if (route.size() < 2)
			return null;
		Path2D line = new Path2D.Double();
		boolean oneStep = route.size() == 2;
		double inset = oneStep ? 0 : ROUTE_START_INSET / 2;
		Point2D start = towards(getCenter(bounds, route.get(0)), getCenter(bounds, route.get(1)), inset);
		line.moveTo(start.getX(), start.getY());
		Point2D last = start;
		for (int i = 1; i < route.size() - 1; i++)
		{
			last = getCenter(bounds, route.get(i));
			line.lineTo(last.getX(), last.getY());
		}
		GauntletRoom end = route.get(route.size() - 1);
		Point2D tip = findTip(getCenter(bounds, route.get(route.size() - 2)), end, bounds);
		double reach = last.distance(tip);
		if (reach <= 0)
			return new RouteLine(line, null, tip, tip);
		// A head never runs back past the line's start; on a one-step route it leaves at least half the step to the line.
		double length = Math.min(headLength, oneStep ? reach * ONE_STEP_HEAD_SHARE : reach);
		double ux = (tip.getX() - last.getX()) / reach;
		double uy = (tip.getY() - last.getY()) / reach;
		Point2D base = new Point2D.Double(tip.getX() - ux * length, tip.getY() - uy * length);
		if (length < reach)
			line.lineTo(base.getX(), base.getY());
		double half = length * HEAD_HALF_WIDTH;
		Path2D head = new Path2D.Double();
		head.moveTo(tip.getX(), tip.getY());
		head.lineTo(base.getX() - uy * half, base.getY() + ux * half);
		head.lineTo(base.getX() + uy * half, base.getY() - ux * half);
		head.closePath();
		return new RouteLine(line, head, tip, base);
	}

	/**
	 * Where the arrowhead's tip goes, coming from the center of the room before: TIP_DEPTH of the way from the end room's
	 * edge to its center. When the end room is unlit: at the door, halfway between the two rooms, but at least
	 * UNLIT_END_GAP short of the unlit room's own edge, so where rooms touch it stops short of the door.
	 */
	static Point2D findTip(Point2D before, GauntletRoom end, RoomBounds bounds)
	{
		Rectangle room = bounds.getBounds(end);
		Point2D center = new Point2D.Double(room.getCenterX(), room.getCenterY());
		double distance = before.distance(center);
		if (distance <= 0)
			return center;
		double ux = (center.getX() - before.getX()) / distance;
		double uy = (center.getY() - before.getY()) / distance;
		// Neighbors share a row or a column, so the half extent along the step is half the room's width or height.
		double half = Math.abs(ux) >= Math.abs(uy) ? room.getWidth() / 2 : room.getHeight() / 2;
		if (!end.isDiscovered())
		{
			// Short of the unlit room's edge by the casing's reach, so no pixel of the head lands in it.
			double toEdge = distance - half - UNLIT_END_GAP;
			double toTip = Math.max(0, Math.min(distance / 2, toEdge));
			return new Point2D.Double(before.getX() + ux * toTip, before.getY() + uy * toTip);
		}
		double fromCenter = half * (1 - TIP_DEPTH);
		return new Point2D.Double(center.getX() - ux * fromCenter, center.getY() - uy * fromCenter);
	}

	/** The point a share of the way from one point to another. */
	private static Point2D towards(Point2D from, Point2D to, double share)
	{
		return new Point2D.Double(from.getX() + (to.getX() - from.getX()) * share, from.getY() + (to.getY() - from.getY()) * share);
	}

	/** Draws a route, if any: both casings first, then the line in its color and stroke and the filled arrowhead. */
	static void drawRoute(Graphics2D g, RouteLine route, Color color, Stroke stroke, Stroke casing, Stroke headCasing)
	{
		if (route == null)
			return;
		g.setColor(CASING);
		g.setStroke(casing);
		g.draw(route.line);
		if (route.head != null)
		{
			g.setStroke(headCasing);
			g.draw(route.head);
		}
		g.setColor(color);
		g.setStroke(stroke);
		g.draw(route.line);
		if (route.head != null)
			g.fill(route.head);
	}
}
