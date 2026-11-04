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
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;

/**
 * The sceptre for TaskPriorities: Guidance asks after the task steps and Next, because both cues read the task
 * they published. A Light room task without a sceptre fetches one first (requireSceptre); once no room is left to
 * light, the held sceptre gets a drop cue (chooseCue).
 */
final class Sceptre
{
	private static final String RETURN_FOR_SCEPTRE = "Return to spawn for a sceptre (needed to light rooms)";
	private final SceptreState state = new SceptreState();

	/** What the inventory advice does with a held sceptre. */
	enum Cue
	{
		DROP,
		KEEP
	}

	/** Whether this tick's task is a recovery, and the drop cue's latch between ticks. */
	private static final class SceptreState
	{
		private boolean recovery;
		/** The drop cue, latched until a Light door is the task again (isSceptreDone). */
		private boolean cued;

		boolean isRecovery() { return recovery; }
		void startRecovery() { recovery = true; }
		void clearRecovery() { recovery = false; }
		boolean isCued() { return cued; }
		void latchCue() { cued = true; }
		void clearCue() { cued = false; }
	}

	boolean isSceptreRecovery() { return state.isRecovery(); }

	/** A recovery Decision is published: the overlay draws the task in the recovery blue. */
	void startRecovery()
	{
		state.startRecovery();
	}

	/** No sceptre recovery until a published Decision says so: at each update and before each recovery check. */
	void clearRecovery()
	{
		state.clearRecovery();
	}

	void reset()
	{
		state.clearCue();
	}

	/**
	 * Lighting a room needs a sceptre. When the published task is "Light room" and none is held or worn, pick up a
	 * nearby dropped sceptre, otherwise return to spawn and take one from the tool storage (blue cue). The Light
	 * door stays as the next task. Null when no recovery is needed.
	 */
	Decision requireSceptre(TickContext context)
	{
		TileObject door = context.getTaskTarget();
		if (context.getTaskKind() != ObjectKind.DOOR || door == null || Guidance.hasSceptre(context.inv) || !context.opening.isInitialized())
			return null;
		return chooseRecovery(context).withSceptreRecovery(door);
	}

	private static Decision chooseRecovery(TickContext context)
	{
		Decision pile = findNearbySceptre(context);
		if (pile != null)
			return pile;
		if (context.isAtHome())
			return takeFromToolStorage(context);
		if (context.canWalkTo(GauntletMap.HOME))
			return Decision.walkTo(GauntletMap.HOME, RETURN_FOR_SCEPTRE);
		return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction(RETURN_FOR_SCEPTRE);
	}

	/** The nearest dropped sceptre within the step limit, when there is room for it; null when none. */
	private static Decision findNearbySceptre(TickContext context)
	{
		Pile pile = findNearbySceptre(context.loot, context.inv, context.getView(), context.route);
		if (pile == null)
			return null;
		return Decision.pickup(pile.entry.point, pile.path).withGuidancePaused().withInstruction("Pick up sceptre (needed to light rooms)");
	}

	/** A dropped sceptre within reach and the path to it. */
	static final class Pile
	{
		final GroundLoot.Entry entry;
		final List<Point> path;

		Pile(GroundLoot.Entry entry, List<Point> path)
		{
			this.entry = entry;
			this.path = path;
		}
	}

	/**
	 * The nearest dropped sceptre within Guidance.NEARBY_SCEPTRE_STEPS of the player, when the inventory has a slot for
	 * it; null when there is none, or no loot, view or route to measure by. Routing counts such a sceptre as one a door
	 * can be lit with, so the shorter way home through a door stays while the recovery picks it up.
	 */
	static Pile findNearbySceptre(GroundLoot loot, Inventory inv, WorldView view, LocalPathfinder route)
	{
		if (loot == null || route == null || view == null || inv.getFreeSlots() <= 0)
			return null;
		GroundLoot.Entry best = null;
		List<Point> bestPath = null;
		for (GroundLoot.Entry e : loot.getEntries())
		{
			if (!Guidance.isSceptre(e.id) || e.quantity <= 0)
				continue;
			LocalPoint local = LocalPoint.fromWorld(view, e.point);
			if (local == null)
				continue;
			LocalPathfinder.Choice choice = route.chooseNearest(Collections.singletonList(
					LocalPathfinder.walkTo(0, local.getSceneX(), local.getSceneY())));
			if (choice != null && choice.path.size() <= Guidance.NEARBY_SCEPTRE_STEPS && (bestPath == null || choice.path.size() < bestPath.size()))
			{
				best = e;
				bestPath = choice.path;
			}
		}
		return best == null ? null : new Pile(best, bestPath);
	}

