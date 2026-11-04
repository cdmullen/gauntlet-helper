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
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Inventory;
import com.gauntlethelper.plan.InventoryAdvice;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.TeleportEstimate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;

/**
 * Home work outside the task steps: the home storage hold before a space-constrained departure (prepareHomeSpace, which
 * replaces the steps when it applies) and whether a teleport home beats the walk.
 */
final class HomeSpace
{
	/** The rooms a known staff-return walk must visit, start and home included, before it gets the crystal. */
	private static final int STAFF_TELEPORT_ROOMS = 4;

	private final Guidance guidance;
	private final GuidanceState targeting;
	/** At home, fish storage goes before the bowl because crafts are waiting for slots (prepareHomeSpace). */
	private boolean storeBeforeCrafting;
	/** This home visit went to the range to cook and store: the raw fish left are cooked there too before leaving. */
	private boolean cookingThisVisit;
	/** The crystal was used on a trip heading for the range: its slot, now free, doesn't change the station on landing. */
	private boolean landingAtRange;

	HomeSpace(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	void reset()
	{
		storeBeforeCrafting = false;
		cookingThisVisit = false;
		landingAtRange = false;
	}

	/** The crystal just left the inventory; plan is the one from before, still holding it. */
	void observeCrystalUsed(Requirements plan)
	{
		landingAtRange = HomeReturns.isStorageFirst(plan, new HomeVisit(plan));
	}

	boolean isTeleportWorthwhile()
	{
		if (targeting.lastRoute == null || !guidance.opening.isInitialized() || guidance.attempt.isAtHome())
			return false;
		WorldView view = guidance.client.getTopLevelWorldView();
		if (view == null)
			return false;
		LocalPathfinder known = guidance.routing.getKnownRoute(targeting.lastRoute, view);
		List<LocalPathfinder.Goal> bowls = findHomeBowls(view);
		Point spawn = guidance.opening.getRoomCenter(GauntletMap.HOME);
		int x = spawn.getX() - view.getBaseX(), y = spawn.getY() - view.getBaseY();
		LocalPathfinder.Choice walking = known.chooseNearest(bowls), arrival = known.routeFrom(new Point(x, y)).chooseNearest(bowls);
		GauntletRoom home = guidance.gauntletMap.getRoom(GauntletMap.HOME);
		int arrivalTiles = arrival == null ? Integer.MAX_VALUE : arrival.path.size() - 1;
		if (arrival == null && home != null)
			for (Map.Entry<WorldPoint, ObjectKind> facility : home.getFacilities().entrySet())
				if (facility.getValue() == ObjectKind.BOWL)
					arrivalTiles = Math.min(arrivalTiles,
							Math.max(0, Math.abs(spawn.getX() - facility.getKey().getX())
							+ Math.abs(spawn.getY() - facility.getKey().getY()) - 1));
		if (arrivalTiles == Integer.MAX_VALUE)
			return false;
		// A known walk from the room beside home is a short home trip: walk and keep the crystal.
		if (walking != null)
			return countRoomsCrossed(walking.path, view) > 2
				&& TeleportEstimate.isWorthwhile(walking.path.size() - 1, TeleportEstimate.ACTIVATION_TICKS, arrivalTiles);
		// When intervening scene tiles are unloaded, a straight-line lower bound can still prove a saving.
		WorldPoint player = guidance.client.getLocalPlayer().getWorldLocation();
		int lowerBound = Integer.MAX_VALUE;
		if (home != null)
			for (Map.Entry<WorldPoint, ObjectKind> facility : home.getFacilities().entrySet())
				if (facility.getValue() == ObjectKind.BOWL)
					lowerBound = Math.min(lowerBound,
							Math.max(0, Math.max(Math.abs(player.getX() - facility.getKey().getX()),
							Math.abs(player.getY() - facility.getKey().getY())) - 1));
		return lowerBound < Integer.MAX_VALUE && TeleportEstimate.isWorthwhile(lowerBound, TeleportEstimate.ACTIVATION_TICKS, arrivalTiles);
	}

	/**
	 * Whether the staff return may spend the crystal: the one crystal is held for the final return from the demi hunt,
	 * unless this walk is itself long. A walk with no known route counts as long.
	 */
	boolean isStaffWalkLong()
	{
		return countHomeWalkRooms() >= STAFF_TELEPORT_ROOMS;
	}

	/** How many rooms the known walk to the bowl visits, start and home included; MAX_VALUE when no such walk is known. */
	int countHomeWalkRooms()
	{
		WorldView view = guidance.client.getTopLevelWorldView();
		if (targeting.lastRoute == null || !guidance.opening.isInitialized() || view == null)
			return Integer.MAX_VALUE;
		LocalPathfinder.Choice walking = guidance.routing.getKnownRoute(targeting.lastRoute, view).chooseNearest(findHomeBowls(view));
		return walking == null ? Integer.MAX_VALUE : countRoomsCrossed(walking.path, view);
	}

	private List<LocalPathfinder.Goal> findHomeBowls(WorldView view)
	{
		List<LocalPathfinder.Goal> bowls = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> e : guidance.objects.entrySet())
			if (e.getValue() == ObjectKind.BOWL && guidance.isActive(e.getKey(), e.getValue()))
			{
				TileObject object = e.getKey();
				if (!guidance.isHomeTile(object.getWorldLocation()))
					continue;
				bowls.add(guidance.routing.buildServiceGoal(object, ObjectKind.BOWL, 0));
			}
		return bowls;
	}

