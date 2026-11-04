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
import com.gauntlethelper.plan.OpeningBudget;
import com.gauntlethelper.plan.Requirements;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.gameval.ItemID;

/**
 * Task priorities for Guidance: applyPriorities runs the ordered taskSteps list, and the first step that claims
 * the update wins.
 */
final class TaskPriorities
{
	/** Seconds left below which cooking gives way to the walk to the barrier. */
	private static final int COOK_RESERVE_SECONDS = 15;
	static final String TIME_LOW_AT_HOME = "Time low: nothing left at home, go to Hunllef";
	private final Guidance guidance;
	private final GuidanceState targeting;
	final HomeReturns homeReturns;
	final HomeSpace homeSpace;
	final LootPickup pickup;
	final DemiHunt hunt;
	final KillObjective kills;
	final OptimizedTrip optimized;
	final Sceptre sceptre;
	private final List<TaskStep> taskSteps;

	TaskPriorities(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
		homeReturns = new HomeReturns();
		homeSpace = new HomeSpace(guidance, targeting);
		pickup = new LootPickup();
		kills = new KillObjective();
		hunt = new DemiHunt(kills);
		optimized = new OptimizedTrip(kills);
		sceptre = new Sceptre();
		// The task priorities, highest first. Two steps always run once reached and never claim (forecastDemiCoverage,
		// forgetAfterKill). Their places are part of the order: a step that claims earlier leaves them unrun, and the
		// drop pickups before forgetAfterKill still read the after-kill forecast.
		taskSteps = Arrays.asList(
				homeReturns::returnForFullInventory,
				TaskPriorities::awaitRoute,
				TaskPriorities::routeToBarrierWhenReady,
				updates(hunt::forecastDemiCoverage),
				kills::finishFightPastWarning,
				TaskPriorities::showInventoryAction,
				TaskPriorities::fillVials,
				context -> hunt.holdThroughPickup(pickup.pickUpLoot(context), context),
				hunt::collectDemiDrops,
				updates(context -> hunt.forgetAfterKill()),
				TaskPriorities::goToBarrierWhenTimeLowAtHome,
				homeReturns::returnForLowTime,
				TaskPriorities::showInventoryActionAfterLoot,
				homeReturns::returnForStoredFish,
				context -> continueOpeningWork(context, kills),
				hunt::huntDemis,
				optimized::followOptimizedTrip,
				kills::fightOrdinaryEnemy,
				homeReturns::returnForObservedLoot,
				kills::goToKnownEnemyRoom,
				optimized::gatherKnownResources,
				homeReturns::returnForHomeWork);
	}

	/** Guidance's share of an attempt reset. */
	void reset()
	{
		optimized.reset();
		hunt.reset();
		kills.reset();
		pickup.reset();
		sceptre.reset();
		homeReturns.reset();
		homeSpace.reset();
	}

	void applyPriorities(Requirements plan, Inventory inv, GroundLoot loot, boolean urgent)
	{
		targeting.sideEnemyTarget = null;
		boolean openingActive = guidance.opening.isActive();
		applyTaskSteps(plan, inv, loot, urgent);
		// The published Decision ended the opening (continueOpeningWork: five rooms done, no home work): this update's task
		// and the task steps are chosen again with the opening over. Once only, since the opening can't end a second time.
		if (openingActive && !guidance.opening.isActive())
		{
			guidance.update(plan);
			applyTaskSteps(plan, inv, loot, urgent);
		}
		publishSideEnemy(TickContext.of(guidance, plan, inv, loot, urgent));
		pickup.rememberTaskNode(targeting.target, targeting.targetKind);
	}

	/** One task step: its Decision when it claims this run of the steps, else null. */
	interface TaskStep
	{
		Decision decide(TickContext context);
	}

	/** A per-tick state update in the step order: it runs when reached and never claims. */
	static TaskStep updates(Consumer<TickContext> update)
	{
		return context ->
		{
			update.accept(context);
			return null;
		};
	}

	/** The first step's Decision that isn't null; the steps after it don't run. */
	static Decision chooseDecision(List<TaskStep> steps, TickContext context)
	{
		for (TaskStep step : steps)
		{
			Decision decision = step.decide(context);
			if (decision != null)
				return decision;
		}
		return null;
	}

	private void applyTaskSteps(Requirements plan, Inventory inv, GroundLoot loot, boolean urgent)
	{
		if (guidance.isAtHome() || plan.isReady())
			guidance.attempt.clearInventorySpaceReturn();
		guidance.syncLoot(loot);
		// Before the steps, from update's target, so they see this tick's demi room (TickContext.isInDemiObjective, the drops).
		guidance.updateDemiRoomCue(plan);
		applySteps(taskSteps, TickContext.of(guidance, plan, inv, loot, urgent));
	}

