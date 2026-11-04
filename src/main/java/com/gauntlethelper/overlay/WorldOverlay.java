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
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.guidance.EnemyRules;
import com.gauntlethelper.guidance.Guidance;
import com.gauntlethelper.plan.HomeCraftPlan;
import com.gauntlethelper.plan.HomeVisit;
import com.gauntlethelper.plan.Requirement;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ColorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Draws the guidance in the game world: the task's path, object and enemy highlights, true tiles and ground items. */
public class WorldOverlay extends Overlay
{
	/**
	 * Needed-demi current cue: a 2px stroke, so it stands out on the large demi model next to the type-colored true
	 * tile of Demi-bosses true tiles. The Next demi and small mobs stay 1px.
	 */
	public static final int DEMI_STROKE = 2;
	/** Demi clickboxes are outline-only: a fill tints the large model and its tiles. */
	public static final int DEMI_FILL_ALPHA = 0;
	private static final Logger log = LoggerFactory.getLogger(WorldOverlay.class);
	/** NPCs whose last geometry read threw and has been logged; one line per NPC, however many frames it lasts. */
	private static final Set<NPC> UNREADABLE_NPCS = Collections.newSetFromMap(new WeakHashMap<>());

	private final Client client;
	private final GauntletHelperPlugin plugin;
	private final GauntletHelperConfig config;
	private final Guidance guidance;