	private static Decision takeFromToolStorage(TickContext context)
	{
		String instruction = "Take sceptre from tool storage";
		TileObject storage = context.getToolStorage();
		if (storage == null)
			return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction(instruction);
		List<Point> path = Collections.emptyList();
		if (context.route != null)
		{
			LocalPathfinder.Choice choice = context.route.chooseNearest(Collections.singletonList(Routing.buildObjectGoal(storage, 0, false)));
			if (choice != null)
				path = choice.path;
		}
		return Decision.task(storage, ObjectKind.TOOLS, path).withGuidancePaused().withInstruction(instruction);
	}

	/** The cue for a held sceptre; null when none is held or it needs neither. Asking updates the drop cue's latch. */
	Cue chooseCue(TickContext context)
	{
		if (context.inv.getHeld(ItemID.GAUNTLET_SCEPTRE_HM) + context.inv.getHeld(ItemID.GAUNTLET_SCEPTRE) <= 0)
			return null;
		if (isSceptreDone(context))
			return Cue.DROP;
		return isSceptreNeeded(context) ? Cue.KEEP : null;
	}

	/**
	 * No room is left to light: the opening and demi hunt are done, shards and frames are held, no Light door is the
	 * current or next task, and each gathered resource still short is surely covered by nodes in rooms reached through
	 * lit doors, counting each node one under its nominal yield as in isRoomSurelyCovering. The held sceptre then gets
	 * a drop cue, before final preparation. Once shown, the cue stays until the sceptre is needed again (isSceptreNeeded).
	 */
	private boolean isSceptreDone(TickContext context)
	{
		Requirements plan = context.plan;
		if (!context.opening.isInitialized() || context.opening.isActive() || !plan.getRow(Requirement.UPGRADES).isComplete()
				|| !plan.getRow(Requirement.SHARDS).isComplete() || !plan.getRow(Requirement.FRAMES).isComplete() || isSceptreNeeded(context))
		{
			state.clearCue();
			return false;
		}
		if (!state.isCued() && hasNoRoomToLight(context))
			state.latchCue();
		return state.isCued();
	}

	/**
	 * A Light door is the current or next task, or the shortest way home from this room lights one
	 * (Routing.hasHomeShortcut). Dropping the sceptre here would send the player the long way round.
	 */
	private static boolean isSceptreNeeded(TickContext context)
	{
		if (context.getTaskKind() == ObjectKind.DOOR || context.getNextKind() == ObjectKind.DOOR)
			return true;
		Player player = context.getPlayer();
		return player != null && context.opening.isInitialized() && context.hasHomeShortcut(context.opening.getRoomAt(player.getWorldLocation()));
	}

	private static boolean hasNoRoomToLight(TickContext context)
	{
		Requirements plan = context.plan;
		if (!plan.needsExploration())
			return true;
		Player player = context.getPlayer();
		if (player == null)
			return false;
		Point current = context.opening.getRoomAt(player.getWorldLocation());
		GauntletMap map = context.map;
		Map<ObjectKind, Integer> sure = context.countSurelyIn(current);
		for (GauntletRoom other : map.getRooms())
		{
			Point at = map.getRelative(other);
			if (at.equals(current) || !map.hasOpenRoute(current, at))
				continue;
			for (GauntletRoom.ResourceNode node : other.getResources())
			{
				int nominal = node.getKind().getNominalYield();
				if (node.isActive() && node.getKind().resource != null)
					sure.merge(node.getKind(), nominal <= 1 ? nominal : nominal - 1, Integer::sum);
			}
		}
		for (ObjectKind kind : new ObjectKind[]{ObjectKind.ORE, ObjectKind.BARK, ObjectKind.LINUM_TIRINUM, ObjectKind.GRYM_LEAVES, ObjectKind.FISH})
			if (plan.getRow(kind.resource).getMissing() > sure.getOrDefault(kind, 0))
				return false;
		return true;
	}
}
