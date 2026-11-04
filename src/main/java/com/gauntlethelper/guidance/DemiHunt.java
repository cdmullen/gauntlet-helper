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
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.map.GauntletMap;
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.DemiBossTracker;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.Simulation;
import com.gauntlethelper.plan.TripPlanner;
import com.gauntlethelper.plan.WeaponType;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * The demi hunt for TaskPriorities: whether forecast demi shards suppress
 * kill cues, the stay for a demi room's drops, the hunt itself (this room's resources, a useful demi here, kills, then
 * the perimeter search) and the after-kill forecast that shows Next during a fight and its drop pickup. Each step decides
 * from the TickContext; the forecast runs at decision time, and TaskPriorities.publish writes its result after the pause.
 */
final class DemiHunt
{
	/** Walking tiles to a demi across the room line that make it the fight: a demi stops about two tiles from the player. */
	private static final int DEMI_BESIDE_TILES = 2;

	// A second cache so the after-kill forecast never evicts the live tour's.
	private final TripPlanner afterKillPlanner = new TripPlanner();
	private final HuntState state = new HuntState();
	private final KillObjective kills;

	DemiHunt(KillObjective kills)
	{
		this.kills = kills;
	}

	/** The forecast's coverage for this update and the fight's after-kill forecast, kept for its drop pickup. */
	private static final class HuntState
	{
		/** Forecast demi shards cover the shortfall, so ordinary kill cues are suppressed; each update decides afresh. */
		private boolean ordinarySuppressed;
		/** The fight the forecast is for; null when none is remembered. */
		private GauntletRoom fightRoom;
		/** The fight's after-kill move (the forecast needs the living demi); null when it could not be told. */
		private AfterKill move;
		/** The components the fight's demi could drop and the upgrades held then, to read a repeat kill's actual drop. */
		private Set<WeaponType> drops = Collections.emptySet();
		private Set<WeaponType> upgrades = Collections.emptySet();
		/** Demi kills the forecast counted (the fight's included); a later kill makes its move stale. */
		private int kills;
		/** The demi fought in its own room, whose objective holds while the fight crosses a doorway; null when none. */
		private NPC heldFight;

		boolean isOrdinarySuppressed() { return ordinarySuppressed; }
		void clearSuppression() { ordinarySuppressed = false; }
		void suppressOrdinaryEnemies(boolean suppressed) { ordinarySuppressed = suppressed; }

		void beginFight(GauntletRoom room, Set<WeaponType> possibleDrops, Set<WeaponType> heldUpgrades, int killsCounted)
		{
			fightRoom = room;
			move = null;
			drops = possibleDrops;
			upgrades = heldUpgrades;
			kills = killsCounted;
		}

		void agreeMove(AfterKill agreed) { move = agreed; }

		void holdFight(NPC npc) { heldFight = npc; }

		void forgetFight()
		{
			fightRoom = null;
			move = null;
		}

		GauntletRoom getFightRoom() { return fightRoom; }
		AfterKill getMove() { return move; }
		Set<WeaponType> getDrops() { return drops; }
		Set<WeaponType> getUpgrades() { return upgrades; }
		int getKills() { return kills; }
		NPC getHeldFight() { return heldFight; }
	}

	/** Attempt reset. The kill suppression stays until the next update replaces it. */
	void reset()
	{
		afterKillPlanner.reset();
		state.forgetFight();
		state.holdFight(null);
	}

	/** Start of each update: the suppression is decided again if forecastDemiCoverage is reached. */
	void beginUpdate()
	{
		state.clearSuppression();
	}

	boolean isOrdinarySuppressed() { return state.isOrdinarySuppressed(); }

	/** Always runs, never claims: whether forecast demi shards suppress ordinary kill cues. */
	void forecastDemiCoverage(TickContext context)
	{
		state.suppressOrdinaryEnemies(EnemyRules.isCoveredByDemis(context.plan, context.plan.hasStaff()
				&& !context.opening.isActive() && !context.isStaffReturn()));
	}