	void applySteps(List<TaskStep> steps, TickContext context)
	{
		Decision decision = chooseDecision(steps, context);
		if (decision != null)
			publish(decision);
	}

	/** The side objective beside a routed resource task: decided after the steps, because it reads the task they published. */
	void publishSideEnemy(TickContext context)
	{
		targeting.sideEnemyTarget = kills.updateSideEnemy(context);
	}

	/** The sceptre recovery, over the task and Next already published, whichever step or home work chose them. */
	void publishSceptreRecovery(TickContext context)
	{
		sceptre.clearRecovery();
		Decision recovery = sceptre.requireSceptre(context);
		if (recovery != null)
			publish(recovery);
	}

	/** The one place a Decision reaches GuidanceState and the instruction. */
	void publish(Decision decision)
	{
		if (decision.guidancePaused)
			guidance.pauseGuidance();
		if (decision.tripTeleport)
			guidance.attempt.startTripTeleport();
		if (decision.target != null || decision.kind != null)
			targeting.setTask(decision.target, decision.kind, decision.path);
		if (decision.enemy != null)
		{
			targeting.enemyTarget = decision.enemy;
			targeting.path = decision.path;
		}
		if (decision.ground != null)
		{
			targeting.groundTarget = decision.ground;
			targeting.path = decision.path;
		}
		if (decision.instruction != null)
			guidance.attempt.setInstruction(decision.instruction);
		if (decision.outerRoomChecked)
			guidance.attempt.markOuterRoomChecked();
		if (decision.afterKill != null)
		{
			targeting.demiFight = true;
			targeting.afterKillRoom = decision.afterKill.room;
			targeting.afterKillLabel = decision.afterKill.label;
			targeting.afterKillObjective = decision.afterKill.objective;
		}
		if (decision.nextPickupLabel != null)
			targeting.nextPickupLabel = decision.nextPickupLabel;
		if (decision.spaceReturnLatched)
		{
			targeting.groundTarget = null;
			guidance.attempt.latchInventorySpaceReturn();
		}
		if (decision.room != null)
			guidance.routing.navigateRoom(decision.room, decision.instruction, decision.gatherPrefix);
		if (decision.finalInstruction != null)
			guidance.attempt.setInstruction(decision.finalInstruction);
		if (decision.openingEnded)
		{
			guidance.opening.finishWithoutHome();
			guidance.attempt.endOpeningWithoutHome(decision.staffSettled);
		}
		if (decision.sceptreDoor != null)
		{
			sceptre.startRecovery();
			// Last, because the walk home may itself lead through this door, which then stays the task.
			if (decision.sceptreDoor != targeting.target)
			{
				targeting.clearNext();
				targeting.setNext(decision.sceptreDoor, ObjectKind.DOOR);
			}
		}
	}

	/** No route or opening yet: nothing more this update. */
	static Decision awaitRoute(TickContext context)
	{
		return context.route == null || !context.opening.isInitialized() ? Decision.CLAIM_ONLY : null;
	}

	static Decision routeToBarrierWhenReady(TickContext context)
	{
		return context.plan.isReady() ? routeToBarrier(context) : null;
	}

	/**
	 * Ready for Hunllef: path to and highlight the nearest reachable barrier into its room. With none reachable yet,
	 * head for the nearest discovered room beside Hunllef's. Entering stays manual. With neither, the steps still stop here.
	 */
	private static Decision routeToBarrier(TickContext context)
	{
		WorldView view = context.getView();
		if (view == null)
			return Decision.CLAIM_ONLY;
		Decision barrier = findLoadedBarrier(context, view);
		return barrier != null ? barrier : headBesideHunllef(context);
	}

	/** The nearest reachable loaded barrier as the task; null when none is reachable. */
	private static Decision findLoadedBarrier(TickContext context, WorldView view)
	{
		List<TileObject> candidates = new ArrayList<>();
		List<LocalPathfinder.Goal> goals = new ArrayList<>();
		for (TileObject barrier : context.getBarriers())
		{
			if (barrier.getPlane() != view.getPlane() || barrier.getLocalLocation() == null)
				continue;
			goals.add(Routing.buildObjectGoal(barrier, Routing.addCandidate(candidates, barrier), false));
		}
		LocalPathfinder.Choice choice = context.route.chooseNearest(goals);
		if (choice == null)
			return null;
		return Decision.task(candidates.get(choice.index), ObjectKind.BARRIER, choice.path).withGuidancePaused().withInstruction(ObjectKind.BARRIER.label);
	}

