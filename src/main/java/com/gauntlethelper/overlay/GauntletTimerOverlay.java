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
import com.gauntlethelper.game.GauntletMode;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * The Gauntlet plugin's timer overlay, without its chat timer: elapsed preparation time, then a grayed preparation
 * time and a running total once Hunllef's fight starts, and "Time left:" while the game's own Time Left box is
 * hidden (GauntletTimeLeft). Leaving resets it. Its priority puts it at the top of the snapped top-left stack.
 * <p>Times count game ticks, 0.6 s each, because the game times the Gauntlet and its combat achievements in ticks;
 * wall-clock seconds drift from them, so the two lines could add up to more than the prep length.
 * While Time left is known the entry tick is anchored to it (GauntletMode.getPrepTicks: 750 ticks of prep in the
 * Corrupted Gauntlet, 1000 in the regular one), so the two lines always add up to 7:30 or 10:00; before that
 * (enabled mid-run) counting starts at the first prep tick.
 */
@Singleton
public class GauntletTimerOverlay extends OverlayPanel
{
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	// Game ticks: the entry tick and the tick Hunllef's fight started (-1 until then).
	private long gauntletStart = -1;
	private long hunllefStart = -1;
	private GauntletMode mode = GauntletMode.CORRUPTED;

	@Inject
	public GauntletTimerOverlay(GauntletHelperPlugin plugin, GauntletHelperConfig config)
	{
		super(plugin);
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setMovable(true);
		setSnappable(true);
		setPriority(PRIORITY_HIGHEST);
	}

	/**
	 * Called every game tick with the plugin's inPrep and inBossFight state and AttemptClock's prep ticks left (-1 when
	 * unknown).
	 */
	public void update(boolean inPrep, boolean inBossFight, long tick, int ticksLeft)
	{
		update(inPrep, inBossFight, tick, ticksLeft, GauntletMode.CORRUPTED);
	}

	/** As above in a given Gauntlet, whose prep length (GauntletMode.getPrepTicks) anchors the entry tick. */
	public void update(boolean inPrep, boolean inBossFight, long tick, int ticksLeft, GauntletMode mode)
	{
		this.mode = mode == null ? GauntletMode.CORRUPTED : mode;
		if (!inPrep && !inBossFight)
		{
			gauntletStart = -1;
			hunllefStart = -1;
			return;
		}
		if (gauntletStart == -1)
			gauntletStart = tick;
		// At 0 the count has stopped, so it no longer says when prep began.
		if (!inBossFight && ticksLeft > 0)
			gauntletStart = tick - (this.mode.getPrepTicks() - ticksLeft);
		if (inBossFight && hunllefStart == -1)
			hunllefStart = tick;
	}

	long getGauntletStart() { return gauntletStart; }

	long getHunllefStart() { return hunllefStart; }

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTimersOverlay() || gauntletStart == -1)
			return null;
		panelComponent.getChildren().add(TitleComponent.builder().text("Gauntlet Timer").build());
		for (String[] line : buildLines(gauntletStart, hunllefStart, plugin.getGameTick(), plugin.getSecondsRemaining(), mode))
		{
			Color color = line.length > 2 ? Color.LIGHT_GRAY : Color.WHITE;
			panelComponent.getChildren().add(LineComponent.builder().left(line[0]).right(line[1]).leftColor(color).rightColor(color).build());
		}
		return super.render(graphics);
	}

	/**
	 * The timer's lines as {left, right}, with a third "gray" entry on the grayed preparation time once Hunllef starts.
	 * Starts and currentTick are game ticks. Time left (secondsLeft, -1 when unknown) shows only during prep, and Preparation is then
	 * 7:30 minus it. Once Hunllef starts: grayed Preparation, Boss (ticks since the fight began) and Total.
	 */
	static List<String[]> buildLines(long gauntletStart, long hunllefStart, long currentTick, int secondsLeft)
	{
		return buildLines(gauntletStart, hunllefStart, currentTick, secondsLeft, GauntletMode.CORRUPTED);
	}

	/** As above in a given Gauntlet: Preparation is its prep length (7:30 or 10:00) minus Time left. */
	static List<String[]> buildLines(long gauntletStart, long hunllefStart, long currentTick, int secondsLeft, GauntletMode mode)
	{
		List<String[]> lines = new ArrayList<>();
		if (hunllefStart == -1)
		{
			long prepSeconds = secondsLeft >= 0 ? mode.getPrepTicks() * 3 / 5 - secondsLeft : toSeconds(currentTick - gauntletStart);
			lines.add(new String[]{"Preparation:", format(prepSeconds)});
			if (secondsLeft >= 0)
				lines.add(new String[]{"Time left:", format(secondsLeft)});
		}
		else
		{
			lines.add(new String[]{"Preparation:", format(toSeconds(hunllefStart - gauntletStart)), "gray"});
			lines.add(new String[]{"Boss:", format(toSeconds(currentTick - hunllefStart))});
			lines.add(new String[]{"Total:", format(toSeconds(currentTick - gauntletStart))});
		}
		return lines;
	}

	/** Whole seconds in a tick count, 0.6 s per tick, rounded down. */
	static long toSeconds(long ticks)
	{
		return Math.max(0, ticks) * 3 / 5;
	}

	/** True while the game's Time Left box should be hidden: its setting is on and the player is in the Gauntlet. */
	public static boolean shouldHideTimeLeft(GauntletHelperConfig config, boolean inPrep, boolean inBossFight)
	{
		return config.hideTimeLeftBox() && (inPrep || inBossFight);
	}

	/** m:ss, as the reference formats it. */
	static String format(long totalSeconds)
	{
		long seconds = Math.max(0, totalSeconds);
		return String.format("%01d:%02d", seconds % 3600 / 60, seconds % 60);
	}
}