	/** A demi room whose drops haven't appeared yet holds the player there, unless time is low. */
	Decision collectDemiDrops(TickContext context)
	{
		if (!context.isAwaitingDemiDrops() || context.urgent)
			return null;
		return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction("Collect demi-boss drops").withAfterKill(holdAfterKill(context));
	}

	/** Always runs, never claims: past the drop pickups the route decides live, and a later fight forecasts afresh. */
	void forgetAfterKill()
	{
		state.forgetFight();
	}

	/** While an upgrade is missing: a useful demi here, this room's resources, kills, then the perimeter search. */
	Decision huntDemis(TickContext context)
	{
		Requirements plan = context.plan;
		if (plan.getRow(Requirement.UPGRADES).isComplete())
			return null;
		// Home crafts come first; the hunt holds travel until they are done.
		if (context.isAtHome() && context.canCraft())
			return Decision.CLAIM_ONLY;
		WorldPoint here = context.getHere();
		Point current = context.getPlayerMapRoom();
		GauntletRoom room = context.map.getRoom(current);
		DemiBossPlan needed = new DemiBossPlan(plan, context.demiHistory);
		// The room's nodes wait until its targeted demi is dead.
		if (needed.isUseful(context.map, room))
		{
			state.holdFight(findFightHere(context, current));
			return fightDemiHere(context, needed, room, current);
		}
		Point fightRoom = findHeldFightRoom(context, needed);
		if (fightRoom != null)
			return fightDemiHere(context, needed, context.map.getRoom(fightRoom), fightRoom);
		state.holdFight(null);
		Point besideRoom = findDemiRoomBeside(context, needed, current);
		if (besideRoom != null)
			return fightDemiHere(context, needed, context.map.getRoom(besideRoom), besideRoom);
		// Chosen before this room's work, so the map shows where the sweep heads while the player gathers here.
		GauntletMap.SearchTarget sweep = chooseSweepTarget(context, current);
		Decision gather = gatherThisRoom(context, here);
		if (gather != null)
			return gather;
		// Until the outer room is checked, the post-loop search leads; this step only adds a staff-frame kill.
		if (!context.isOuterRoomChecked())
		{
			Decision frameKill = plan.hasStaff() ? null : fightNearbyEnemy(context);
			return frameKill != null ? frameKill : Decision.CLAIM_ONLY;
		}
		Decision kill = fightNearbyEnemy(context);
		return kill != null ? kill : followSweep(context, sweep);
	}

	/**
	 * The neighboring room of a needed demi within DEMI_BESIDE_TILES of the player through an open doorway; null when there is none.
	 * A doorway's tiles count as one room, so the walk to the demi's room can end beside it on this side of the room line,
	 * where its fight would never start.
	 */
	private static Point findDemiRoomBeside(TickContext context, DemiBossPlan needed, Point current)
	{
		GauntletRoom here = context.map.getRoom(current);
		WorldView view = context.getView();
		if (here == null || view == null || context.route == null)
			return null;
		for (NPC npc : context.getNeededDemis())
		{
			WorldPoint at = npc.getWorldLocation();
			Point room = at == null ? null : context.opening.getRoomAt(at);
			if (room == null || Math.abs(room.getX() - current.getX()) + Math.abs(room.getY() - current.getY()) != 1
					|| here.getConnection(SceneGeometry.getDirection(current, room)) != GauntletRoom.Connection.OPEN
					|| !needed.isUseful(context.map, context.map.getRoom(room)))
				continue;
			LocalPathfinder.Goal goal = Routing.buildNpcGoal(npc, view, 0);
			LocalPathfinder.Choice choice = goal == null ? null : context.route.chooseNearest(Collections.singletonList(goal));
			if (choice != null && choice.path.size() - 1 <= DEMI_BESIDE_TILES)
				return room;
		}
		return null;
	}

	/** A needed demi in this room in a fight with the player; null when there is none. */
	private static NPC findFightHere(TickContext context, Point current)
	{
		for (NPC npc : context.getNeededDemis())
		{
			WorldPoint at = npc.getWorldLocation();
			if (at != null && context.opening.getRoomAt(at).equals(current) && context.isInFightWith(npc))
				return npc;
		}
		return null;
	}

