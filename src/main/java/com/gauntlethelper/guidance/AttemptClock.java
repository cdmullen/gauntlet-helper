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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game's remaining prep time. Unknown stays unknown, including mid-run enabling. The game's Time Left box counts
 * down in game ticks: it shows 0.6 s times the ticks left, rounded down (GauntletMode.getPrepTicks at entry: 750
 * ticks, 7:30, in the Corrupted Gauntlet, 1000, 10:00, in the regular one). The box's text stops updating while it is
 * hidden, so the first reading sets the ticks left and each tick counts one down; a reading the count doesn't
 * predict resets the count.
 */
public final class AttemptClock
{
	private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})");

	private int anchorTicksLeft = -1;
	private int anchorTick;
	private int tick;
	private String lastText;
	private boolean visible;

	public void reset()
	{
		anchorTicksLeft = -1;
		lastText = null;
		visible = false;
	}

	/** One game tick's reading of the box's text (null while the game doesn't show it). */
	public void observe(String text, int tick)
	{
		this.tick = tick;
		int read = parse(text);
		visible = read >= 0;
		if (!visible)
		{
			lastText = null;
			return;
		}
		if (anchorTicksLeft < 0)
		{
			// Fewest ticks that show this time: exact at entry, the box's own first reading.
			anchorTicksLeft = (read * 5 + 2) / 3;
			anchorTick = tick;
		}
		else if (!text.equals(lastText) && read != predictSeconds())
		{
			// The text just changed, so this is the most ticks that show this time.
			anchorTicksLeft = (read * 5 + 5 + 2) / 3 - 1;
			anchorTick = tick;
		}
		lastText = text;
	}

	public int getSeconds() { return visible ? predictSeconds() : -1; }

	/** Prep ticks left, -1 while unknown; the Gauntlet timer anchors its Preparation count to it. */
	public int getTicksLeft() { return visible ? countTicks() : -1; }

	public boolean isUrgent(int threshold)
	{
		int secondsLeft = getSeconds();
		return secondsLeft >= 0 && secondsLeft <= threshold;
	}

	/** 0.6 s per tick left, rounded down as the game shows it. */
	private int predictSeconds()
	{
		return countTicks() * 3 / 5;
	}

	private int countTicks()
	{
		return Math.max(0, anchorTicksLeft - Math.max(0, tick - anchorTick));
	}

	private static int parse(String text)
	{
		if (text == null)
			return -1;
		Matcher match = TIME.matcher(text.replaceAll("<[^>]*>", ""));
		if (!match.find())
			return -1;
		int seconds = Integer.parseInt(match.group(2));
		return seconds < 60 ? Integer.parseInt(match.group(1)) * 60 + seconds : -1;
	}
}
