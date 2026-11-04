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

import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.GauntletMode;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;

/**
 * Ordinary-enemy kill objectives for TaskPriorities: the enemy the objective
 * picks in this room (engaged, already chosen, else nearest with its exit tail), the side highlight beside a resource
 * task, and the return to a room already seen with an eligible enemy. Each step decides from the TickContext.
 */
final class KillObjective
{
	/** Seconds after the walk home for the upgrade at the bowl and the walk to the barrier. */
	private static final int UPGRADE_RESERVE_SECONDS = 25;

	private final RoomRoutePlanner enemyPlanner = new RoomRoutePlanner();
	private final KillState state = new KillState();

	/** The enemies remembered between ticks. */
	private static final class KillState
	{
		/** The enemy the kill objective chose; it stays the objective in any room until it dies. */
		private NPC chosen;
		/** The enemy last highlighted beside a resource task. */
		private NPC side;

		NPC getChosen() { return chosen; }
		NPC getSide() { return side; }
		void choose(NPC npc) { chosen = npc; }
		void rememberSide(NPC npc) { side = npc; }

		void clear()
		{
			chosen = null;
			side = null;
		}
	}

	void reset()
	{
		state.clear();
	}

	/**
	 * While a needed resource node is the routed task, the enemy the kill objective would choose in this room, else null.
	 * Resources keep the path and instruction; the cue is a teal clickbox with Defeat only. It reads the published task,
	 * so TaskPriorities asks after the steps have run.
	 */
	NPC updateSideEnemy(TickContext context)
	{
		EnemyChoice choice = canShowSideEnemy(context) ? chooseOrdinaryEnemy(context, true) : null;
		state.rememberSide(choice == null ? null : choice.npc);
		return state.getSide();
	}

	/** Only beside a routed resource task, away from home returns and before the loadout is ready. */
	private static boolean canShowSideEnemy(TickContext context)
	{
		return context.isResourceTask() && !context.isInventorySpaceReturn() && !context.isHomeReturn() && !context.plan.isReady()
				&& context.route != null && context.opening.isInitialized();
	}

	/**
	 * Observed ordinary enemies in the current room, after useful resources and home work. The enemy becomes the chosen
	 * one here, so a Decision this returns has to be published.
	 */
	Decision fightOrdinaryEnemy(TickContext context)
	{
		EnemyChoice choice = chooseOrdinaryEnemy(context, false);
		if (choice == null)
			return null;
		// Opening-room gathering remains authoritative even if an enemy is closer.
		if (context.gatherCurrentRoom(context.getHere()) != null)
			return Decision.CLAIM_ONLY;
		state.choose(choice.npc);
		String name = EnemyType.fromNpcId(choice.npc.getId()).getLabel();
		String instruction = "Defeat " + context.mode.getEnemyPrefix() + " " + name + " for " + choice.reason;
		return Decision.fight(choice.npc, choice.path).withGuidancePaused().withInstruction(instruction);
	}

	/**
	 * Missing shards/frames with no eligible enemy here: go back to a room already seen with one, rather than opening
	 * new rooms.
	 */
	Decision goToKnownEnemyRoom(TickContext context)
	{
		return context.urgent ? null : returnToKnownEnemyRoom(context);
	}

	/**
	 * Low time ends the steps, but with armor upgrades left a large enemy already in a fight stays the task, ahead of the
	 * pickups and the way home, when its guaranteed shards pay for the next upgrade and the time left covers the way home,
	 * the bowl and the barrier. A new fight never starts here.
	 */
	Decision finishFightPastWarning(TickContext context)
	{
		WorldView view = context.getView();
		if (!context.plan.isUpgradesDeferred() || view == null || view.npcs() == null || context.route == null || context.isAtHome()
				|| !context.plan.canShardsFundNextUpgrade(EnemyRules.LARGE_KILL_SHARDS) || !hasTimeToSpendShards(context))
			return null;
		for (NPC npc : view.npcs())
		{
			if (npc.isDead() || !EnemyRules.isAvoided(npc.getId()) || !context.isInFightWith(npc))
				continue;
			LocalPathfinder.Goal goal = Routing.buildNpcGoal(npc, view, 0);
			LocalPathfinder.Choice choice = goal == null ? null : context.route.chooseNearest(Collections.singletonList(goal));
			state.choose(npc);
			return Decision.fight(npc, choice == null ? Collections.emptyList() : choice.path).withGuidancePaused()
					.withInstruction("Defeat " + context.mode.getEnemyPrefix() + " " + EnemyType.fromNpcId(npc.getId()).getLabel() + " for shards");
		}
		return null;
	}