	/**
	 * The room of the demi whose fight began with the player in its room and still goes on, with the player now through
	 * the open doorway; null when there is none. Either side's target counts, because the player's drops between attacks.
	 */
	private Point findHeldFightRoom(TickContext context, DemiBossPlan needed)
	{
		NPC npc = state.getHeldFight();
		if (npc == null || !context.getNeededDemis().contains(npc) || !context.isInFightWith(npc) || npc.getWorldLocation() == null)
			return null;
		Point room = context.opening.getRoomAt(npc.getWorldLocation());
		return needed.isUseful(context.map, context.map.getRoom(room)) ? room : null;
	}

	/** The perimeter search's next room once the outer room is checked, shown on the map; null before or when none. */
	private static GauntletMap.SearchTarget chooseSweepTarget(TickContext context, Point current)
	{
		if (!context.isOuterRoomChecked())
			return null;
		GauntletMap.SearchTarget next = context.perimeter.select(context.map, current, context.findReachableRoomSteps(current)::contains, context.plan,
				context.demiHistory, neighbor -> context.countDoorSteps(context.route, current, neighbor));
		if (next != null)
			context.setTourGoal(next.waypoint);
		return next;
	}

	/** This room's needed resources first, as the hunt's task; null when it has none. */
	private static Decision gatherThisRoom(TickContext context, WorldPoint here)
	{
		ObjectKind kind = context.gatherCurrentRoom(here);
		return kind == null ? null : Decision.CLAIM_ONLY.withInstruction("Demi hunting: " + kind.label);
	}

	/** A demi here whose drop is needed: fight it, with Next forecast from its drop. */
	private Decision fightDemiHere(TickContext context, DemiBossPlan needed, GauntletRoom room, Point current)
	{
		return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction("Demi hunting: " + needed.getObjective(room)).withOuterRoomChecked()
			.withAfterKill(previewAfterKill(context, needed, room, current));
	}

	/**
	 * An eligible enemy here, else (without a staff) a room already seen with one: a frame kill comes before the
	 * nearest new door, so the rat/bat/spider in the room just lit isn't passed for a door to an unopened room. In the
	 * regular Gauntlet, with none known, the ring's guaranteed weak-monster room is searched next.
	 */
	private Decision fightNearbyEnemy(TickContext context)
	{
		Decision fight = kills.fightOrdinaryEnemy(context);
		if (fight != null || context.plan.hasStaff())
			return fight;
		Decision known = kills.returnToKnownEnemyRoom(context);
		return known != null ? known : kills.returnToGuaranteedEnemyRoom(context);
	}

	/** Walk the sweep to its next room, else ask for the loot or an edge connection the search lacks. */
	private static Decision followSweep(TickContext context, GauntletMap.SearchTarget sweep)
	{
		if (sweep == null)
			return Decision.CLAIM_ONLY.withGuidancePaused().withInstruction("Demi hunting: check required loot or reveal a reachable edge connection");
		if (!context.canWalkTo(sweep.waypoint))
			return Decision.CLAIM_ONLY;
		// No "Demi sweep" summary line: it only repeated Current.
		return Decision.walkTo(sweep.waypoint, "Demi hunting: " + sweep.getLabel()).withGatherPrefix("Demi hunting: ");
	}

	/**
	 * Where travel goes once this demi is down and its drop is held, for the Next line and door during the fight.
	 * Forecast with the drop added and the kill counted, in the order applyPriorities then takes: stored fish home, the
	 * demi sweep while upgrades are missing, else the optimized tour. A repeat kill whose possible drops lead to
	 * different moves leaves the preview empty rather than guessing.
	 */
	private AfterKill previewAfterKill(TickContext context, DemiBossPlan needed, GauntletRoom room, Point current)
	{
		EnemyType killed = needed.chooseEnemyType(room);
		Set<WeaponType> drops = needed.computeDrops(room);
		state.beginFight(room, drops, DemiRoomObjective.computeHeldUpgrades(context.plan), countKills(context.demiHistory) + 1);
		AfterKill agreed = killed == null || drops.isEmpty() ? null : findAgreedMove(context, killed, drops, current);
		state.agreeMove(agreed);
		return agreed == null ? AfterKill.UNKNOWN : agreed;
	}

