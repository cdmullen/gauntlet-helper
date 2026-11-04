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

package com.gauntlethelper.config;

import com.gauntlethelper.plan.Requirement;
import java.awt.Color;

/** Resource selectors live outside RuneLite's configuration proxy. */
public final class ResourceSettings
{
	private ResourceSettings() { }

	public static ResourceDisplay getDisplay(GauntletHelperConfig config, Requirement resource)
	{
		switch (resource)
		{
			case ORE:
				return config.oreTracker();
			case BARK:
				return config.barkTracker();
			case LINUM_TIRINUM:
				return config.linumTirinumTracker();
			case SHARDS:
				return config.shardsTracker();
			case FRAMES:
				return config.framesTracker();
			case UPGRADES:
				return config.upgradesTracker();
			case GRYM_LEAVES:
				return config.grymLeavesTracker();
			case FISH:
				return config.fishTracker();
			default:
				// Crafted counts (vials, dust, cooked fish, potions, armor, weapons) are never shown: the infoboxes
				// and overlay rows track only gathered or dropped materials.
				return ResourceDisplay.HIDDEN;
		}
	}

	/**
	 * Fishing spot outline default: The Gauntlet plugin's cyan, so users coming from it see the same color.
	 */
	public static final Color FISH_OUTLINE = new Color(0, 255, 255, 255);

	/**
	 * Next action default: a faint gray-orange, so Next doesn't draw attention away from the current task.
	 */
	public static final Color NEXT_COLOR = new Color(155, 130, 105, 180);

	public static Color getResourceOutlineColor(GauntletHelperConfig config, Requirement resource)
	{
		switch (resource)
		{
			case ORE:
				return config.oreOutlineColor();
			case BARK:
				return config.phrenRootsOutlineColor();
			case LINUM_TIRINUM:
				return config.linumTirinumOutlineColor();
			case GRYM_LEAVES:
				return config.grymRootOutlineColor();
			case FISH:
				return config.fishingSpotOutlineColor();
			default:
				return config.currentActionColor();
		}
	}
}
