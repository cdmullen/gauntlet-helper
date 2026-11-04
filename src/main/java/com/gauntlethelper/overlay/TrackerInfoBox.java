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

import com.gauntlethelper.GauntletHelperPlugin;
import com.gauntlethelper.config.GauntletHelperConfig;
import com.gauntlethelper.config.ResourceSettings;
import com.gauntlethelper.plan.Instructions;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.awt.Color;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.overlay.infobox.InfoBox;

/** An infobox showing one resource's held count against its requirement. */
public class TrackerInfoBox extends InfoBox
{
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	private final Requirement resource;

	public TrackerInfoBox(BufferedImage image, GauntletHelperPlugin plugin, GauntletHelperConfig config, Requirement resource)
	{
		super(image, plugin);
		this.plugin = plugin;
		this.config = config;
		this.resource = resource;
	}

	public void refresh()
	{
		Requirements plan = plugin.getPlan();
		if (plan != null)
		{
			setTooltip(Instructions.getLabel(plan, resource) + ": " + plan.getRow(resource).getText() + " (held / current requirement)");
		}
	}

	@Override
	public boolean render()
	{
		Requirements plan = plugin.getPlan();
		return plan != null && ResourceSettings.getDisplay(config, resource).isInfobox()
				&& (!config.hideCompletedTrackers() || !plan.getRow(resource).isComplete());
	}

	@Override
	public String getName() { return "Gauntlet Helper: " + resource.getLabel(); }
	@Override
	public String getText()
	{
		Requirements plan = plugin.getPlan();
		return plan == null ? "" : String.valueOf(plan.getRow(resource).getMissing());
	}

	@Override
	public Color getTextColor()
	{
		Requirements plan = plugin.getPlan();
		return plan != null && plan.getRow(resource).isComplete() ? Color.GREEN : config.trackerColor();
	}
}