	/** How many rooms a scene path visits, its start and end rooms included. */
	private int countRoomsCrossed(List<Point> path, WorldView view)
	{
		Set<Point> rooms = new HashSet<>();
		for (Point tile : path)
			rooms.add(guidance.opening.getRoomAt(tile.getX() + view.getBaseX(), tile.getY() + view.getBaseY()));
		return rooms.size();
	}

	/**
	 * Finish available home conversion or storage before a space-constrained departure, in the fixed home order:
	 * craft at the bowl, fill vials at the pump, then cook and store fish at the range. Crafts waiting for slots
	 * move the range first.
	 */
	boolean prepareHomeSpace(Requirements plan, InventoryAdvice advice)
	{
		if (!guidance.isAtHome())
			cookingThisVisit = false;
		if (!guidance.isAtHome() || !advice.shouldStoreFish() || plan.isReady() || plan.isInventoryAction() && !plan.needsInventorySpace())
		{
			storeBeforeCrafting = false;
			landingAtRange &= !guidance.isAtHome();
			return finishCookingHere(plan);
		}
		guidance.attempt.clearInventorySpaceReturn();
		// The task update chose, before the storage hold clears it; the task steps keep it after storing (isStaffSearchNext).
		TileObject departure = targeting.target;
		ObjectKind departureKind = targeting.targetKind;
		HomeCraftPlan home = guidance.getHomeVisit(plan).planCrafts();
		// Crafts waiting for slots: cook and leave the surplus fish first.
		// Held until storage ends, so a slot freed mid-cook (mixing a grym leaf) doesn't send the bowl back in between.
		storeBeforeCrafting |= (landingAtRange || HomeReturns.isWaitingPastDropCues(plan, home, advice)) && !advice.isDropInProgress();
		landingAtRange = false;
		boolean stationFirst = !advice.isDropInProgress() && !storeBeforeCrafting;
		if (stationFirst && home.getNextCraft() != null)
			craftAtBowl(home);
		else if (stationFirst && plan.needsWater())
			fillVialsAtPump(plan);
		else
		{
			cookingThisVisit = true;
			cookAndStore(plan, advice, departure, departureKind);
		}
		return true;
	}

	/**
	 * Storing is done with raw fish still held: the player is at the range, so they are cooked now rather than on the
	 * final visit. The bowl, the pump and inventory actions still come first. False when this doesn't apply.
	 */
	private boolean finishCookingHere(Requirements plan)
	{
		Inventory inv = plan.getInventory();
		if (!cookingThisVisit || plan.isReady() || plan.isInventoryAction() || guidance.canCraft(plan) || plan.needsWater()
				|| inv.getHeld(ItemID.GAUNTLET_RAW_FOOD) == 0 || inv.getHeld(ItemID.GAUNTLET_FOOD) >= plan.getFoodTarget())
			return false;
		holdForHomeStorage(ObjectKind.RANGE.label);
		if (targeting.lastRoute != null && guidance.routing.targetService(ObjectKind.RANGE, false, ObjectKind.RANGE.label))
			guidance.next.previewDeparture(plan);
		return true;
	}