	@Inject
	public WorldOverlay(Client client, GauntletHelperPlugin plugin, GauntletHelperConfig config, Guidance guidance)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.guidance = guidance;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!plugin.arePrepCuesAllowed())
		{
			return null;
		}
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		boolean teleportSuggested = plugin.isTeleportSuggested();
		NPC enemy = guidance.getEnemyTarget();
		boolean objectiveVisible = !teleportSuggested && !guidance.shouldSuppressEnemyMarkers()
				&& config.highlightTargetedEnemies()
				&& enemy != null && !enemy.isDead() && EnemyRules.isEligible(enemy.getId(), plugin.getPlan());
		if (config.highlightEnemiesTrueTiles() && view.npcs() != null)
		{
			for (NPC npc : view.npcs())
				if (EnemyRules.isOrdinary(npc.getId()) || EnemyRules.isAvoided(npc.getId()))
					renderEnemyTile(graphics, view, npc, new Color(145, 145, 145));
		}
		// Return travel hides task routes, while observed non-objective mobs retain awareness tiles.
		if (teleportSuggested)
			return null;
		// Needed demis: clickbox plus Defeat caption, no room floor border.
		// The demi that is Next (the move after the current kill) shows in the Next color with the Next caption.
		NPC nextDemi = guidance.getNextEnemy();
		if (config.highlightTargetedDemiBossesClickboxes())
			for (NPC demi : guidance.getNeededDemis())
				if (demi != nextDemi || !isNextDemiShown())
					renderEnemyClickbox(graphics, demi, config.currentActionColor(), config.showTaskCaptions() ? "Defeat" : null, DEMI_STROKE, DEMI_FILL_ALPHA);
		if (nextDemi != null && nextDemi != enemy && isNextDemiShown())
			renderNextDemi(graphics, nextDemi);
		Requirement resource = guidance.getTargetResource();
		Color color = config.currentActionColor();
		if (guidance.isSceptreRecovery())
			color = Guidance.SCEPTRE_BLUE;
		else if (resource != null)
			color = ResourceSettings.getResourceOutlineColor(config, resource);
		drawPaths(graphics, view, color);
		drawNextObject(graphics, guidance.getNextTarget());
		TileObject target = guidance.getTarget();
		if (target != null
				&& (resource == null || ResourceVisibility.isVisible(resource, plugin.getPlan(), config)
				|| ResourceVisibility.isCaptionOnly(resource, config)))
		{
			// Resource models are outlined by ResourceOverlay; keep clickboxes for doors/utilities.
			Shape clickbox = config.highlightTasksClickboxes() ? guidance.getTargetClickbox() : null;
			if (clickbox != null)
			{
				Color oldColor = graphics.getColor();
				Stroke oldStroke = graphics.getStroke();
				graphics.setColor(ColorUtil.colorWithAlpha(color, 45));
				graphics.fill(clickbox);
				graphics.setColor(color);
				graphics.setStroke(OverlayStrokes.MEDIUM);
				graphics.draw(clickbox);
				graphics.setStroke(oldStroke);
				graphics.setColor(oldColor);
			}
			String text = getWorldLabel(guidance.getSceneInstruction());
			Point label = findTargetLabel(graphics, target, resource, text);
			if (label != null)
			{
				OutlinedText.draw(graphics, label, text, color);
				drawBowlKeys(graphics, label, text, color);
			}
		}
		if (objectiveVisible)
			renderEnemyClickbox(graphics, enemy, config.currentActionColor(), config.showTaskCaptions() ? guidance.getSceneInstruction() : null);
		// Side kill cue while gathering: same teal clickbox and Defeat caption, no route.
		NPC side = guidance.getSideEnemyTarget();
		if (side != null && side != enemy && !side.isDead() && !guidance.shouldSuppressEnemyMarkers()
				&& config.highlightTargetedEnemies()
				&& EnemyRules.isEligible(side.getId(), plugin.getPlan()))
			renderEnemyClickbox(graphics, side, config.currentActionColor(), config.showTaskCaptions() ? "Defeat" : null);
		return null;
	}

	/** Where the current target's caption goes: above a resource's model, else the object's own text spot; null when hidden. */
	private Point findTargetLabel(Graphics2D graphics, TileObject target, Requirement resource, String text)
	{
		if (!config.showTaskCaptions() || guidance.isEnteringOpeningRoom() || text == null)
			return null;
		if (resource != null)
			return OutlinedText.findAboveModel(graphics, target, text);
		return target.getCanvasTextLocation(graphics, text, OutlinedText.DEFAULT_Z_OFFSET);
	}

	private boolean isNextDemiShown()
	{
		return config.showNextTask() && (config.highlightTasksClickboxes() || config.showTaskCaptions());
	}

	/** The Next demi: the clickbox with Tasks clickboxes, the Next caption with Task captions. */
	private void renderNextDemi(Graphics2D graphics, NPC demi)
	{
		Color next = config.nextActionColor();
		String caption = config.showTaskCaptions() ? "Next: " + guidance.getNextTaskInstruction() : null;
		if (config.highlightTasksClickboxes())
		{
			renderEnemyClickbox(graphics, demi, next, caption, 1, DEMI_FILL_ALPHA);
			return;
		}
		if (demi.isDead() || caption == null)
			return;
		try
		{
			Point label = demi.getCanvasTextLocation(graphics, caption, OutlinedText.DEFAULT_Z_OFFSET);
			if (label != null)
				OutlinedText.draw(graphics, label, caption, next);
			UNREADABLE_NPCS.remove(demi);
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			noteUnreadable(demi, e);
		}
	}

	/** The Next object, with Guide → Next task: outline with Tasks clickboxes, caption with Task captions. */
	private void drawNextObject(Graphics2D graphics, TileObject secondary)
	{
		if (secondary == null || !config.showNextTask() || !config.highlightTasksClickboxes() && !config.showTaskCaptions())
			return;
		Color oldColor = graphics.getColor();
		Stroke oldStroke = graphics.getStroke();
		// The next task shares the panel's Next color.
		Color muted = config.nextActionColor();
		if (config.highlightTasksClickboxes())
		{
			Shape outline = secondary.getClickbox();
			if (outline == null && secondary.getLocalLocation() != null)
				outline = Perspective.getCanvasTilePoly(client, secondary.getLocalLocation());
			if (outline != null)
			{
				graphics.setColor(ColorUtil.colorWithAlpha(muted, 25));
				graphics.fill(outline);
				graphics.setColor(muted);
				graphics.setStroke(OverlayStrokes.THIN);
				graphics.draw(outline);
			}
		}
		if (config.showTaskCaptions())
		{
			// With node coloring off, a Next resource node's caption takes the node's color instead.
			Requirement nextResource = guidance.getNextResource();
			String caption = "Next: " + guidance.getNextTaskInstruction();
			// A resource node's caption floats above the model rather than on it.
			Point label = nextResource != null
					? OutlinedText.findAboveModel(graphics, secondary, caption)
					: secondary.getCanvasTextLocation(graphics, caption, OutlinedText.DEFAULT_Z_OFFSET);
			Color text = ResourceVisibility.isCaptionOnly(nextResource, config)
				? ResourceSettings.getResourceOutlineColor(config, nextResource) : muted;
			if (label != null)
				OutlinedText.draw(graphics, label, caption, text);
		}
		graphics.setColor(oldColor);
		graphics.setStroke(oldStroke);
	}

	/** The current path and, when Next task is on, Next's path; neither has an end-tile marker. */
	private void drawPaths(Graphics2D graphics, WorldView view, Color color)
	{
		Stroke previous = graphics.getStroke();
		Color oldColor = graphics.getColor();
		// Next's path continues from the current path's end in the Next color, drawn first so the current path
		// stays on top; 2px like the current path, because the Next color is faint.
		if (config.showNextTask() && guidance.getNextTarget() != null)
		{
			graphics.setStroke(OverlayStrokes.MEDIUM);
			graphics.setColor(config.nextActionColor());
			Point last = null;
			for (Point step : guidance.getNextPath())
			{
				Point canvas = Perspective.localToCanvas(client, LocalPoint.fromScene(step.getX(), step.getY(), view), view.getPlane());
				if (last != null && canvas != null)
					graphics.drawLine(last.getX(), last.getY(), canvas.getX(), canvas.getY());
				last = canvas;
			}
		}
		graphics.setStroke(OverlayStrokes.MEDIUM);
		graphics.setColor(color);
		// No destination tile at the path's end, matching the Next path.
		Point last = null;
		for (Point step : guidance.getPath())
		{
			Point canvas = Perspective.localToCanvas(client, LocalPoint.fromScene(step.getX(), step.getY(), view), view.getPlane());
			if (last != null && canvas != null)
			{
				graphics.drawLine(last.getX(), last.getY(), canvas.getX(), canvas.getY());
			}
			last = canvas;
		}
		graphics.setStroke(previous);
		graphics.setColor(oldColor);
	}

	/** The bowl key line, centered under the bowl caption, so the keys can be planned while walking there. */
	private void drawBowlKeys(Graphics2D graphics, Point caption, String text, Color color)
	{
		if (!config.showKeysLabelForSingingBowlRecipes() || guidance.getTargetKind() != ObjectKind.BOWL)
			return;
		String keys = plugin.getPlan() == null ? null : formatBowlKeys(plugin.getBowlKeys(), plugin.getHomeVisit());
		if (keys == null)
			return;
		FontMetrics metrics = graphics.getFontMetrics();
		int x = caption.getX() + (metrics.stringWidth(text) - metrics.stringWidth(keys)) / 2;
		OutlinedText.draw(graphics, new Point(x, caption.getY() + metrics.getHeight()), keys, color);
	}

	/** This home visit's crafts as bowl keys, in number order as far as the free slots allow; null without crafts. */
	static String formatBowlKeys(BowlKeys keys, HomeVisit visit)
	{
		HomeCraftPlan home = visit.getHomeCraftPlan();
		return keys.format(home != null ? home.orderCrafts(keys::getKeyRank) : visit.getCrafts());
	}

	/**
	 * Phase prefixes like "Opening loop 3/5: " belong to the Status line in the side panel only; the label over the
	 * highlighted object shows just the task.
	 */
	static String getWorldLabel(String instruction)
	{
		if (instruction == null)
			return null;
		String rest = instruction.replaceFirst("^(?i)(After opening loop|Opening loop(?: \\d+/\\d+)?|Demi hunting|Demi search): *", "");
		if (rest.isEmpty())
			return instruction;
		return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
	}

	static void renderEnemyClickbox(Graphics2D graphics, NPC npc, Color color, String text)
	{
		renderEnemyClickbox(graphics, npc, color, text, 1, 35);
	}

	static void renderEnemyClickbox(Graphics2D graphics, NPC npc, Color color, String text, int stroke, int fillAlpha)
	{
		Graphics2D marker = (Graphics2D) graphics.create();
		try
		{
			Shape clickbox = npc.getConvexHull();
			if (clickbox == null || npc.isDead())
				return;
			if (fillAlpha > 0)
			{
				marker.setColor(ColorUtil.colorWithAlpha(color, fillAlpha));
				marker.fill(clickbox);
			}
			marker.setColor(color);
			marker.setStroke(new BasicStroke(stroke));
			marker.draw(clickbox);
			if (text != null)
			{
				Point label = npc.getCanvasTextLocation(marker, text, OutlinedText.DEFAULT_Z_OFFSET);
				if (label != null)
					OutlinedText.draw(marker, label, text, color);
			}
			UNREADABLE_NPCS.remove(npc);
		}
		catch (ArrayIndexOutOfBoundsException e)
		{
			noteUnreadable(npc, e);
		}
		finally
		{
			marker.dispose();
		}
	}

	/**
	 * The client's getConvexHull and getCanvasTextLocation read tile settings under the NPC's footprint with no bounds
	 * check, so both throw for an NPC at the edge of the loaded scene, every frame until it moves. Only that NPC's
	 * clickbox or caption is lost; the rest of the overlay still draws. The JVM may drop the message and stack of a
	 * repeated throw, so nothing here reads them beyond the log line.
	 */
	private static void noteUnreadable(NPC npc, ArrayIndexOutOfBoundsException e)
	{
		if (UNREADABLE_NPCS.add(npc))
			log.debug("Gauntlet clickbox skipped for NPC {}: {}", npc.getId(), e.getMessage());
	}

	void renderEnemyTile(Graphics2D graphics, WorldView view, NPC npc, Color color)
	{
		if (npc.isDead())
			return;
		WorldArea area = npc.getWorldArea();
		WorldPoint world = area == null ? npc.getWorldLocation() : area.toWorldPoint();
		if (world == null || world.getPlane() != view.getPlane())
			return;
		LocalPoint corner = LocalPoint.fromWorld(view, world);
		if (corner == null)
			return;
		int size = area == null ? Math.max(1, npc.getFootprintSize()) : Math.max(area.getWidth(), area.getHeight());
		Shape tile = Perspective.getCanvasTileAreaPoly(client,
				corner.plus((size - 1) * Perspective.LOCAL_HALF_TILE_SIZE, (size - 1) * Perspective.LOCAL_HALF_TILE_SIZE), size);
		if (tile == null)
			return;
		Graphics2D marker = (Graphics2D) graphics.create();
		try
		{
			marker.setColor(ColorUtil.colorWithAlpha(color, 25));
			marker.fill(tile);
			marker.setColor(color);
			marker.setStroke(OverlayStrokes.THIN);
			marker.draw(tile);
		}
		finally
		{
			marker.dispose();
		}
	}
}
