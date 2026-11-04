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
import com.gauntlethelper.map.GauntletRoom;
import com.gauntlethelper.plan.DemiBossPlan;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import com.gauntlethelper.plan.WeaponType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.NPC;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;

/**
 * The demi-room cue for Guidance: the room-stay objective
 * (DemiRoomObjective), the living loaded demis whose drop is still needed (the clickbox), the one the hunt fights
 * first in a room, and the demi room's area.
 */
final class DemiCue
{
	private final Guidance guidance;
	final DemiRoomObjective demiRoom = new DemiRoomObjective();

	DemiCue(Guidance guidance)
	{
		this.guidance = guidance;
	}

	void reset()
	{
		demiRoom.reset();
	}

	/** Called on the client thread after routing and loot observation; rendering is read-only. */
	void update(Requirements plan)
	{
		if (guidance.config == null)
		{
			demiRoom.reset();
			return;
		}
		demiRoom.update(plan, guidance.gauntletMap, guidance.getCurrentMapRoom(), guidance.getDestinationMapRoom(), guidance.demiHistory,
			guidance.client.getTickCount());
	}

	/**
	 * Living, loaded demi-bosses whose drop is still needed for the selected loadout (same ranking as the demi search).
	 * Rendering reads this on the client thread.
	 */
	List<NPC> findNeededDemis()
	{
		WorldView view = guidance.client == null ? null : guidance.client.getTopLevelWorldView();
		Requirements plan = guidance.attempt.getNavigationPlan();
		if (view == null || view.npcs() == null || plan == null || plan.isReady() || guidance.config == null)
			return Collections.emptyList();
		if (isLastUpgradeComing(plan))
			return Collections.emptyList();
		DemiBossPlan needed = new DemiBossPlan(plan, guidance.demiHistory);
		List<NPC> result = new ArrayList<>();
		for (NPC npc : view.npcs())
		{
			if (npc == null || npc.isDead())
				continue;
			EnemyType type = EnemyType.fromNpcId(npc.getId());
			// An outranked demi (a fallback dark beast once a dragon is spotted) loses its clickbox.
			if (type != null && type.isDemiBoss() && needed.rank(type) < Integer.MAX_VALUE && !needed.isOutranked(guidance.gauntletMap, type, null))
				result.add(npc);
		}
		return result;
	}

	/**
	 * Whether every missing upgrade is lying on the ground or about to land from the demi just killed, so no other demi
	 * is worth a Defeat cue. A drop that is lost or expires brings the cue back.
	 */
	private boolean isLastUpgradeComing(Requirements plan)
	{
		Set<WeaponType> coming = EnumSet.noneOf(WeaponType.class);
		coming.addAll(demiRoom.getAwaitedUpgrades());
		GroundLoot policy = new GroundLoot();
		for (GauntletRoom room : guidance.gauntletMap.getRooms())
			for (GroundLoot.Entry entry : room.getLoot())
				for (WeaponType type : WeaponType.values())
					if (type.getComponent() == entry.id && policy.isNeeded(entry, plan, plan.getInventory(), false))
						coming.add(type);
		int missing = plan.getRow(Requirement.UPGRADES).getMissing();
		return missing > 0 && coming.size() >= missing && (!plan.needsStaffOrb() || coming.contains(WeaponType.STAFF));
	}

	/** The living demi the hunt would fight first in room, when it is in the loaded scene; else null. */
	NPC findVisibleDemi(Point room, Requirements plan)
	{
		GauntletRoom known = guidance.gauntletMap.getRoom(room);
		EnemyType chosen = known == null ? null : new DemiBossPlan(plan, guidance.demiHistory).chooseEnemyType(known);
		if (chosen == null)
			return null;
		for (NPC npc : guidance.getNeededDemis())
		{
			WorldPoint at = npc.getWorldLocation();
			if (EnemyType.fromNpcId(npc.getId()) == chosen && at != null && guidance.opening.getRoomAt(at).equals(room))
				return npc;
		}
		return null;
	}

	WorldArea getRoomArea()
	{
		GauntletRoom room = demiRoom.getRoom();
		WorldView view = guidance.client == null ? null : guidance.client.getTopLevelWorldView();
		if (room == null || view == null || !guidance.opening.isInitialized() || guidance.attempt.isInventorySpaceReturn() || guidance.attempt.isHomeReturn())
			return null;
		Point center = guidance.opening.getRoomCenter(guidance.gauntletMap.getRelative(room));
		int radius = guidance.opening.getRoomPitch() / 2 - 1;
		return new WorldArea(center.getX() - radius, center.getY() - radius, radius * 2 + 1, radius * 2 + 1, view.getPlane());
	}
}
