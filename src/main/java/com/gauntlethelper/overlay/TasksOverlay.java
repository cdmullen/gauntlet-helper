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
import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.plan.Instructions;
import com.gauntlethelper.plan.Requirement;
import com.gauntlethelper.plan.Requirements;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.NPC;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** The prep panel: the current and next task and the requirement rows. */
public class TasksOverlay extends OverlayPanel
{
	private final GauntletHelperConfig config;
	private final GauntletHelperPlugin plugin;
	private final Guidance guidance;

	@Inject
	public TasksOverlay(GauntletHelperConfig config, GauntletHelperPlugin plugin, Guidance guidance)
	{
		this.config = config;
		this.plugin = plugin;
		this.guidance = guidance;
		setPosition(OverlayPosition.TOP_LEFT);
		// A size saved by resizing the panel still wins over this default.
		panelComponent.setPreferredSize(new Dimension(220, 0));
	}

	static String buildTitle(Requirements plan)
	{
		return "Gauntlet Helper (" + Instructions.getLoadoutName(plan) + ")";
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		panelComponent.getChildren().clear();
		if (plugin.isInBossFight())
		{
			return null;
		}
		if (plugin.getPlan() == null || !config.showTasksOverlay())
		{
			return null;
		}

		Requirements plan = plugin.getPlan();
		panelComponent.getChildren().add(TitleComponent.builder().text(buildTitle(plan)).color(Color.WHITE).build());
		boolean guidanceAllowed = plugin.arePrepCuesAllowed();
		if (guidanceAllowed)
		{
			String status = plugin.getPhaseLabel();
			addLine("Status: " + status, Color.LIGHT_GRAY);
			addLine("Current: " + stripStatus(plugin.getInstruction(), status), config.currentActionColor());
			// Panel echo of the side kill cue while gathering; same gates as the clickbox.
			NPC side = guidance.getSideEnemyTarget();
			if (side != null && !side.isDead() && config.highlightTargetedEnemies() && !guidance.shouldSuppressEnemyMarkers())
				addLine("Additional: Defeat " + EnemyType.fromNpcId(side.getId()).getLabel(),
						config.currentActionColor());
		}
		else
		{
			addLine("Resource checklist", Color.LIGHT_GRAY);
		}
		if (guidanceAllowed && config.showNextTask())
		{
			Instructions text = plugin.getPlanInstructions();
			String next = plugin.getSecondaryInstruction();
			if (next == null && plan.isReady())
				next = plugin.getInstruction().equals(text.getInstruction()) ? text.getNext() : "Fight Hunllef";
			else if (next == null)
				next = buildFallbackNext(plugin.getInstruction(), plugin.getInstruction().equals(text.getInstruction())
						? text.getNext() : text.getInstruction(), guidance.isOpeningRouteActive(), Guidance.DEMI_HUNTING.equals(plugin.getPhaseLabel()));
			if (next != null)
				addLine("Next: " + next, config.nextActionColor());
		}
		// The time left itself is shown by the game's Time Left box or the Gauntlet timer, never here; the hint's own
		// gates (expiring stored fish, or time low with Time-aware return guidance) are in getTimeHint.
		String hint = plugin.getTimeHint();
		if (hint != null)
			for (String part : hint.split("\n"))
				addLine(part, config.nextActionColor());
		// A needed node without a path warns.
		String route = guidanceAllowed ? plugin.getRouteWarning() : null;
		if (route != null)
			addLine(route, Color.LIGHT_GRAY);
		if (Instructions.getFoodCapNotice(plan) != null)
			addLine(Instructions.getFoodCapNotice(plan), Color.LIGHT_GRAY);
		for (Requirement resource : Requirement.values())
		{
			Requirements.Row row = plan.getRow(resource);
			if (!ResourceSettings.getDisplay(config, resource).isOverlay() || config.hideCompletedTrackers() && row.isComplete())
			{
				continue;
			}
			panelComponent.getChildren().add(LineComponent.builder().left(Instructions.getLabel(plan, resource))
					.right(row.getText()).rightColor(row.isComplete() ? Color.GREEN
					: config.trackerColor()).build());
		}
		return super.render(graphics);
	}

	/**
	 * The plan's to-do list doesn't know the map, so a gather/catch/find task is only a guess until the scene has seen
	 * that resource. During the opening loop it becomes a loop cue; elsewhere it is hidden. While Now is lighting a
	 * room, nothing beyond it is known yet. During the demi hunt no plan step shows: the list is in priority order, not
	 * route order, so a home craft would read as coming before the demis still to kill.
	 */
	public static String buildFallbackNext(String nowText, String planStep, boolean openingLoop, boolean demiHunt)
	{
		if ("Light room".equals(nowText))
			return "Waiting to light room";
		if (planStep == null || demiHunt)
			return null;
		if (planStep.startsWith("Gather ") || planStep.startsWith("Catch ") || planStep.startsWith("Find "))
			return openingLoop ? "Continue the opening loop" : null;
		return planStep;
	}

	/**
	 * Drops a Now prefix that only repeats the Status line above it, e.g. "Demi hunting: " under "Status: Demi hunting"
	 * or "Opening loop 3/5: " under "Status: First loop (3/5)".
	 */
	static String stripStatus(String nowText, String status)
	{
		if (nowText == null || status == null)
			return nowText;
		String rest;
		if (status.equals(Guidance.DEMI_HUNTING))
			rest = stripPrefix(nowText, "Demi hunting: ", "Demi search: ");
		else if (status.startsWith("First loop"))
			rest = stripPrefix(nowText, "Opening loop: ", "Opening loop " + status.replaceAll("\\D+", " ").trim().replace(' ', '/') + ": ");
		else
			rest = stripPrefix(nowText, status + ": ");
		if (rest == null || rest.isEmpty())
			return nowText;
		return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
	}

	private static String stripPrefix(String text, String... prefixes)
	{
		for (String prefix : prefixes)
			if (text.regionMatches(true, 0, prefix, 0, prefix.length()))
				return text.substring(prefix.length());
		return null;
	}

	private void addLine(String text, Color color)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(color).build());
	}
}