	/**
	 * Time low at home with no station left to use: the task already up would lead out of home, and one step out the
	 * time-low return would turn back. The barrier is the task instead. Goals stay as they are and the panel still lists
	 * what is short. The barrier's doorway tile counts as Hunllef's room, which is where this ends.
	 */
	static Decision goToBarrierWhenTimeLowAtHome(TickContext context)
	{
		boolean atBarrier = context.opening.getRoomAt(context.getHere()).equals(context.opening.getHunllefRoom());
		if (!context.urgent || !context.isAtHome() && !atBarrier || isHomeStationTask(context))
			return null;
		Decision cook = context.isAtHome() ? cookHeldFish(context) : null;
		return cook != null ? cook : routeToBarrier(context).withFinalInstruction(TIME_LOW_AT_HOME);
	}

	/**
	 * The range for raw fish still short of the food target, while the time left allows: raw fish can't be eaten against
	 * Hunllef, and with an upgrade missing no other step asks for them to be cooked. Null otherwise.
	 */
	private static Decision cookHeldFish(TickContext context)
	{
		Inventory inv = context.inv;
		if (inv.getHeld(ItemID.GAUNTLET_RAW_FOOD) == 0 || inv.getHeld(ItemID.GAUNTLET_FOOD) >= context.plan.getFoodTarget()
				|| context.getSecondsLeft() < COOK_RESERVE_SECONDS)
			return null;
		Decision range = context.findServiceTask(ObjectKind.RANGE, false);
		return range == null ? null : range.withInstruction(ObjectKind.RANGE.label);
	}

	private static boolean isHomeStationTask(TickContext context)
	{
		TileObject target = context.getTaskTarget();
		ObjectKind kind = context.getTaskKind();
		return target != null && kind != null && kind.isHomeStation() && context.opening.getRoomAt(target.getWorldLocation()).equals(GauntletMap.HOME);
	}

	/** The walk to the nearest discovered room beside Hunllef's, where a barrier loads; nothing to publish with no such walk. */
	private static Decision headBesideHunllef(TickContext context)
	{
		GauntletRoom boss = context.map.getRoom(3, 3);
		if (boss == null)
			return Decision.CLAIM_ONLY;
		Point current = context.opening.getRoomAt(context.getHere());
		Point hunllef = context.map.getRelative(boss);
		Point best = null;
		int length = Integer.MAX_VALUE;
		for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
		{
			Point beside = new Point(hunllef.getX() + d[0], hunllef.getY() + d[1]);
			GauntletRoom room = context.map.getRoom(beside);
			if (room == null || !room.isDiscovered())
				continue;
			List<Point> rooms = context.map.findPath(current, beside, context.findReachableRoomSteps(current)::contains);
			if (!rooms.isEmpty() && rooms.size() < length)
			{
				best = beside;
				length = rooms.size();
			}
		}
		if (best == null || best.equals(current) || !context.canWalkTo(best))
			return Decision.CLAIM_ONLY;
		return Decision.walkTo(best, "Head to the barrier to Hunllef");
	}

	/** An inventory action holds the task steps; one that needs space away from home goes home for it. */
	static Decision showInventoryAction(TickContext context)
	{
		Requirements plan = context.plan;
		if (!plan.isInventoryAction())
			return null;
		if (!plan.needsInventorySpace() || context.isAtHome())
			return Decision.CLAIM_ONLY;
		String instruction = HomeReturns.buildFullReturnInstruction(plan, context.getHomeVisit());
		return context.canWalkTo(GauntletMap.HOME) ? Decision.walkTo(GauntletMap.HOME, instruction) : Decision.CLAIM_ONLY;
	}

	/** Empty vials are filled next, except in the demi room objective or at home with a craft waiting. */
	static Decision fillVials(TickContext context)
	{
		if (context.isInDemiObjective() || !context.plan.needsWater() || context.isAtHome() && context.canCraft())
			return null;
		Decision water = context.findServiceTask(ObjectKind.WATER, true);
		return water == null ? null : water.withInstruction("Fill empty vials");
	}

	static Decision showInventoryActionAfterLoot(TickContext context)
	{
		return context.plan.isInventoryAction() ? Decision.CLAIM_ONLY : null;
	}

	/**
	 * Opening work is authoritative: only local loot/required enemies may interrupt it, not another room plan. With five
	 * rooms done and no home work the Decision ends the opening, and applyPriorities runs this update and the task steps
	 * again with the opening over.
	 */
	static Decision continueOpeningWork(TickContext context, KillObjective kills)
	{
		if (!context.opening.isActive())
			return null;
		ObjectKind published = context.getTaskKind();
		if (published != null && published.resource != null)
			return Decision.CLAIM_ONLY;
		Decision fight = kills.fightOrdinaryEnemy(context);
		if (fight != null)
			return fight;
		Requirements plan = context.plan;
		if (context.opening.getStep() >= 5 && !OpeningBudget.hasHomeWork(plan, context.getCrafts()))
			return Decision.CLAIM_ONLY.withGuidancePaused().withOpeningEnded(!plan.needsOpeningStaff() || plan.hasStaff());
		return Decision.CLAIM_ONLY;
	}
}
