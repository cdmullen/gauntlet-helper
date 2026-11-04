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
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.WeaponType;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Ground loot pickup for TaskPriorities: the nearest needed pile in reach,
 * with the resource node in progress finished first (finishNodeBeforePickup), and which rooms' piles count as a
 * demi's drops.
 */
final class LootPickup
{
	private final PickupHold hold = new PickupHold();

	/** The node a pickup waits for, across updates. */
	private static final class PickupHold
	{
		/** The resource node that was the task last update; a drop seen while it is worked waits for it. */
		private TileObject lastTaskNode;
		/** The node finished before this room's pickups; once it is done, pickups are due until none remain. */
		private TileObject holdNode;
		private boolean pickupsDue;

		void reset()
		{
			lastTaskNode = null;
			clear();
		}

		void rememberTaskNode(TileObject node) { lastTaskNode = node; }

		/** No pile is waiting. */
		void clear()
		{
			holdNode = null;
			pickupsDue = false;
		}

		/** A pile is waiting: the node in progress when it appeared holds it, unless its pickups are already due. */
		TileObject findHoldNode()
		{
			if (holdNode == null && !pickupsDue)
				holdNode = lastTaskNode;
			return holdNode;
		}

		/** The node no longer holds the pickups. */
		void releaseNode()
		{
			holdNode = null;
			pickupsDue = true;
		}
	}

	void reset()
	{
		hold.reset();
	}

	/** After each run of the task steps: the resource node that is now the task, else none. */
	void rememberTaskNode(TileObject target, ObjectKind kind)
	{
		hold.rememberTaskNode(kind != null && kind.resource != null ? target : null);
	}

	/**
	 * The nearest needed ground pile in reach (in the demi room objective only its drops, in the opening only the work
	 * room's). A node in progress is finished first (finishNodeBeforePickup); the next-nearest pile is Next.
	 */
	Decision pickUpLoot(TickContext context)
	{
		Map<GroundLoot.Entry, List<Point>> pickups = findPilesInReach(context);
		GroundLoot.Entry best = chooseNearestPile(pickups);
		if (best == null)
		{
			hold.clear();
			return null;
		}
		Decision finishNode = finishNodeBeforePickup(context);
		if (finishNode != null)
			return finishNode.withNextPickup(describePickup(context, best));
		Decision pickup = Decision.pickup(best.point, pickups.get(best)).withGuidancePaused()
			.withInstruction(describePickup(context, best));
		GroundLoot.Entry following = chooseFollowingPile(pickups, best);
		return following != null && context.inv.getFreeSlots() > 1 ? pickup.withNextPickup(describePickup(context, following)) : pickup;
	}

	/** Needed piles the player may pick up here, each with its walking path, in the loot's order (item id within a tile). */
	private static Map<GroundLoot.Entry, List<Point>> findPilesInReach(TickContext context)
	{
		GroundLoot loot = context.loot;
		WorldView view = context.getView();
		WorldPoint here = context.getHere();
		Map<GroundLoot.Entry, List<Point>> pickups = new LinkedHashMap<>();
		for (GroundLoot.Entry e : loot.getEntries())
		{
			if (context.isInDemiObjective() && !isDemiDropRoom(context.map.getRoom(context.opening.getRoomAt(e.point)), context.getCurrentRoom()))
				continue;
			if (loot.computePickupQuantity(e, context.plan, context.inv, context.retrieveFood()) <= 0 || !context.isLootAllowed(e))
				continue;
			if (context.opening.isActive() && !isToolForCurrentTask(context, e) && !context.opening.getRoomAt(e.point).equals(context.getOpeningWorkRoom()))
				continue;
			if (!loot.isWorthRoomTrip(e) && !context.opening.getRoomAt(e.point).equals(context.opening.getRoomAt(here)))
				continue;
			// Gear crafting comes first at home. A far pile is in reach only while the scene around home holds it, so
			// taking it first would walk out, lose it at a reload and come back.
			if (context.isAtHome() && context.canCraft() && !context.opening.getRoomAt(e.point).equals(GauntletMap.HOME))
				continue;
			LocalPoint local = LocalPoint.fromWorld(view, e.point);
			if (local == null)
				continue;
			LocalPathfinder.Choice choice = context.route.chooseNearest(
				Collections.singletonList(LocalPathfinder.walkTo(0, local.getSceneX(), local.getSceneY())));
			if (choice != null)
				pickups.put(e, choice.path);
		}
		return pickups;
	}

