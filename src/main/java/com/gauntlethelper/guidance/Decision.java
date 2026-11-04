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
import java.util.List;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;

/**
 * What a task step decided: its claim on this run of the steps, which TaskPriorities.publish writes to GuidanceState.
 * A step returns null when it doesn't claim. A null field leaves what is already published.
 */
final class Decision
{
	/** A claim with nothing to publish: the steps stop here and what is published stays. */
	static final Decision CLAIM_ONLY = new Decision(false, null, null, null, null, null, null, null, null, null, false, null, false, false, null, false, false,
		null);

	/** Clear the default task, Next and trip flags first (Guidance.pauseGuidance). */
	final boolean guidancePaused;
	final TileObject target;
	final ObjectKind kind;
	final NPC enemy;
	final WorldPoint ground;
	final List<Point> path;
	/** The task is walking toward this room under instruction (Routing.navigateRoom, which publish runs last). */
	final Point room;
	/** With room, lit rooms on the way are toured for their nodes, under an instruction starting with this. */
	final String gatherPrefix;
	final String instruction;
	/** Next is the demi fight's after-kill move (DemiHunt), worked out by the step and written after the pause. */
	final DemiHunt.AfterKill afterKill;
	/** Latch the full-inventory return until home (AttemptLatches) and drop any ground target. */
	final boolean spaceReturnLatched;
	/** Next text for what follows this task (GuidanceState.nextPickupLabel). */
	final String nextPickupLabel;
	/** The hunt reached a useful demi, so the outer-room check is over (AttemptLatches). */
	final boolean outerRoomChecked;
	/** The trip home is by teleport (AttemptLatches), written after the pause, which clears it. */
	final boolean tripTeleport;
	/** Written after the walk, over the instruction and any waiting text its route added. */
	final String finalInstruction;
	/** The opening is over with no home visit; applyPriorities then chooses this update's task again. */
	final boolean openingEnded;
	/** With openingEnded: the staff needs no home visit, so the opening's home visit counts as done. */
	final boolean staffSettled;
	/** A sceptre recovery in place of Lighting this door, which publish keeps as Next after the walk (Sceptre). */
	final TileObject sceptreDoor;

	private Decision(boolean guidancePaused, TileObject target, ObjectKind kind, NPC enemy, WorldPoint ground, List<Point> path, Point room,
		String gatherPrefix, String instruction, DemiHunt.AfterKill afterKill, boolean spaceReturnLatched, String nextPickupLabel, boolean outerRoomChecked,
		boolean tripTeleport, String finalInstruction, boolean openingEnded, boolean staffSettled, TileObject sceptreDoor)
	{
		this.guidancePaused = guidancePaused;
		this.target = target;
		this.kind = kind;
		this.enemy = enemy;
		this.ground = ground;
		this.path = path;
		this.room = room;
		this.gatherPrefix = gatherPrefix;
		this.instruction = instruction;
		this.afterKill = afterKill;
		this.spaceReturnLatched = spaceReturnLatched;
		this.nextPickupLabel = nextPickupLabel;
		this.outerRoomChecked = outerRoomChecked;
		this.tripTeleport = tripTeleport;
		this.finalInstruction = finalInstruction;
		this.openingEnded = openingEnded;
		this.staffSettled = staffSettled;
		this.sceptreDoor = sceptreDoor;
	}

	/** The task is an object to use (null for a walking waypoint), its kind and the path to it. */
	static Decision task(TileObject target, ObjectKind kind, List<Point> path)
	{
		return new Decision(false, target, kind, null, null, path, null, null, null, null, false, null, false, false, null, false, false, null);
	}

	/** The task is an enemy to fight, with the path to it. */
	static Decision fight(NPC enemy, List<Point> path)
	{
		return new Decision(false, null, null, enemy, null, path, null, null, null, null, false, null, false, false, null, false, false, null);
	}

	/** The task is a ground pile to pick up, with the path to it. */
	static Decision pickup(WorldPoint ground, List<Point> path)
	{
		return new Decision(false, null, null, null, ground, path, null, null, null, null, false, null, false, false, null, false, false, null);
	}

	/**
	 * The task is walking toward a room, with its route built in publish. With no room route the instruction still shows
	 * and no route is drawn, so a step that shouldn't hold the instruction checks TickContext.canWalkTo first.
	 */
	static Decision walkTo(Point room, String instruction)
	{
		return new Decision(false, null, null, null, null, null, room, null, instruction, null, false, null, false, false, null, false, false, null);
	}

	Decision withGuidancePaused()
	{
		return new Decision(true, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	Decision withGatherPrefix(String prefix)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, prefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	Decision withInstruction(String text)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, text, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	/** Next shows this after-kill move; null leaves Next as it is. */
	Decision withAfterKill(DemiHunt.AfterKill move)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, move, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	Decision withNextPickup(String label)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, label,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	Decision withSpaceReturnLatched()
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, true, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	Decision withOuterRoomChecked()
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			true, tripTeleport, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	Decision withTripTeleport()
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, true, finalInstruction, openingEnded, staffSettled, sceptreDoor);
	}

	/** The walk's text is replaced by this once publish has walked it. */
	Decision withFinalInstruction(String text)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, text, openingEnded, staffSettled, sceptreDoor);
	}

	/** This task recovers a sceptre, drawn in the recovery blue, and the Light door it replaces stays as Next. */
	Decision withSceptreRecovery(TileObject door)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, openingEnded, staffSettled, door);
	}

	Decision withOpeningEnded(boolean settled)
	{
		return new Decision(guidancePaused, target, kind, enemy, ground, path, room, gatherPrefix, instruction, afterKill, spaceReturnLatched, nextPickupLabel,
			outerRoomChecked, tripTeleport, finalInstruction, true, settled, sceptreDoor);
	}
}
