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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.plugins.grounditems.GroundItemsConfig;
import net.runelite.client.util.Text;
import net.runelite.client.util.WildcardMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only view of the RuneLite Ground Items settings that decide which name lines it stacks on a tile.
 */
public class GroundItemsSettings
{
	/** Ground Items' line spacing (GroundItemsOverlay.STRING_GAP). */
	static final int LINE_GAP = 15;
	private static final Pattern THRESHOLD = Pattern.compile("^(.+?)\\s*([<>])\\s*(\\d{1,9})$");
	private static final Logger log = LoggerFactory.getLogger(GroundItemsSettings.class);

	private final BooleanSupplier enabled;
	private final GroundItemsConfig config;
	private boolean unreadable;
	private String hiddenSource, highlightSource;
	private List<String> hidden = new ArrayList<>(), highlighted = new ArrayList<>();

	GroundItemsSettings(BooleanSupplier enabled, GroundItemsConfig config)
	{
		this.enabled = enabled;
		this.config = config;
	}

	/** Settings for when Ground Items can't be read: it counts as off, so the helper stacks its own names. */
	static GroundItemsSettings disabled() { return new GroundItemsSettings(() -> false, null); }

	public boolean isEnabled() { return !unreadable && enabled.getAsBoolean(); }

	/**
	 * Whether Ground Items draws a name line (and so takes a stack row) for this item. A client whose Ground Items config
	 * no longer has these settings counts as Ground Items off from then on.
	 */
	public boolean isShown(String name, int quantity)
	{
		if (config == null || unreadable)
			return false;
		try
		{
			return readShown(name, quantity);
		}
		catch (LinkageError e)
		{
			log.debug("Ground Items settings unreadable; drawing needed names above the stack", e);
			unreadable = true;
			return false;
		}
	}

	private boolean readShown(String name, int quantity)
	{
		String hiddenItems = config.getHiddenItems();
		String highlightItems = config.getHighlightItems();
		if (!isSame(hiddenItems, hiddenSource))
		{
			hiddenSource = hiddenItems;
			hidden = Text.fromCSV(hiddenItems == null ? "" : hiddenItems);
		}
		if (!isSame(highlightItems, highlightSource))
		{
			highlightSource = highlightItems;
			highlighted = Text.fromCSV(highlightItems == null ? "" : highlightItems);
		}
		if (matches(highlighted, name, quantity))
			return true;
		return !config.showHighlightedOnly() && !matches(hidden, name, quantity);
	}

	private static boolean isSame(String a, String b) { return Objects.equals(a, b); }

	private static boolean matches(List<String> patterns, String name, int quantity)
	{
		for (String pattern : patterns)
		{
			Matcher threshold = THRESHOLD.matcher(pattern);
			if (!threshold.matches())
			{
				if (WildcardMatcher.matches(pattern, name))
					return true;
				continue;
			}
			int limit = Integer.parseInt(threshold.group(3));
			boolean holds = threshold.group(2).equals(">") ? quantity > limit : quantity < limit;
			if (holds && WildcardMatcher.matches(threshold.group(1), name))
				return true;
		}
		return false;
	}
}
