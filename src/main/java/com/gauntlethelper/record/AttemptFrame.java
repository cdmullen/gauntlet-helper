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

package com.gauntlethelper.record;

import com.gauntlethelper.config.GauntletHelperConfig;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * One tick of attempt-recorder data, built on the client thread from plain values. Values are compared with the
 * previous frame and written only when they change; sets are written as additions and removals.
 */
public final class AttemptFrame
{
	private static final String DELTA = "=";
	private static final String SAME = ".";
	final int tick;
	public final Map<String, Object> values = new LinkedHashMap<>();
	public final Map<String, TreeSet<String>> sets = new LinkedHashMap<>();

	public AttemptFrame(int tick)
	{
		this.tick = tick;
	}

	/** A scalar or list value (String, Number, Boolean or List of those). Null removes the key from the record. */
	public AttemptFrame put(String key, Object value)
	{
		values.put(key, value);
		return this;
	}

	/**
	 * An unordered set of observation strings, recorded as additions/removals against the previous frame. Identical
	 * members (cooked fish dropped together share id, tile and despawn tick) become one member with " n=count".
	 */
	public AttemptFrame set(String key, Collection<String> members)
	{
		Map<String, Integer> counts = new TreeMap<>();
		for (String member : members)
			counts.merge(member, 1, Integer::sum);
		TreeSet<String> set = new TreeSet<>();
		for (Map.Entry<String, Integer> entry : counts.entrySet())
			set.add(entry.getValue() == 1 ? entry.getKey() : entry.getKey() + " n=" + entry.getValue());
		sets.put(key, set);
		return this;
	}

	/**
	 * Collision flags as "WxH:" then "flags*count" runs, x-major (every y of column 0, then column 1, ...). A
	 * scene of mostly open or blocked floor shrinks to a few hundred runs.
	 */
	public static String encodeRunLength(int[][] flags)
	{
		StringBuilder text = new StringBuilder();
		text.append(flags.length).append('x').append(flags.length == 0 ? 0 : flags[0].length).append(':');
		int run = 0;
		int value = 0;
		for (int[] column : flags)
		{
			for (int flag : column)
			{
				if (run > 0 && flag == value)
				{
					run++;
					continue;
				}
				if (run > 0)
				{
					text.append(value).append('*').append(run).append(',');
				}
				value = flag;
				run = 1;
			}
		}
		if (run > 0)
		{
			text.append(value).append('*').append(run);
		}
		return text.toString();
	}

	/** The flags {@link #encodeRunLength} wrote, as the x-major grid. */
	public static int[][] decodeRunLength(String text)
	{
		int colon = text.indexOf(':');
		String[] size = text.substring(0, colon).split("x");
		int width = Integer.parseInt(size[0]);
		int height = Integer.parseInt(size[1]);
		int[][] flags = new int[width][height];
		int cell = 0;
		for (String run : text.substring(colon + 1).split(","))
		{
			if (run.isEmpty())
				continue;
			int star = run.indexOf('*');
			int value = Integer.parseInt(run.substring(0, star));
			int count = Integer.parseInt(run.substring(star + 1));
			for (int i = 0; i < count; i++, cell++)
				flags[cell / height][cell % height] = value;
		}
		return flags;
	}

	/**
	 * A run-length map as the changes from the previous one: "=dx,dy:" then runs of "value*count" or ".*count",
	 * a dot meaning the flags the previous map holds dx,dy cells away (the scene base moved by that much, so it is
	 * the same world tile). Null when the maps differ in size, the current map isn't in {@link #encodeRunLength}'s
	 * own form (so a reader could not rebuild it exactly) or the full map is no longer.
	 */
	public static String encodeDelta(String previous, String current, int dx, int dy)
	{
		int[][] before = decodeRunLength(previous);
		int[][] now = decodeRunLength(current);
		if (before.length != now.length || before.length > 0 && before[0].length != now[0].length || !encodeRunLength(now).equals(current))
			return null;
		StringBuilder text = new StringBuilder().append(DELTA).append(dx).append(',').append(dy).append(':');
		int run = 0;
		String value = null;
		int height = now.length == 0 ? 0 : now[0].length;
		for (int x = 0; x < now.length; x++)
		{
			for (int y = 0; y < height; y++)
			{
				int fromX = x + dx;
				int fromY = y + dy;
				boolean same = fromX >= 0 && fromY >= 0 && fromX < now.length && fromY < height && before[fromX][fromY] == now[x][y];
				String token = same ? SAME : Integer.toString(now[x][y]);
				if (run > 0 && token.equals(value))
				{
					run++;
					continue;
				}
				if (run > 0)
					text.append(value).append('*').append(run).append(',');
				value = token;
				run = 1;
			}
		}
		if (run > 0)
			text.append(value).append('*').append(run);
		return text.length() < current.length() ? text.toString() : null;
	}