	/** The timer is read and leaves the walk home (none with the teleport crystal), the bowl and the barrier after the kill. */
	private static boolean hasTimeToSpendShards(TickContext context)
	{
		int rooms = 0;
		if (context.inv.getHeld(ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM) == 0)
		{
			Point current = context.opening.getRoomAt(context.getHere());
			List<Point> path = context.map.findPath(current, GauntletMap.HOME, context.findReachableRoomSteps(current)::contains);
			if (path.isEmpty())
				return false;
			rooms = path.size() - 1;
		}
		return context.getSecondsLeft() >= rooms * OptimizedTrip.ROUTE_SECONDS_PER_ROOM + UPGRADE_RESERVE_SECONDS;
	}

	/** Walks to the nearest room already seen with an eligible enemy; null when there is none or no route. */
	Decision returnToKnownEnemyRoom(TickContext context)
	{
		KnownEnemyRoom known = findKnownEnemyRoom(context);
		if (known == null || !context.canWalkTo(known.room))
			return null;
		NPC beside = findEnemyBeside(context, known.room);
		if (beside != null)
		{
			state.choose(beside);
			Decision fight = fightOrdinaryEnemy(context);
			if (fight != null)
				return fight;
		}
		return Decision.walkTo(known.room, known.instruction);
	}

	/**
	 * An eligible enemy in that room within a step of the player: one by the doorway is reached from this side of the
	 * room line, where the walk there ends and the room's own kill objective would never start. Null when there is none.
	 */
	private static NPC findEnemyBeside(TickContext context, Point room)
	{
		WorldView view = context.getView();
		if (view == null || view.npcs() == null || context.route == null)
			return null;
		for (NPC npc : view.npcs())
		{
			if (npc.isDead() || !EnemyRules.isEligible(npc.getId(), context.plan))
				continue;
			LocalPathfinder.Goal goal = buildEnemyGoal(context, npc, view, room, 0);
			LocalPathfinder.Choice choice = goal == null ? null : context.route.chooseNearest(Collections.singletonList(goal));
			if (choice != null && choice.path.size() <= 1)
				return npc;
		}
		return null;
	}

	/**
	 * The regular Gauntlet, no staff or frame yet and no known room with an eligible enemy: the ring's guaranteed
	 * weak-monster room is surely among its undiscovered rooms while no ring room has shown a rat, spider or bat, so
	 * the nearest of them is lit for the first frame. It only chooses which room to light: work this update already
	 * chose (a node, a station) stays. Null when that doesn't apply or there is no route.
	 */
	Decision returnToGuaranteedEnemyRoom(TickContext context)
	{
		ObjectKind kind = context.getTaskKind();
		if (kind != null && kind != ObjectKind.DOOR)
			return null;
		Point room = findGuaranteedEnemyRoom(context);
		return room != null && context.canWalkTo(room) ? Decision.walkTo(room, "Light room") : null;
	}

	/** The first room to light (or walk into) toward the nearest undiscovered ring room for returnToGuaranteedEnemyRoom, or null. */
	private static Point findGuaranteedEnemyRoom(TickContext context)
	{
		Requirements plan = context.plan;
		if (context.mode != GauntletMode.REGULAR || !context.opening.isInitialized() || context.opening.isActive()
				|| !plan.needsOpeningStaff() || plan.isStaffInHand() || context.map.hasRingWeakMonsterRoom())
			return null;
		if (!context.shouldHighlightEnemies() || context.isOrdinarySuppressed() || context.isStaffReturn()
				|| context.isAtHome() && context.canCraft() || context.getEnemyReason() == null)
			return null;
		Point current = context.opening.getRoomAt(context.getHere());
		List<Point> path = context.map.findRingPath(current, context.findReachableRoomSteps(current)::contains,
				neighbor -> context.countDoorSteps(context.route, current, neighbor));
		return path.size() < 2 ? null : context.map.findFrontier(path);
	}

	static final class KnownEnemyRoom
	{
		final Point room;
		final String instruction;

		KnownEnemyRoom(Point room, String instruction)
		{
			this.room = room;
			this.instruction = instruction;
		}
	}

