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

package com.gauntlethelper.guidance;

import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;

/**
 * Observes the loaded scene, map varbits and NPCs into Guidance's persistent per-attempt dungeon map. Writes only
 * the map; guidance targets stay in Guidance.
 */
final class MapObserver
{
	private final Guidance guidance;

	MapObserver(Guidance guidance)
	{
		this.guidance = guidance;
	}

	void refreshGlobalMap(WorldView view, Player player, Tile[][] tiles)
	{
		if (!guidance.opening.isInitialized() || !guidance.gauntletMap.isInitialized())
			return;
		int tick = guidance.client.getTickCount();
		for (int row = 0; row < GauntletMap.SIZE; row++)
			for (int col = 0; col < GauntletMap.SIZE; col++)
				if (guidance.client.getVarbitValue(VarbitID.GAUNTLET_ROOM_0_FOUND + row * GauntletMap.SIZE + col) == 1)
					guidance.gauntletMap.discover(guidance.orientation.getWorldRoomOfMapSquare(col, row));
		WorldPoint here = player.getWorldLocation();
		Point current = guidance.opening.getRoomAt(here);
		if (guidance.opening.isRoomInterior(current, here.getX(), here.getY()))
			guidance.gauntletMap.visit(current);
		Set<WorldPoint> observedNodes = new HashSet<>();
		Set<WorldPoint> observedFacilities = new HashSet<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			WorldPoint at = object.getWorldLocation();
			if (at == null || object.getPlane() != view.getPlane())
				continue;
			Point relative = guidance.opening.getRoomAt(at);
			GauntletRoom room = guidance.gauntletMap.getRoom(relative);
			if (kind.isDoorway())
			{
				for (GauntletRoom from : guidance.gauntletMap.getRooms())
					for (GauntletRoom.Direction direction : new GauntletRoom.Direction[]{GauntletRoom.Direction.NORTH, GauntletRoom.Direction.EAST})
					{
						Point fromRoom = guidance.gauntletMap.getRelative(from);
						Point toRoom = new Point(fromRoom.getX() + (direction == GauntletRoom.Direction.EAST ? 1 : 0),
								fromRoom.getY() + (direction == GauntletRoom.Direction.NORTH ? 1 : 0));
						if (guidance.gauntletMap.getRoom(toRoom) == null)
							continue;
						if (SceneGeometry.isNearBoundary(at, guidance.opening.getBoundary(fromRoom, toRoom)))
							guidance.gauntletMap.connect(fromRoom, direction, guidance.isOpenPassage(object, kind) ?
								GauntletRoom.Connection.OPEN : GauntletRoom.Connection.UNLIT);
					}
			}
			else if (room != null && room.isDiscovered())
			{
				if (kind.resource != null)
				{
					observedNodes.add(at);
					guidance.gauntletMap.recordResource(relative, at, kind, guidance.isActive(object, kind), tick);
				}
				else
				{
					observedFacilities.add(at);
					guidance.gauntletMap.recordFacility(relative, at, kind);
				}
			}
		}
		// A scene unload is not exhaustion. Only reconcile absence at a currently loaded, revealed tile.
		for (GauntletRoom room : guidance.gauntletMap.getRooms())
			for (GauntletRoom.ResourceNode node : room.getResources())
				if (node.isActive() && !observedNodes.contains(node.getLocation()) && isLoaded(view, tiles, node.getLocation()))
					guidance.gauntletMap.recordResource(guidance.gauntletMap.getRelative(room), node.getLocation(), node.getKind(), false, tick);
		for (GauntletRoom room : guidance.gauntletMap.getRooms())
			for (WorldPoint at : room.getFacilities().keySet())
				if (!observedFacilities.contains(at) && isLoaded(view, tiles, at))
					room.removeFacility(at);
		guidance.gauntletMap.beginEnemyObservation();
		if (view.npcs() != null)
		{
			for (NPC npc : view.npcs())
				observeNpc(npc);
			for (GauntletRoom room : guidance.gauntletMap.getRooms())
				if (room.isDiscovered() && isRoomFullyLoaded(view, tiles, guidance.gauntletMap.getRelative(room)))
					guidance.gauntletMap.completeObservation(guidance.gauntletMap.getRelative(room), tick);
		}
	}

	private boolean isLoaded(WorldView view, Tile[][] tiles, WorldPoint at)
	{
		LocalPoint local = LocalPoint.fromWorld(view, at);
		if (local == null)
			return false;
		int x = local.getSceneX(), y = local.getSceneY();
		return x >= 0 && y >= 0 && x < tiles.length && y < tiles[x].length && tiles[x][y] != null;
	}

	private boolean isRoomFullyLoaded(WorldView view, Tile[][] tiles, Point relative)
	{
		Point center = guidance.opening.getRoomCenter(relative);
		// Scene coordinates are a fixed offset from world coordinates, so convert the center and index the rest.
		LocalPoint local = LocalPoint.fromWorld(view, new WorldPoint(center.getX(), center.getY(), view.getPlane()));
		return local != null && isRoomLoaded(tiles, local.getSceneX(), local.getSceneY(), guidance.opening.getRoomPitch());
	}

	/**
	 * Whether every scene tile within pitch/2 - 3 of the room center estimate is loaded. The estimate sits a tile or
	 * two off the room's chunk square, so this box stays inside the room's own chunks yet still spans all four.
	 */
	static boolean isRoomLoaded(Tile[][] tiles, int centerX, int centerY, int pitch)
	{
		int radius = pitch / 2 - 3;
		for (int x = centerX - radius; x <= centerX + radius; x++)
			for (int y = centerY - radius; y <= centerY + radius; y++)
				if (x < 0 || y < 0 || x >= tiles.length || y >= tiles[x].length || tiles[x][y] == null)
					return false;
		return true;
	}

	void observeGlobalMapOnly()
	{
		if (!guidance.gauntletMap.isInitialized())
			return;
		WorldView view = guidance.client.getTopLevelWorldView();
		Player player = guidance.client.getLocalPlayer();
		if (view != null && view.getScene() != null && player != null)
			refreshGlobalMap(view, player, view.getScene().getTiles()[view.getPlane()]);
	}

	void observeNpc(NPC npc)
	{
		if (npc == null || !guidance.gauntletMap.isInitialized() || !guidance.opening.isInitialized())
			return;
		WorldPoint at = npc.getWorldLocation();
		if (at == null)
			return;
		WorldView view = guidance.client.getTopLevelWorldView();
		if (view != null && at.getPlane() != view.getPlane())
			return;
		guidance.gauntletMap.observeEnemy(npc, npc.getIndex(), npc.getId(), guidance.opening.getRoomAt(at), at, npc.isDead(), guidance.client.getTickCount());
	}

	/** The map half of Guidance.recordNpcDeath; clearing a target that died stays in Guidance. */
	void recordNpcDeath(NPC npc)
	{
		if (!guidance.gauntletMap.isInitialized())
			return;
		observeNpc(npc);
		guidance.gauntletMap.markEnemyDead(npc, guidance.client.getTickCount());
	}

	/** The map half of Guidance.recordNpcUnloaded: an unload is not a death. */
	void recordNpcUnloaded(NPC npc)
	{
		guidance.gauntletMap.markEnemyUnloaded(npc);
	}

	GauntletRoom getCurrentMapRoom()
	{
		if (!guidance.gauntletMap.isInitialized() || guidance.client == null || guidance.client.getLocalPlayer() == null)
			return null;
		WorldPoint here = guidance.client.getLocalPlayer().getWorldLocation();
		return here == null ? null : guidance.gauntletMap.getRoom(getRoomAt(here));
	}

	/**
	 * The room the player is in. The boundary tile in an unlit room's doorway counts as the lit room it opens from, so
	 * stepping onto it mid-fight doesn't make the hunt sweep from the unlit room.
	 */
	Point getRoomAt(WorldPoint here)
	{
		Point room = guidance.opening.getRoomAt(here);
		GauntletRoom known = guidance.gauntletMap.getRoom(room);
		if (known == null || known.isDiscovered() || guidance.opening.isRoomInterior(room, here.getX(), here.getY()))
			return room;
		for (int[] step : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
		{
			Point next = guidance.opening.getRoomAt(here.getX() + step[0], here.getY() + step[1]);
			GauntletRoom nextRoom = guidance.gauntletMap.getRoom(next);
			if (!next.equals(room) && nextRoom != null && nextRoom.isDiscovered())
				return next;
		}
		return room;
	}
}
