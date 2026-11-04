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
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.Simulation;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/**
 * Harvest tracking for Guidance: the items gathered at each resource
 * node, and from them how many a node still gives (the "×n" labels), how many nodes a room tour plans for and
 * what a room's nodes surely still cover. Cleared on attempt reset only.
 */
final class Harvests
{
	private final Guidance guidance;
	private final GuidanceState targeting;
	/** Items gathered at each resource node, by its location, and each resource's held item count last tick. */
	private final Map<WorldPoint, Integer> harvests = new HashMap<>();
	private final Map<Requirement, Integer> harvestHeld = new HashMap<>();
	/** Nodes that left the scene since the last trackHarvest. */
	private final Map<TileObject, ObjectKind> gone = new IdentityLinkedMap<>();

	Harvests(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	void reset()
	{
		harvests.clear();
		harvestHeld.clear();
		gone.clear();
	}

	/** Keeps a removed node until the next trackHarvest, which may still have its last item to credit. */
	void recordGone(TileObject node, ObjectKind kind)
	{
		if (kind != null && kind.resource != null)
			gone.put(node, kind);
	}

	/**
	 * Whether the needed resource nodes in room surely cover what the plan still lacks of their kinds. A node counts
	 * one item under its nominal yield, less what it already gave, because a spot can run out early. Kinds with no
	 * node in room are left to the forecast.
	 */
	boolean isRoomSurelyCovering(Requirements plan, Point room)
	{
		for (Map.Entry<ObjectKind, Integer> entry : countSurelyIn(plan, room).entrySet())
			if (plan.getRow(entry.getKey().resource).getMissing() > entry.getValue())
				return false;
		return true;
	}

	/** What the needed resource nodes in room surely still give, per kind (see isRoomSurelyCovering). */
	Map<ObjectKind, Integer> countSurelyIn(Requirements plan, Point room)
	{
		Map<ObjectKind, Integer> cautious = new HashMap<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject object = entry.getKey();
			ObjectKind kind = entry.getValue();
			if (kind.resource == null || !guidance.isUseful(kind, plan) || !guidance.isActive(object, kind))
				continue;
			WorldPoint point = object.getWorldLocation();
			if (!guidance.opening.getRoomAt(point).equals(room))
				continue;
			int nominal = kind.getNominalYield();
			int sure = nominal <= 1 ? nominal : Math.max(0, nominal - getHarvested(object) - 1);
			cautious.merge(kind, sure, Integer::sum);
		}
		return cautious;
	}

	/**
	 * Nodes a tour plans for needed items of kind, at the nominal yield each. A node already harvested counts only for
	 * what it may still give, at least one item, so the spot being fished doesn't count for a full four.
	 */
	int countNodesFor(ObjectKind kind, int needed, List<TileObject> candidates)
	{
		int nominal = kind.getNominalYield();
		int used = 0;
		for (TileObject candidate : candidates)
			if (guidance.objects.get(candidate) == kind)
				used = Math.max(used, getHarvested(candidate));
		if (used <= 0)
			return (needed + nominal - 1) / nominal;
		int left = Math.max(1, nominal - used);
		return needed <= left ? 1 : 1 + (needed - left + nominal - 1) / nominal;
	}

	/** Items gathered so far from node (see trackHarvest). */
	private int getHarvested(TileObject node)
	{
		return harvests.getOrDefault(node.getWorldLocation(), 0);
	}

	/**
	 * Credits each item a multi-yield resource gains to the node of that kind the player stands next to (the routed one on
	 * a tie), nothing when next to none. A need that falls without a new item credits nothing.
	 */
	void trackHarvest(Requirements plan)
	{
		Player player = guidance.client == null ? null : guidance.client.getLocalPlayer();
		WorldPoint here = player == null ? null : player.getWorldLocation();
		for (ObjectKind kind : ObjectKind.values())
		{
			if (kind.resource == null || kind.getNominalYield() <= 1)
				continue;
			int held = plan.getInventory().getHeld(kind.resource.getItemId());
			Integer last = harvestHeld.put(kind.resource, held);
			if (last == null || held <= last || here == null)
				continue;
			TileObject node = findNodeBeside(kind, here);
			if (node != null)
				harvests.merge(node.getWorldLocation(), held - last, Integer::sum);
		}
		gone.clear();
	}

	/**
	 * The node of kind whose footprint is next to (or under) here; null when none. A spot leaves the scene on the tick
	 * its last item lands, so a node gone since the last tick comes first, then the routed target.
	 */
	private TileObject findNodeBeside(ObjectKind kind, WorldPoint here)
	{
		for (Map.Entry<TileObject, ObjectKind> entry : gone.entrySet())
			if (entry.getValue() == kind && isBeside(entry.getKey(), here))
				return entry.getKey();
		TileObject found = null;
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			TileObject node = entry.getKey();
			if (entry.getValue() != kind || !isBeside(node, here))
				continue;
			if (node == targeting.target)
				return node;
			if (found == null)
				found = node;
		}
		return found;
	}

	private static boolean isBeside(TileObject node, WorldPoint here)
	{
		WorldPoint at = node.getWorldLocation();
		if (at == null || at.getPlane() != here.getPlane())
			return false;
		Point min = SceneGeometry.getSceneMin(node), max = SceneGeometry.getSceneMax(node);
		int x = here.getX() - at.getX(), y = here.getY() - at.getY();
		return x >= -1 && y >= -1 && x <= max.getX() - min.getX() + 1 && y <= max.getY() - min.getY() + 1;
	}

	/**
	 * Items of kind still to gather from node: what the plan still lacks (fish within the fish allowance), capped by
	 * what the node is forecast to give and what fits in the inventory. 0 for a one-item node (grym leaf) and when
	 * nothing is needed.
	 */
	int getGatherCount(ObjectKind kind, TileObject node)
	{
		return getGatherCount(kind, node, 0);
	}

	/** As getGatherCount, once earlier stops have given before items toward the need. */
	int getGatherCount(ObjectKind kind, TileObject node, int before)
	{
		Requirements plan = guidance.attempt.getNavigationPlan();
		if (plan == null || kind == null || kind.resource == null || kind.getNominalYield() <= 1)
			return 0;
		int needed = plan.getRow(kind.resource).getMissing();
		if (kind.resource == Requirement.FISH)
			needed = Math.min(needed, guidance.attempt.getFishAllowance());
		needed = Math.min(needed - before, countFitting(kind.resource, plan, before));
		int nominal = kind.getNominalYield();
		int used = node == null || node.getWorldLocation() == null ? 0 : getHarvested(node);
		int gives = used <= 0 ? nominal : Math.max(1, nominal - used);
		return Math.max(0, Math.min(needed, gives));
	}

	/** How many items of resource fit in the inventory once before more have gone in; a held stack takes any number. */
	private static int countFitting(Requirement resource, Requirements plan, int before)
	{
		int free = plan.getInventory().getFreeSlots();
		if (!Simulation.isStacked(resource.getItemId()))
			return free - before;
		return free > 0 || plan.getInventory().getHeld(resource.getItemId()) > 0 ? Integer.MAX_VALUE : 0;
	}
}
