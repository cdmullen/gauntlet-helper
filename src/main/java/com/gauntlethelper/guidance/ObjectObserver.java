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

import com.gauntlethelper.game.GauntletIds;
import com.gauntlethelper.game.ObjectKind;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;

/**
 * Scene observation for Guidance: sorts loaded objects into
 * Guidance.objects, the tool storage and the barriers, rescans the whole scene, and pauses guidance when the
 * target object or enemy goes. The object fields stay on Guidance, where the overlays and the other helpers read
 * them.
 */
final class ObjectObserver
{
	private final Guidance guidance;
	private final GuidanceState targeting;

	ObjectObserver(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	/** Forgets the scanned objects and what was chosen from them (attempt reset and scan). */
	void clear()
	{
		guidance.objects.clear();
		guidance.toolStorage = null;
		guidance.barriers.clear();
		guidance.tour.clear();
		// The scanned objects are new, so the held door and ground target go too; next cues stay until update.
		targeting.clearTask();
		targeting.groundTarget = null;
		targeting.heldDoor = null;
		guidance.attempt.clearAtHome();
		guidance.orientation.clearOpeningOrientationWait();
	}

	void add(TileObject object)
	{
		if (object == null)
		{
			return;
		}
		ObjectKind kind;
		switch (GauntletIds.object(object.getId()))
		{
			case ObjectID.GAUNTLET_ROCK_HM:
				kind = ObjectKind.ORE;
				break;
			case ObjectID.GAUNTLET_TREE_HM:
				kind = ObjectKind.BARK;
				break;
			case ObjectID.GAUNTLET_FIBRE_HM:
				kind = ObjectKind.LINUM_TIRINUM;
				break;
			case ObjectID.GAUNTLET_HERB_HM:
				kind = ObjectKind.GRYM_LEAVES;
				break;
			case ObjectID.GAUNTLET_POND_HM:
				kind = ObjectKind.FISH;
				break;
			case ObjectID.GAUNTLET_SINGING_BOWL_HM:
				kind = ObjectKind.BOWL;
				break;
			case ObjectID.GAUNTLET_RANGE_HM:
				kind = ObjectKind.RANGE;
				break;
			case ObjectID.GAUNTLET_SINK_HM:
				kind = ObjectKind.WATER;
				break;
			case ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_01_HM:
			case ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_02_HM:
				kind = ObjectKind.DOOR;
				break;
			case ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_01_HM:
			case ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_02_HM:
				kind = ObjectKind.PASSAGE;
				break;
			case ObjectID.GAUNTLET_TOOLS_HM:
				// Kept out of the route/map object set: only the sceptre-recovery cue targets it.
				guidance.toolStorage = object;
				return;
			case ObjectID.GAUNTLET_BLOCKADE_ENTER_HM:
			case ObjectID.GAUNTLET_BLOCKADE_HM:
				// Also outside the route set: only the ready-for-Hunllef cue targets it.
				if (!guidance.barriers.contains(object))
					guidance.barriers.add(object);
				return;
			default:
				return;
		}
		guidance.objects.put(object, kind);
	}

	void remove(TileObject object)
	{
		WorldPoint removed = object.getWorldLocation();
		if (guidance.client.getGameState() != GameState.LOADING)
		{
			guidance.gauntletMap.removeObject(removed, guidance.client.getTickCount());
			guidance.harvests.recordGone(object, guidance.objects.get(object));
		}
		guidance.objects.remove(object);
		guidance.barriers.remove(object);
		if (object == guidance.toolStorage)
			guidance.toolStorage = null;
		if (object == targeting.target)
		{
			guidance.pauseGuidance();
		}
	}

	void scan()
	{
		clear();
		WorldView view = guidance.client.getTopLevelWorldView();
		if (view == null || view.getScene() == null)
		{
			return;
		}
		Tile[][] tiles = view.getScene().getTiles()[view.getPlane()];
		for (Tile[] column : tiles)
		{
			for (Tile tile : column)
			{
				if (tile == null)
				{
					continue;
				}
				for (GameObject object : tile.getGameObjects())
				{
					guidance.add(object);
				}
				guidance.add(tile.getWallObject());
				guidance.add(tile.getDecorativeObject());
				guidance.add(tile.getGroundObject());
			}
		}
	}

	void recordNpcDeath(NPC npc)
	{
		if (npc == targeting.sideEnemyTarget)
			targeting.sideEnemyTarget = null;
		if (npc == targeting.enemyTarget)
			guidance.pauseGuidance();
		guidance.mapObserver.recordNpcDeath(npc);
	}

	void recordNpcUnloaded(NPC npc)
	{
		if (npc == targeting.sideEnemyTarget)
			targeting.sideEnemyTarget = null;
		if (npc == targeting.enemyTarget)
			guidance.pauseGuidance();
		guidance.mapObserver.recordNpcUnloaded(npc);
	}
}
