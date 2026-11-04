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

import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.record.AttemptFrame;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.GameObject;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-log diagnostics for Guidance (the spawn geometry at initialization and opening-loop state changes) and
 * the guidance descriptions the attempt recorder writes.
 */
final class GuidanceLog
{
	// Logs under Guidance's name, so client-log greps and filters find every guidance line under one logger.
	private static final Logger log = LoggerFactory.getLogger(Guidance.class);
	private final Guidance guidance;
	private final GuidanceState targeting;
	private String loggedOpening;

	/** One client-log line per opening-loop state change (init, side, step, end), for live-run diagnosis. */
	private String loggedInit;

	GuidanceLog(Guidance guidance, GuidanceState targeting)
	{
		this.guidance = guidance;
		this.targeting = targeting;
	}

	/** Guidance's share of an attempt reset: log every state again. */
	void reset()
	{
		loggedOpening = null;
		loggedInit = null;
	}

	/**
	 * Raw spawn geometry for diagnosing the room grid: every Light/passage object, the bowl, player and map varbits.
	 */
	void logInitialization(Player player, TileObject bowl, WorldPoint home, int startRoom, int column, int row)
	{
		if (!log.isDebugEnabled())
			return;
		List<String> entries = new ArrayList<>();
		for (Map.Entry<TileObject, ObjectKind> entry : guidance.objects.entrySet())
		{
			if (!entry.getValue().isDoorway())
				continue;
			TileObject object = entry.getKey();
			WorldPoint at = object.getWorldLocation();
			if (at.getPlane() != home.getPlane() || at.distanceTo2D(home) > 40)
				continue;
			String extent = "";
			if (object instanceof GameObject)
			{
				GameObject gameObject = (GameObject) object;
				extent = " scene=" + gameObject.getSceneMinLocation().getX() + "," + gameObject.getSceneMinLocation().getY() + "-"
						+ gameObject.getSceneMaxLocation().getX() + "," + gameObject.getSceneMaxLocation().getY();
			}
			entries.add(object.getClass().getSimpleName() + "#" + object.getId() + "@" + at.getX() + "," + at.getY()
					+ extent + (guidance.isOpenPassage(object, entry.getValue()) ? " lit" : ""));
		}
		Collections.sort(entries);
		String key = entries + "|" + guidance.opening.isInitialized();
		if (key.equals(loggedInit))
			return;
		loggedInit = key;
		StringBuilder doors = new StringBuilder();
		for (String e : entries)
			doors.append("\n    ").append(e);
		WorldPoint at = player.getWorldLocation();
		log.debug("Gauntlet opening init: start={} mapRoom=({},{}) player={},{} bowl={} home={},{} -> {} "
				+ "doors within 40 ({}):{}",
				startRoom, column, row, at.getX(), at.getY(), bowl == null ? "none" : bowl.getWorldLocation().getX()
				+ "," + bowl.getWorldLocation().getY(),
				home.getX(), home.getY(), guidance.opening.describe(), entries.size(), doors);
	}

	/** The routed task's target, as written in the attempt recording. */
	private String describeTarget()
	{
		return targeting.enemyTarget != null ? "npc#" + targeting.enemyTarget.getId() + "@"
				+ formatWorld(targeting.enemyTarget.getWorldLocation())
				: targeting.groundTarget != null ? "loot@" + formatWorld(targeting.groundTarget)
				: targeting.target != null ? targeting.targetKind + "#" + targeting.target.getId() + "@"
				+ formatWorld(targeting.target.getWorldLocation())
				: targeting.targetKind != null ? String.valueOf(targeting.targetKind) : "none";
	}

	private String describePathEnd(WorldView view)
	{
		if (targeting.path.isEmpty())
			return "none";
		Point last = targeting.path.get(targeting.path.size() - 1);
		WorldPoint endPoint = new WorldPoint(view.getBaseX() + last.getX(), view.getBaseY() + last.getY(), view.getPlane());
		return formatWorld(endPoint) + " " + guidance.opening.getRoomAt(endPoint) + " (" + (targeting.path.size() - 1) + " tiles)";
	}

	/** The Next cue's target, written like describeTarget(): a visible next demi as "npc#id@x,y"; null when none. */
	private String describeNextTarget()
	{
		return targeting.nextEnemy != null ? "npc#" + targeting.nextEnemy.getId() + "@"
				+ formatWorld(targeting.nextEnemy.getWorldLocation())
				: targeting.nextTarget == null ? null
				: targeting.nextKind + "@" + formatWorld(targeting.nextTarget.getWorldLocation());
	}

	/** The attempt recorder's guidance fields: written every tick. */
	void describe(AttemptFrame frame)
	{
		WorldView view = guidance.client == null ? null : guidance.client.getTopLevelWorldView();
		boolean ready = view != null && guidance.opening.isInitialized();
		frame.put("target", ready ? describeTarget() : null);
		frame.put("pathEnd", ready ? describePathEnd(view) : null);
		frame.put("nextTarget", ready ? describeNextTarget() : null);
		frame.put("side", targeting.sideEnemyTarget == null ? null : "npc#" + targeting.sideEnemyTarget.getId() + "@"
				+ formatWorld(targeting.sideEnemyTarget.getWorldLocation()));
		frame.put("loopStep", guidance.opening.isInitialized() ? guidance.opening.describe() : null);
		frame.put("roomRoute", targeting.roomRouteSummary);
	}

	private String formatWorld(WorldPoint p)
	{
		return p == null ? "?" : p.getX() + "," + p.getY() + (guidance.opening.isInitialized() ? " " + guidance.opening.getRoomAt(p) : "");
	}

	void logOpening(Player player)
	{
		if (!log.isDebugEnabled())
			return;
		String state = guidance.opening.describe();
		if (state.equals(loggedOpening))
			return;
		WorldPoint at = player == null ? null : player.getWorldLocation();
		log.debug("Gauntlet opening loop: {}{} | player={} room={} start={}", state, guidance.opening.getEndReason() == null ? ""
				: " ("
				+ guidance.opening.getEndReason() + ")",
				at == null ? "?" : at.getX() + "," + at.getY(), at == null || !guidance.opening.isInitialized() ? "?"
				: guidance.opening.getRoomAt(at), guidance.orientation.openingStartRoom);
		loggedOpening = state;
	}
}