	/** The move every possible drop leads to; null when they differ or one cannot be told. */
	private AfterKill findAgreedMove(TickContext context, EnemyType killed, Set<WeaponType> drops, Point current)
	{
		DemiBossTracker kills = (context.demiHistory == null ? new DemiBossTracker() : context.demiHistory).withKill(killed);
		LocalPathfinder pickup = findDropRoute(context, killed, current);
		AfterKill agreed = null;
		for (WeaponType type : drops)
		{
			Simulation after = new Simulation(context.plan, context.inv, context.loot.countHomeFood(), 0);
			if (after.acquire(type.getComponent(), 1) == 0)
				return null;
			AfterKill move = planMoveAfterKill(context, after.plan(), after.snapshot(), kills, current, pickup);
			if (move == null || agreed != null && !agreed.isSame(move))
				return null;
			agreed = move;
		}
		return agreed;
	}

	/** The route from the demi's tile, where its drops land and the pickup leaves the player; else from the player. */
	private static LocalPathfinder findDropRoute(TickContext context, EnemyType demi, Point current)
	{
		for (NPC npc : context.getNeededDemis())
		{
			WorldPoint at = npc.getWorldLocation();
			if (EnemyType.fromNpcId(npc.getId()) != demi || at == null || !context.opening.getRoomAt(at).equals(current))
				continue;
			LocalPathfinder.Goal tile = Routing.buildNpcGoal(npc, context.getView(), 0);
			if (tile != null)
				return context.route.routeFrom(new Point(tile.minX, tile.minY));
		}
		return context.route;
	}

	/** A ground pickup keeps the fight's Next preview (holdAfterKill); a node finished before it doesn't. */
	Decision holdThroughPickup(Decision pickup, TickContext context)
	{
		return pickup == null || pickup.ground == null ? pickup : pickup.withAfterKill(holdAfterKill(context));
	}

	/**
	 * Drop collection in the fight's room keeps the fight's Next preview until the pickups are done. Loot
	 * elsewhere ends it, with null for no preview. A fight without a preview (a repeat kill with two possible drops)
	 * gets one from the actual drop once it is seen.
	 */
	private AfterKill holdAfterKill(TickContext context)
	{
		if (state.getFightRoom() == null || state.getFightRoom() != context.getCurrentRoom())
		{
			state.forgetFight();
			return null;
		}
		Point current = context.opening.getRoomAt(context.getHere());
		// Another demi died since the forecast, e.g. a second bear through the open doorway.
		AfterKill move = countKills(context.demiHistory) > state.getKills() ? planAfterKillFromLoot(context, current)
				: state.getMove() != null ? state.getMove() : planAfterKillFromDrop(context, current);
		return move == null ? AfterKill.UNKNOWN : move;
	}

	/**
	 * The move after a repeat kill whose drop the fight could not tell: forecast with the possible component seen in
	 * the room's loot, or with the plan as it is once that component is picked up. Null while neither is seen.
	 * Recomputed each update, so a tied sweep is measured from where the player stands.
	 */
	private AfterKill planAfterKillFromDrop(TickContext context, Point current)
	{
		Simulation after = new Simulation(context.plan, context.inv, context.loot.countHomeFood(), 0);
		if (DemiRoomObjective.computeHeldUpgrades(context.plan).equals(state.getUpgrades()))
		{
			WeaponType dropped = null;
			for (GroundLoot.Entry entry : context.loot.getEntries())
				for (WeaponType type : state.getDrops())
					if (entry.id == type.getComponent() && context.map.getRoom(context.opening.getRoomAt(entry.point)) == state.getFightRoom())
						dropped = type;
			if (dropped == null || after.acquire(dropped.getComponent(), 1) == 0)
				return null;
		}
		return planMoveAfterKill(context, after.plan(), after.snapshot(), context.demiHistory == null ? new DemiBossTracker() : context.demiHistory,
				current, context.route);
	}