	/** The room returnToKnownEnemyRoom heads for, with its instruction; changes no state. Null when there is none. */
	KnownEnemyRoom findKnownEnemyRoom(TickContext context)
	{
		if (!context.shouldHighlightEnemies() || context.isOrdinarySuppressed() || context.isStaffReturn() || context.isAtHome() && context.canCraft())
			return null;
		String reason = context.getEnemyReason();
		if (reason == null)
			return null;
		Point current = context.opening.getRoomAt(context.getHere());
		boolean largeAllowed = EnemyRules.areLargeKillsAllowed(context.plan);
		Point best = null;
		int bestLength = Integer.MAX_VALUE;
		String bestName = null;
		boolean bestVisited = false;
		for (GauntletRoom room : context.map.getRooms())
		{
			Point at = context.map.getRelative(room);
			if (at.equals(current) || !room.isDiscovered() || room.isHunllefRoom() || room.hasLivingDemiBoss())
				continue;
			String name = findEnemyName(room, largeAllowed);
			if (name == null)
				continue;
			int length = measureEnemyRoom(context, current, at, room, largeAllowed);
			if (length < bestLength)
			{
				bestLength = length;
				best = at;
				bestName = name;
				bestVisited = room.isVisited();
			}
		}
		if (best == null)
			return null;
		// A room only seen through its lit door has nothing to return to yet.
		String go = bestVisited ? "Return to a known " : "Go to the ";
		return new KnownEnemyRoom(best, go + context.mode.getEnemyPrefix() + " " + bestName + " for " + reason);
	}

	/** The living enemy a return to this room is for: a large one when large kills are allowed, else a small one. */
	private static String findEnemyName(GauntletRoom room, boolean largeAllowed)
	{
		String name = null;
		if (largeAllowed)
			name = findLivingType(room, EnemyType.UNICORN, EnemyType.SCORPION, EnemyType.WOLF);
		if (name == null)
			name = findLivingType(room, EnemyType.RAT, EnemyType.BAT, EnemyType.SPIDER);
		return name;
	}

	private static String findLivingType(GauntletRoom room, EnemyType... types)
	{
		for (EnemyType type : types)
			if (room.countLivingEnemies(type) > 0)
				return type.getLabel();
		return null;
	}

	/**
	 * Rooms to walk to an enemy room, MAX_VALUE without a route. A large enemy is worth a few extra rooms over a small
	 * one when large kills are allowed.
	 */
	private static int measureEnemyRoom(TickContext context, Point current, Point at, GauntletRoom room, boolean largeAllowed)
	{
		List<Point> route = context.map.findPath(current, at, context.findReachableRoomSteps(current)::contains);
		if (route.isEmpty())
			return Integer.MAX_VALUE;
		boolean largeOnly = largeAllowed && room.countLivingEnemies(EnemyType.RAT)
				+ room.countLivingEnemies(EnemyType.BAT)
				+ room.countLivingEnemies(EnemyType.SPIDER) == 0;
		return route.size() - (largeOnly ? 2 : 0);
	}

	static final class EnemyChoice
	{
		final NPC npc;
		final List<Point> path;
		final String reason;

		EnemyChoice(NPC npc, List<Point> path, String reason)
		{
			this.npc = npc;
			this.path = path;
			this.reason = reason;
		}
	}

	/**
	 * The enemy the kill objective would pick: the engaged or already chosen one, else the nearest in the current room
	 * with its exit tail. Its only side effect is forgetting a chosen enemy that died. For the side
	 * highlight, the remembered side enemy stands in for the chosen one, but only from the spawn room.
	 */
	EnemyChoice chooseOrdinaryEnemy(TickContext context, boolean side)
	{
		if (!context.shouldHighlightEnemies() || context.isOrdinarySuppressed() || context.isStaffReturn())
			return null;
		String reason = context.getEnemyReason();
		if (reason == null || context.route == null)
			return null;
		WorldView view = context.getView();
		if (view == null || view.npcs() == null)
			return null;
		Requirements plan = context.plan;
		// The side kill fought at the door from the spawn room keeps its highlight, even with a home craft available.
		// Otherwise home crafting comes first.
		NPC rememberedSide = side && context.isAtHome() ? findUsable(state.getSide(), view, plan) : null;
		if (context.isAtHome() && context.canCraft() && rememberedSide == null)
			return null;
		Point current = context.opening.getRoomAt(context.getHere());
		// The enemy already being fought stays the objective until it dies.
		NPC engaged = findEngagedEnemy(context, view, current);
		// So does the one the objective already chose, from another room too, so stepping back into the spawn room
		// doesn't drop the rat for the door. The side kill too, from the spawn room only; elsewhere it is picked in
		// the current room.
		if (engaged == null)
			engaged = side ? rememberedSide : findChosenWithoutHomeCraft(context, view, current);
		if (engaged != null)
			current = context.opening.getRoomAt(engaged.getWorldLocation());
		GauntletRoom room = context.map.getRoom(current);
		if (room == null || !room.isDiscovered() || room.isHunllefRoom() || room.hasLivingDemiBoss())
			return null;
		// Large shortfall with finished gear: a unicorn/scorpion/wolf (80+ shards) before rats/bats/spiders (20+).
		boolean preferLarge = engaged == null && EnemyRules.areLargeKillsAllowed(plan) && isLargeEnemyIn(context, view, current);
		List<NPC> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (NPC npc : engaged != null ? Collections.singletonList(engaged) : view.npcs())
		{
			if (!EnemyRules.isEligible(npc.getId(), plan) || npc.isDead() || preferLarge && !EnemyRules.isAvoided(npc.getId()))
				continue;
			LocalPathfinder.Goal goal = buildEnemyGoal(context, npc, view, current, candidates.size());
			if (goal == null)
				continue;
			goals.add(goal);
			candidates.add(npc);
		}
		LocalPathfinder.Choice choice = chooseWithExitTail(context, goals, current);
		return choice == null ? null : new EnemyChoice(candidates.get(choice.index), choice.path, reason);
	}

