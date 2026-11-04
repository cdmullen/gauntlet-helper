/*
 * BSD 2-Clause License
 *
 * Copyright (c) 2026, cdmullen
 * Copyright (c) 2023, InfernoStats
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

package com.gauntlethelper.boss;

import com.gauntlethelper.config.GauntletHelperConfig;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Projectile;
import net.runelite.api.gameval.SpotanimID;

/**
 * Setting "Prayer-disable projectile": swaps the orb of Hunllef's prayer-disable attack (Crystalline and Corrupted)
 * for the chosen graphic. Adapted from the Hunllef Projectiles plugin (Plugin Hub: hunllef-projectiles), which only
 * swaps the Crystalline orb; this version uses the WorldPoint projectile API. The copy keeps the orb's
 * path, timing and target.
 */
public class HunllefProjectiles
{
	private final Client client;
	private final GauntletHelperConfig config;

	@Inject
	HunllefProjectiles(Client client, GauntletHelperConfig config)
	{
		this.client = client;
		this.config = config;
	}

	static boolean isPrayerOrb(int id)
	{
		return id == SpotanimID.CRYSTAL_HUNLLEF_PRAYER_TRAVEL || id == SpotanimID.CRYSTAL_HUNLLEF_PRAYER_TRAVEL_HM;
	}

	/** Called for every moving projectile; replaces a prayer-disable orb once, then hides the original. */
	public void replace(Projectile projectile)
	{
		int id = projectile.getId();
		// An end cycle of 0 marks an orb already replaced, waiting for the client to drop it.
		if (!isPrayerOrb(id) || projectile.getEndCycle() == 0)
			return;
		int replacement = config.prayerDisableProjectile().getSpotanimId();
		// The same graphic needs no copy, which also stops a Crystalline or Corrupted copy from being replaced again.
		if (replacement < 0 || replacement == id)
			return;
		Projectile copy = client.createProjectile(replacement,
				projectile.getSourcePoint(), projectile.getStartHeight(), projectile.getSourceActor(),
				projectile.getTargetPoint(), projectile.getEndHeight(), projectile.getTargetActor(),
				projectile.getStartCycle(), projectile.getEndCycle(), projectile.getSlope(), projectile.getStartPos());
		spawn(copy);
		projectile.setEndCycle(0);
	}

	// The client may already list a projectile it created; add it only when it isn't there.
	private void spawn(Projectile copy)
	{
		if (copy == null)
			return;
		for (Projectile listed : client.getProjectiles())
		{
			if (listed == copy)
				return;
		}
		client.getProjectiles().addLast(copy);
	}
}
