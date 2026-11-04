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

package com.gauntlethelper.plan;

import com.gauntlethelper.game.EnemyType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Actor;
import net.runelite.api.NPC;

/**
 * Observed deaths and upgrade components seen on the ground. Neither substitutes for collecting an upgrade: they only
 * tell which component a demi's next kill can drop.
 */
public final class DemiBossTracker
{
	/** Observed kills by demi type. */
	private final Map<EnemyType, Integer> kills = new EnumMap<>(EnemyType.class);
	private final Set<Actor> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<WeaponType> droppedComponents = EnumSet.noneOf(WeaponType.class);

	public void reset()
	{
		kills.clear();
		recorded.clear();
		droppedComponents.clear();
	}

	public void record(Actor actor)
	{
		if (!(actor instanceof NPC))
			return;
		EnemyType type = EnemyType.fromNpcId(((NPC) actor).getId());
		if (type != null && type.isDemiBoss() && recorded.add(actor))
			kills.merge(type, 1, Integer::sum);
	}

	/**
	 * Notes an item seen on the ground. Only a demi's drop puts an upgrade component in the world, so one the player
	 * dropped by hand had already dropped too; it stays noted after a pickup or expiry.
	 */
	public void observeGroundItem(int itemId)
	{
		for (WeaponType type : WeaponType.values())
			if (type.getComponent() == itemId)
				droppedComponents.add(type);
	}

	/** Whether this weapon's component has been seen on the ground this attempt; it may since be held, left or gone. */
	public boolean hasDropped(WeaponType type) { return droppedComponents.contains(type); }

	public int count(EnemyType type) { return kills.getOrDefault(type, 0); }

	/** A forecast copy with one more kill of type; the observed history is unchanged. */
	public DemiBossTracker withKill(EnemyType type)
	{
		DemiBossTracker copy = new DemiBossTracker();
		copy.kills.putAll(kills);
		copy.kills.merge(type, 1, Integer::sum);
		copy.droppedComponents.addAll(droppedComponents);
		return copy;
	}
	public String buildSummary()
	{
		return "Demi kills: dragon " + count(EnemyType.DRAGON) + ", beast " + count(EnemyType.DARK_BEAST) + ", bear " + count(EnemyType.BEAR);
	}

	/** Everything the demi forecast reads, for a planner's cache key. */
	public String buildForecastKey() { return buildSummary() + ", dropped " + droppedComponents; }
}