	/**
	 * The move once every upgrade component on the ground in the rooms being picked up is held: after more than one
	 * kill, the fight's own forecast no longer fits.
	 */
	private AfterKill planAfterKillFromLoot(TickContext context, Point current)
	{
		Simulation after = new Simulation(context.plan, context.inv, context.loot.countHomeFood(), 0);
		for (GroundLoot.Entry entry : context.loot.getEntries())
			if (LootPickup.isUpgradeComponent(entry.id) && context.loot.computePickupQuantity(entry, context.plan, context.inv, false) > 0
					&& LootPickup.isDemiDropRoom(context.map.getRoom(context.opening.getRoomAt(entry.point)), context.getCurrentRoom()))
				after.acquire(entry.id, 1);
		return planMoveAfterKill(context, after.plan(), after.snapshot(), context.demiHistory == null ? new DemiBossTracker() : context.demiHistory,
				current, context.route);
	}

	private static int countKills(DemiBossTracker history)
	{
		if (history == null)
			return 0;
		int total = 0;
		for (EnemyType type : EnemyType.values())
			total += history.count(type);
		return total;
	}

	/**
	 * The first move of the forecast plan after the kill; null when it cannot be told. Every Decision carrying it pauses
	 * guidance, so a trip flag the pause clears (an outward trip) never stands in its way.
	 */
	private AfterKill planMoveAfterKill(TickContext context, Requirements plan, Inventory inv, DemiBossTracker kills, Point current, LocalPathfinder pickup)
	{
		Point home = GauntletMap.HOME;
		GroundLoot loot = context.loot;
		if (plan.isReadyForStoredFood() && loot.countHomeFood() > 0 && !context.isAtHome())
			return new AfterKill(home, "Return home to retrieve stored fish");
		if (!plan.getRow(Requirement.UPGRADES).isComplete())
		{
			// A tied sweep direction takes the nearer door from where the pickup leaves the player.
			GauntletMap.SearchTarget next = context.perimeter.copy().select(context.map, current, context.findReachableRoomSteps(current)::contains, plan,
					kills, neighbor -> context.countDoorSteps(pickup, current, neighbor));
			if (next == null)
				return null;
			return new AfterKill(next.waypoint, next.getLabel(), new DemiBossPlan(plan, kills).getObjective(context.map.getRoom(next.waypoint)));
		}
		TripPlanner.Result forecast = afterKillPlanner.plan(context.map, current, plan, inv, loot, kills,
				context.findReachableRoomSteps(current), context.getTourBudget(), true);
		TripPlanner.Step first = forecast.getNextTask();
		if (first == null || !first.room.equals(current) && TripPlanner.isGatherShortHomeTrip(forecast, plan, inv))
			return null;
		if (first.kind == Simulation.Kind.TELEPORT)
			return context.isTeleportWorthwhile() ? new AfterKill(null, first.label)
					: new AfterKill(home, "Return home to finish preparation");
		return new AfterKill(first.room.equals(current) ? null : first.room, first.label);
	}

	/** A forecast move: the room it heads for (null when it stays here or teleports) and its Next text, null when not certain. */
	static final class AfterKill
	{
		/** A demi fight with no certain move: Next stays empty rather than showing the nearest object. */
		static final AfterKill UNKNOWN = new AfterKill(null, null);

		final Point room;
		final String label;
		/** The forecast's cue for a demi in that room, counting this kill and its drop; null when the move isn't a sweep. */
		final String objective;

		AfterKill(Point room, String label)
		{
			this(room, label, null);
		}

		AfterKill(Point room, String label, String objective)
		{
			this.room = room;
			this.label = label;
			this.objective = objective;
		}

		boolean isSame(AfterKill other) { return label.equals(other.label) && Objects.equals(room, other.room); }
	}
}