	/** Whether a map value is a delta from {@link #encodeDelta} rather than a full map. */
	public static boolean isDelta(String value)
	{
		return value.startsWith(DELTA);
	}

	/** The full map a delta from {@link #encodeDelta} describes, given the previous map it was written against. */
	public static String applyDelta(String previous, String delta)
	{
		int colon = delta.indexOf(':');
		String[] shift = delta.substring(DELTA.length(), colon).split(",");
		int dx = Integer.parseInt(shift[0]);
		int dy = Integer.parseInt(shift[1]);
		int[][] before = decodeRunLength(previous);
		int height = before.length == 0 ? 0 : before[0].length;
		int[][] now = new int[before.length][height];
		int cell = 0;
		for (String run : delta.substring(colon + 1).split(","))
		{
			if (run.isEmpty())
				continue;
			int star = run.indexOf('*');
			String token = run.substring(0, star);
			int count = Integer.parseInt(run.substring(star + 1));
			for (int i = 0; i < count; i++, cell++)
			{
				int x = cell / height;
				int y = cell % height;
				now[x][y] = token.equals(SAME) ? before[x + dx][y + dy] : Integer.parseInt(token);
			}
		}
		return encodeRunLength(now);
	}

	/**
	 * Every setting as ConfigManager stores it (colors as ARGB integers, enums by name), keyed by keyName. Each
	 * setting is listed by hand, so a new setting needs a line here.
	 */
	public static Map<String, String> readConfigValues(GauntletHelperConfig config)
	{
		Map<String, String> values = new TreeMap<>();
		add(values, "weaponPair", config::weaponPair);
		add(values, "armorGoal", config::armorGoal);
		add(values, "paddlefishTarget", config::paddlefishTarget);
		add(values, "grymLeafTarget", config::grymLeafTarget);
		add(values, "showTasksOverlay", config::showTasksOverlay);
		add(values, "showNextTask", config::showNextTask);
		add(values, "showTaskCaptions", config::showTaskCaptions);
		add(values, "highlightTasksClickboxes", config::highlightTasksClickboxes);
		add(values, "useTimeAwareReturnGuidance", config::useTimeAwareReturnGuidance);
		add(values, "returnWarningSeconds", config::returnWarningSeconds);
		add(values, "showTimersOverlay", config::showTimersOverlay);
		add(values, "hideTimeLeftBox", config::hideTimeLeftBox);
		add(values, "highlightResourceNodes", config::highlightResourceNodes);
		add(values, "hideAfterResourceTarget", config::hideAfterResourceTarget);
		add(values, "highlightTargetedEnemies", config::highlightTargetedEnemies);
		add(values, "highlightEnemiesTrueTiles", config::highlightEnemiesTrueTiles);
		add(values, "highlightTargetedDemiBossesClickboxes", config::highlightTargetedDemiBossesClickboxes);
		add(values, "highlightDemiBossesTrueTiles", config::highlightDemiBossesTrueTiles);
		add(values, "darkBeastColor", config::darkBeastColor);
		add(values, "dragonColor", config::dragonColor);
		add(values, "bearColor", config::bearColor);
		add(values, "highlightInventoryActions", config::highlightInventoryActions);
		add(values, "highlightDroppableItems", config::highlightDroppableItems);
		add(values, "highlightSingingBowlCrafting", config::highlightSingingBowlCrafting);
		add(values, "showKeysLabelForSingingBowlRecipes", config::showKeysLabelForSingingBowlRecipes);
		add(values, "highlightNeededGroundItems", config::highlightNeededGroundItems);
		add(values, "reorderGroundItems", config::reorderGroundItems);
		add(values, "showMinimapMarkers", config::showMinimapMarkers);
		add(values, "hideCompletedResourceMinimapIcons", config::hideCompletedResourceMinimapIcons);
		add(values, "showMinimapRouteLine", config::showMinimapRouteLine);
		add(values, "showDemiBossMinimapIcons", config::showDemiBossMinimapIcons);
		add(values, "oreTracker", config::oreTracker);
		add(values, "barkTracker", config::barkTracker);
		add(values, "linumTirinumTracker", config::linumTirinumTracker);
		add(values, "shardsTracker", config::shardsTracker);
		add(values, "framesTracker", config::framesTracker);
		add(values, "upgradesTracker", config::upgradesTracker);
		add(values, "grymLeavesTracker", config::grymLeavesTracker);
		add(values, "fishTracker", config::fishTracker);
		add(values, "hideCompletedTrackers", config::hideCompletedTrackers);
		add(values, "currentActionColor", config::currentActionColor);
		add(values, "nextActionColor", config::nextActionColor);
		add(values, "trackerColor", config::trackerColor);
		add(values, "dropSquareColor", config::dropSquareColor);
		add(values, "equipOutlineColor", config::equipOutlineColor);
		add(values, "fillOutlineColor", config::fillOutlineColor);
		add(values, "mixOutlineColor", config::mixOutlineColor);
		add(values, "grindOutlineColor", config::grindOutlineColor);
		add(values, "oreOutlineColor", config::oreOutlineColor);
		add(values, "phrenRootsOutlineColor", config::phrenRootsOutlineColor);
		add(values, "linumTirinumOutlineColor", config::linumTirinumOutlineColor);
		add(values, "grymRootOutlineColor", config::grymRootOutlineColor);
		add(values, "fishingSpotOutlineColor", config::fishingSpotOutlineColor);
		add(values, "blockHunllefEntryWithRawFish", config::blockHunllefEntryWithRawFish);
		add(values, "outlineHunllefTile", config::outlineHunllefTile);
		add(values, "colorTileByPrayer", config::colorTileByPrayer);
		add(values, "hunllefTileOutlineWidth", config::hunllefTileOutlineWidth);
		add(values, "hunllefOutlineColor", config::hunllefOutlineColor);
		add(values, "hunllefFillColor", config::hunllefFillColor);
		add(values, "outlineTornadoTile", config::outlineTornadoTile);
		add(values, "tornadoTileOutlineWidth", config::tornadoTileOutlineWidth);
		add(values, "tornadoOutlineColor", config::tornadoOutlineColor);
		add(values, "tornadoFillColor", config::tornadoFillColor);
		add(values, "showHunllefRoomTileMarkers", config::showHunllefRoomTileMarkers);
		add(values, "hunllefRoomTileMarkerColor", config::hunllefRoomTileMarkerColor);
		add(values, "prayerDisableProjectile", config::prayerDisableProjectile);
		add(values, "logAttempts", config::logAttempts);
		add(values, "keepRecordings", config::keepRecordings);
		return values;
	}

	private static void add(Map<String, String> values, String key, Supplier<Object> setting)
	{
		try
		{
			Object value = setting.get();
			if (value instanceof Color)
			{
				values.put(key, Integer.toString(((Color) value).getRGB()));
			}
			else if (value instanceof Enum)
			{
				values.put(key, ((Enum<?>) value).name());
			}
			else if (value != null)
			{
				values.put(key, value.toString());
			}
		}
		catch (RuntimeException e)
		{
			values.put(key, "?");
		}
	}

	/** List helper for small integer tuples such as a world point. */
	public static List<Integer> ints(int... values)
	{
		List<Integer> result = new ArrayList<>(values.length);
		for (int value : values)
		{
			result.add(value);
		}
		return result;
	}
}