	private void craftAtBowl(HomeCraftPlan home)
	{
		holdForHomeStorage(home.getInstruction());
		if (targeting.lastRoute != null)
			guidance.routing.targetService(ObjectKind.BOWL, false, home.getInstruction());
	}

	/** Filling comes before cooking and storing, so storage never pre-empts it; the range is Next, not the pump's text. */
	private void fillVialsAtPump(Requirements plan)
	{
		holdForHomeStorage("Fill empty vials");
		// The pump alone: a pond past home's door would turn into a full-inventory return home a step later.
		if (targeting.lastRoute != null && guidance.routing.targetService(ObjectKind.WATER, false, "Fill empty vials"))
			guidance.next.previewStation(plan, ObjectKind.RANGE);
	}

	/**
	 * Cook the surplus first; standing at the range is also where the cooked fish are then dropped. Next is the bowl
	 * when crafts wait for the space, else the departure.
	 */
	private void cookAndStore(Requirements plan, InventoryAdvice advice, TileObject departure, ObjectKind departureKind)
	{
		holdForHomeStorage(advice.getInstruction());
		if (targeting.lastRoute != null && (advice.getCookBeforeStoring() > 0 || advice.shouldStoreFish()))
			guidance.routing.targetService(ObjectKind.RANGE, false, advice.getInstruction());
		if (storeBeforeCrafting)
			guidance.next.previewStation(plan, ObjectKind.BOWL);
		else if (!isStaffSearchNext(plan, departure, departureKind))
			guidance.next.previewDeparture(plan);
	}

	/**
	 * Without a staff after the opening (no frame), the departure after cooking and storing is the staff search, not
	 * the demi hunt: Next is the task applyPriorities then takes, in its order: an eligible enemy in this room, the room of
	 * a known one, else the task update chose. False when a staff is held or due.
	 */
	private boolean isStaffSearchNext(Requirements plan, TileObject departure, ObjectKind departureKind)
	{
		if (!plan.needsOpeningStaff() || plan.hasStaff())
			return false;
		targeting.clearNext();
		if (guidance.config == null || targeting.lastRoute == null
				|| !guidance.opening.isInitialized() || guidance.opening.isActive())
			return true;
		TickContext context = TickContext.forPreview(guidance, plan, plan.getInventory());
		KillObjective.EnemyChoice enemy = guidance.priorities.kills.chooseOrdinaryEnemy(context, false);
		if (enemy != null)
		{
			targeting.nextEnemy = enemy.npc;
			targeting.nextLabel = "Defeat " + guidance.mode.getEnemyPrefix() + " "
					+ EnemyType.fromNpcId(enemy.npc.getId()).getLabel()
					+ " for " + enemy.reason;
			return true;
		}
		KillObjective.KnownEnemyRoom known = guidance.priorities.kills.findKnownEnemyRoom(context);
		if (known != null)
		{
			TileObject door = guidance.routing.findDoorOnWay(known.room);
			if (door != null && !guidance.isOpenPassage(door))
				guidance.next.previewNext(plan, door, ObjectKind.DOOR);
			else
				targeting.nextLabel = known.instruction;
			return true;
		}
		if (departure != null && departureKind != null && departureKind != ObjectKind.PASSAGE && !guidance.isOpenPassage(departure, departureKind))
			guidance.next.previewNext(plan, departure, departureKind);
		return true;
	}

	boolean shouldStoreBeforeCrafting() { return storeBeforeCrafting; }

	void holdForHomeStorage(String instruction)
	{
		guidance.pauseGuidance();
		targeting.groundTarget = null;
		guidance.attempt.setInstruction(instruction);
		targeting.clearNext();
	}
}