	/** The pile with the shortest path; on a tie an upgrade component, so a component-and-frame pile names it. */
	private static GroundLoot.Entry chooseNearestPile(Map<GroundLoot.Entry, List<Point>> pickups)
	{
		GroundLoot.Entry best = null;
		int bestSize = Integer.MAX_VALUE;
		for (Map.Entry<GroundLoot.Entry, List<Point>> pickup : pickups.entrySet())
		{
			GroundLoot.Entry e = pickup.getKey();
			int size = pickup.getValue().size();
			if (best == null || size < bestSize || size == bestSize && isUpgradeComponent(e.id) && !isUpgradeComponent(best.id))
			{
				best = e;
				bestSize = size;
			}
		}
		return best;
	}

	/** The nearest other pile (not best's item on best's tile), for Next. */
	private static GroundLoot.Entry chooseFollowingPile(Map<GroundLoot.Entry, List<Point>> pickups, GroundLoot.Entry best)
	{
		GroundLoot.Entry following = null;
		for (Map.Entry<GroundLoot.Entry, List<Point>> pickup : pickups.entrySet())
		{
			GroundLoot.Entry e = pickup.getKey();
			if (e.id == best.id && e.point.equals(best.point))
				continue;
			if (following == null || pickup.getValue().size() < pickups.get(following).size())
				following = e;
		}
		return following;
	}

	private static String describePickup(TickContext context, GroundLoot.Entry pile)
	{
		int quantity = context.loot.computeTilePickupQuantity(pile, context.plan, context.inv, context.retrieveFood());
		return "Pick up " + GroundLoot.formatLootCount(pile.id, quantity);
	}

	/**
	 * A drop that appears while a resource node is worked (a side kill while gathering) waits until that node is done:
	 * the node stays Current and the pickup is Next. Only the node in progress when the drop
	 * appeared holds it, and not in a demi room, with one free slot or less or without the node's tool. The node routed again as the task, or
	 * null when nothing holds the pickup. In the opening the room tour stays, so the guidance isn't paused.
	 */
	private Decision finishNodeBeforePickup(TickContext context)
	{
		TileObject node = hold.findHoldNode();
		ObjectKind kind = node == null ? null : context.objects.get(node);
		WorldPoint at = node == null ? null : node.getWorldLocation();
		boolean holds = kind != null && kind.resource != null && at != null && !context.isInDemiObjective()
				&& context.inv.getFreeSlots() > 1 && context.isUseful(kind) && context.isActive(node, kind) && hasToolFor(context, kind)
				&& context.opening.getRoomAt(at).equals(context.getOpeningWorkRoom());
		LocalPathfinder.Choice choice = !holds ? null : context.route.chooseNearest(
			Collections.singletonList(Routing.buildObjectGoal(node, 0, false)));
		if (choice == null)
		{
			hold.releaseNode();
			return null;
		}
		Decision task = Decision.task(node, kind, choice.path);
		return context.opening.isActive() ? task : task.withGuidancePaused().withInstruction(kind.label);
	}

	/** A dropped tool the current task can't be done without, so it is fetched from another room. */
	private static boolean isToolForCurrentTask(TickContext context, GroundLoot.Entry pile)
	{
		ObjectKind task = context.getTaskKind();
		return task != null && task.resource != null && task.resource.getTool() == pile.id;
	}

	/** The node can be worked: its resource takes no tool, or the tool is held. */
	private static boolean hasToolFor(TickContext context, ObjectKind kind)
	{
		int tool = kind.resource.getTool();
		return tool < 0 || context.inv.getHeld(tool) > 0;
	}

	/**
	 * The player's room or one whose demi died: drops of a demi killed through an open doorway land in its own room.
	 */
	static boolean isDemiDropRoom(GauntletRoom room, GauntletRoom currentRoom)
	{
		return room != null && (room == currentRoom || room.getDemiStatus() == GauntletRoom.DemiStatus.DEFEATED);
	}

	static boolean isUpgradeComponent(int id)
	{
		for (WeaponType type : WeaponType.values())
			if (type.getComponent() == id)
				return true;
		return false;
	}
}
