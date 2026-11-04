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

package com.gauntlethelper.overlay;

import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;

/** Resource visibility is independent of panel/infobox settings and route choice. */
public final class ResourceVisibility
{
	private ResourceVisibility() { }

	public static boolean isNodeResource(Requirement resource)
	{
		if (resource == null)
		{
			return false;
		}
		switch (resource)
		{
			case ORE:
			case BARK:
			case LINUM_TIRINUM:
			case GRYM_LEAVES:
			case FISH:
				return true;
			default:
				return false;
		}
	}

	public static boolean isVisible(Requirement resource, Requirements plan, GauntletHelperConfig config)
	{
		return plan != null && isNodeResource(resource) && config.highlightResourceNodes()
				&& (!config.hideAfterResourceTarget() || !plan.getRow(resource).isComplete());
	}

	/**
	 * A resource's minimap icon stays until its node resource is satisfied, with Hide completed resource minimap icons
	 * on; without a plan nothing is known to be complete, so the icon stays.
	 */
	public static boolean isMinimapVisible(Requirement resource, Requirements plan, GauntletHelperConfig config)
	{
		return resource != null && (!config.hideCompletedResourceMinimapIcons() || plan == null
				|| !isNodeResource(resource) || !plan.getRow(resource).isComplete());
	}

	/**
	 * With Resource nodes off, the current and Next resource nodes keep their caption, drawn in the node's
	 * configured color, without an outline.
	 */
	public static boolean isCaptionOnly(Requirement resource, GauntletHelperConfig config)
	{
		return isNodeResource(resource) && !config.highlightResourceNodes();
	}
}