	/**
	 * The chosen enemy, unless it is in another room, not yet fought, with a home craft ready: home crafts come first, so
	 * the walk to it would drop at the spawn room's line and pick up again a step back out.
	 */
	private NPC findChosenWithoutHomeCraft(TickContext context, WorldView view, Point current)
	{
		NPC chosen = getChosenEnemy(view, context.plan);
		boolean elsewhere = chosen != null && !current.equals(context.opening.getRoomAt(chosen.getWorldLocation()));
		return elsewhere && context.canCraft() && !context.isInFightWith(chosen) ? null : chosen;
	}

	/** A living unicorn, scorpion or wolf in the room. */
	private static boolean isLargeEnemyIn(TickContext context, WorldView view, Point current)
	{
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc.getWorldLocation();
			if (!npc.isDead() && EnemyRules.isAvoided(npc.getId()) && at != null
					&& context.opening.getRoomAt(at.getX(), at.getY()).equals(current))
				return true;
		}
		return false;
	}

	/** The tiles an enemy in the room covers, as a route goal; null when it is elsewhere or not placed. */
	private static LocalPathfinder.Goal buildEnemyGoal(TickContext context, NPC npc, WorldView view, Point current, int index)
	{
		WorldPoint at = npc.getWorldLocation();
		if (at == null || at.getPlane() != view.getPlane() || !context.opening.getRoomAt(at).equals(current))
			return null;
		if (npc.getLocalLocation() == null)
			return null;
		return Routing.buildNpcGoal(npc, view, index);
	}

	/** The enemy whose walk there plus the walk on to the room's exit is shortest. */
	private LocalPathfinder.Choice chooseWithExitTail(TickContext context, List<LocalPathfinder.Goal> goals, Point current)
	{
		List<TileObject> terminalObjects = new ArrayList<>();
		List<LocalPathfinder.Goal> exits = context.findRoomExitGoals(current, terminalObjects);
		LocalPathfinder.Choice choice = null;
		int best = Integer.MAX_VALUE;
		for (LocalPathfinder.Goal goal : goals)
		{
			RoomRoutePlanner.Result leg = enemyPlanner.plan(context.route, Collections.singletonList(goal), exits);
			if (leg != null && leg.tiles < best)
			{
				best = leg.tiles;
				choice = new LocalPathfinder.Choice(goal.index, leg.path);
			}
		}
		return choice;
	}

	/**
	 * The living eligible enemy in this room the player is fighting: the one they are attacking, else one attacking
	 * them. Null when neither applies, so the nearest enemy is chosen.
	 */
	private static NPC findEngagedEnemy(TickContext context, WorldView view, Point current)
	{
		Player player = context.getPlayer();
		Actor attacked = player.getInteracting();
		NPC attacker = null;
		for (NPC npc : view.npcs())
		{
			WorldPoint at = npc.getWorldLocation();
			if (npc.isDead() || !EnemyRules.isEligible(npc.getId(), context.plan) || at == null || !context.opening.getRoomAt(at).equals(current))
				continue;
			if (attacked instanceof NPC && ((NPC) attacked).getIndex() == npc.getIndex())
				return npc;
			if (attacker == null && npc.getInteracting() == player)
				attacker = npc;
		}
		return attacker;
	}

	/** The living enemy the kill objective last chose, in any room of this plane; null clears it. */
	private NPC getChosenEnemy(WorldView view, Requirements plan)
	{
		state.choose(findUsable(state.getChosen(), view, plan));
		return state.getChosen();
	}

	/** The loaded NPC for a remembered enemy, when it is still a living, eligible kill on this plane. */
	private static NPC findUsable(NPC remembered, WorldView view, Requirements plan)
	{
		NPC found = null;
		for (NPC npc : remembered == null ? Collections.<NPC>emptyList() : view.npcs())
			if (npc.getIndex() == remembered.getIndex() && npc.getId() == remembered.getId())
			{
				found = npc;
				break;
			}
		WorldPoint at = found == null ? null : found.getWorldLocation();
		if (at == null || at.getPlane() != view.getPlane() || found.isDead() || !EnemyRules.isEligible(found.getId(), plan))
			found = null;
		return found;
	}
}
