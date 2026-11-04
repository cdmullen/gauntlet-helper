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

import com.gauntlethelper.boss.HunllefProjectileStyle;
import com.gauntlethelper.boss.PrayerTileMode;
import com.gauntlethelper.boss.TornadoTileMode;
import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

// Sections follow what a new user sets first; only Setup opens by default. Each key is its method's name, built from the
// label, with a verb first on an on/off setting (show, highlight, hide, ...); renaming one resets its saved value.
// docs/product/SETTINGS.md maps each key to its label and section.
@ConfigGroup(GauntletHelperConfig.GROUP)
public interface GauntletHelperConfig extends Config
{
	String GROUP = "gauntlethelper";

	@ConfigSection(name = "Setup", description = "Weapons, armor, food and potions to prepare, in either Gauntlet", position = 0)
	String setupSection = "setup";

	@ConfigSection(name = "Guide", description = "Next task, captions and return timing", position = 1, closedByDefault = true)
	String guideSection = "guide";

	@ConfigSection(name = "Overlay", description = "The prep overlay, its next task line and the timer", position = 2, closedByDefault = true)
	String overlaySection = "overlay";

	@ConfigSection(name = "Highlights", description = "Tasks, resources, enemies, items and the singing bowl", position = 3, closedByDefault = true)
	String highlightsSection = "highlights";

	@ConfigSection(name = "Inventory and crafting", description = "Ground item order and singing bowl keys", position = 4, closedByDefault = true)
	String inventoryCraftingSection = "inventoryCrafting";

	@ConfigSection(name = "Trackers", description = "Where each resource count is shown", position = 5, closedByDefault = true)
	String trackersSection = "trackers";

	@ConfigSection(name = "Mapping", description = "Minimap markers and route line", position = 6, closedByDefault = true)
	String mappingSection = "mapping";

	@ConfigSection(name = "Colors", description = "Colors for guidance, trackers, inventory cues, resource nodes and demi-bosses", position = 7,
			closedByDefault = true)
	String colorsSection = "colors";

	@ConfigSection(name = "Hunllef", description = "Raw fish entry block, Hunllef, tornado and room tiles, prayer-disable orb", position = 8,
			closedByDefault = true)
	String hunllefSection = "hunllef";

	@ConfigSection(name = "Diagnostics", description = "Attempt recording for bug reports", position = 9, closedByDefault = true)
	String diagnosticsSection = "diagnostics";

	// Setup

	@ConfigItem(keyName = "weaponPair", name = "Weapon pair", position = 0,
			description = "Weapons to upgrade. An Either choice takes whichever upgrade drops first.",
			section = setupSection)
	default WeaponPair weaponPair() { return WeaponPair.STAFF_EITHER; }

	// A goal above T1 adds ArmorUpgrades steps after the vials at the bowl; None makes no armor.
	@ConfigItem(keyName = "armorGoal", name = "Armor goal", position = 1,
			description = "None: no armor.<br>T1: basic armor.<br>T2: attuned armor (200 more shards, 4 more of each material)."
					+ "<br>T3: perfected armor (500 more shards, 10 more of each material).",
			section = setupSection)
	default ArmorGoal armorGoal() { return ArmorGoal.T1; }

	// The plan lowers this to the slots the potions and the carried weapon leave (Requirements.computeFoodCap).
	@ConfigItem(keyName = "paddlefishTarget", name = "Paddlefish", position = 2,
			description = "Cooked paddlefish to prepare. Lowered if potions need the space.", section = setupSection)
	@Range(min = 0, max = 26)
	default int paddlefishTarget() { return 20; }

	// Default 2, as in The Gauntlet plugin. 0 plans no potions: no vials, dust or leaves.
	@ConfigItem(keyName = "grymLeafTarget", name = "Grym leaf", position = 3,
			description = "Grym leaves to gather; each one makes one Egniol potion. 0: no potions.", section = setupSection)
	@Range(min = 0, max = 8)
	default int grymLeafTarget() { return 2; }

	// Guide

	// The next task's master switch: its path, captions, overlay Next line and highlight. The next task is
	// worked out either way.
	@ConfigItem(keyName = "showNextTask", name = "Next task", position = 0,
			description = "Show a preview of the task after the current one.", section = guideSection)
	default boolean showNextTask() { return true; }

	// Every task caption, current and next, including the Defeat captions and the bowl key line.
	@ConfigItem(keyName = "showTaskCaptions", name = "Task captions", position = 1,
			description = "Show task text in the game scene.", section = guideSection)
	default boolean showTaskCaptions() { return true; }

	@ConfigItem(keyName = "useTimeAwareReturnGuidance", name = "Time-aware return guidance", position = 2,
			description = "Remind you to head home when prep time runs low.", section = guideSection)
	default boolean useTimeAwareReturnGuidance() { return true; }

	@ConfigItem(keyName = "returnWarningSeconds", name = "Return warning seconds", position = 3,
			description = "Seconds left when the return reminder starts.", section = guideSection)
	@Range(min = 0, max = 600)
	default int returnWarningSeconds() { return 90; }

	// Overlay

	@ConfigItem(keyName = "showTasksOverlay", name = "Tasks overlay", position = 0,
			description = "Show the prep checklist.", section = overlaySection)
	default boolean showTasksOverlay() { return true; }

	// The Gauntlet plugin's timer overlay without its chat timer, off by default as there.
	@ConfigItem(keyName = "showTimersOverlay", name = "Timers overlay", position = 1,
			description = "Show prep time, then total time once the Hunllef fight starts.", section = overlaySection)
	default boolean showTimersOverlay() { return false; }

	// Hiding a game widget gets a switch of its own, so the timer alone never removes game UI.
	@ConfigItem(keyName = "hideTimeLeftBox", name = "Hide the game's Time Left box", position = 2,
			description = "Hide the game's own Time Left box while in the Gauntlet; the Timers overlay shows the time instead.",
			section = overlaySection)
	default boolean hideTimeLeftBox() { return false; }

	// Highlights

	// The current task's object highlight, and the next task's object and enemy highlight while Guide → Next task
	// is on. The current kill objective and needed demis keep their own toggles below.
	@ConfigItem(keyName = "highlightTasksClickboxes", name = "Tasks clickboxes", position = 0,
			description = "Highlight the object for the current and next task.",
			section = highlightsSection)
	default boolean highlightTasksClickboxes() { return true; }

	@ConfigItem(keyName = "highlightResourceNodes", name = "Resource nodes", position = 1,
			description = "Outline every resource node you've seen.",
			section = highlightsSection)
	default boolean highlightResourceNodes() { return true; }

	@ConfigItem(keyName = "hideAfterResourceTarget", name = "Hide after resource target", position = 2,
			description = "Hide a resource once you have enough. It returns if needed again.", section = highlightsSection)
	default boolean hideAfterResourceTarget() { return true; }

	@ConfigItem(keyName = "highlightTargetedEnemies", name = "Targeted enemies", position = 3,
			description = "Highlight enemies to kill for shards or frames.", section = highlightsSection)
	default boolean highlightTargetedEnemies() { return true; }

	@ConfigItem(keyName = "highlightEnemiesTrueTiles", name = "Enemies true tiles", position = 4,
			description = "Gray true tiles under rats, bats, spiders, unicorns, scorpions and wolves.",
			section = highlightsSection)
	default boolean highlightEnemiesTrueTiles() { return true; }

	@ConfigItem(keyName = "highlightTargetedDemiBossesClickboxes", name = "Targeted demi-bosses clickboxes", position = 5,
			description = "Outline a demi-boss while you still need its drop.", section = highlightsSection)
	default boolean highlightTargetedDemiBossesClickboxes() { return true; }

	// The Gauntlet plugin's demi-boss colors on true tiles; on by default here (off there).
	@ConfigItem(keyName = "highlightDemiBossesTrueTiles", name = "Demi-bosses true tiles", position = 6,
			description = "Show every demi-boss's true tile in its own color.",
			section = highlightsSection)
	default boolean highlightDemiBossesTrueTiles() { return true; }

	@ConfigItem(keyName = "highlightNeededGroundItems", name = "Needed ground items", position = 7,
			description = "Label ground items you need to pick up.",
			section = highlightsSection)
	default boolean highlightNeededGroundItems() { return true; }

	// Also covers the teleport crystal box.
	@ConfigItem(keyName = "highlightInventoryActions", name = "Inventory actions", position = 8,
			description = "Outline items to equip, fill, mix, grind or teleport with.", section = highlightsSection)
	default boolean highlightInventoryActions() { return true; }

	@ConfigItem(keyName = "highlightDroppableItems", name = "Droppable items", position = 9,
			description = "Red squares on items to drop.",
			section = highlightsSection)
	default boolean highlightDroppableItems() { return true; }

	@ConfigItem(keyName = "highlightSingingBowlCrafting", name = "Singing bowl crafting", position = 10,
			description = "Singing bowl menu states: teal for every recipe this home visit crafts, yellow for recipes still wanted, "
					+ "dim gray for recipes not needed.",
			section = highlightsSection)
	default boolean highlightSingingBowlCrafting() { return true; }

	// Inventory and crafting

	@ConfigItem(keyName = "reorderGroundItems", name = "Reorder ground items", position = 0,
			description = "On a crowded tile, left click takes the needed item first.", section = inventoryCraftingSection)
	default boolean reorderGroundItems() { return true; }

	@ConfigItem(keyName = "showKeysLabelForSingingBowlRecipes", name = "Keys label for singing bowl recipes", position = 1,
			description = "Show the bowl menu keys to press this visit, e.g. Keys: 3, 4, 8, 8.",
			section = inventoryCraftingSection)
	default boolean showKeysLabelForSingingBowlRecipes() { return false; }

	// Trackers

	@ConfigItem(keyName = "oreTracker", name = "Ore tracker", position = 0,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay oreTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "barkTracker", name = "Bark tracker", position = 1,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay barkTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "linumTirinumTracker", name = "Linum tirinum tracker", position = 2,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay linumTirinumTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "shardsTracker", name = "Shards tracker", position = 3,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay shardsTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "framesTracker", name = "Frames tracker", position = 4,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay framesTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "upgradesTracker", name = "Upgrades tracker", position = 5,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay upgradesTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "grymLeavesTracker", name = "Grym leaves tracker", position = 6,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay grymLeavesTracker() { return ResourceDisplay.INFOBOX; }

	@ConfigItem(keyName = "fishTracker", name = "Fish tracker", position = 7,
			description = "Where to show this count.",
			section = trackersSection)
	default ResourceDisplay fishTracker() { return ResourceDisplay.INFOBOX; }

	// Hides finished overlay rows as well as infoboxes.
	@ConfigItem(keyName = "hideCompletedTrackers", name = "Hide completed trackers", position = 8,
			description = "Hide a tracker once you have enough. It returns if needed again.",
			section = trackersSection)
	default boolean hideCompletedTrackers() { return true; }

	// Mapping

	// Also covers the spawn/Hunllef room crystals.
	@ConfigItem(keyName = "showMinimapMarkers", name = "Minimap markers", position = 0,
			description = "Show observed resource markers and crystals at the spawn and Hunllef rooms on the minimap.",
			section = mappingSection)
	default boolean showMinimapMarkers() { return true; }

	// Same completion test as Hide after resource target, separate switch.
	@ConfigItem(keyName = "hideCompletedResourceMinimapIcons", name = "Hide completed resource minimap icons", position = 1,
			description = "Hide a resource's minimap icons once you have enough. They return if needed again.",
			section = mappingSection)
	default boolean hideCompletedResourceMinimapIcons() { return true; }

	@ConfigItem(keyName = "showMinimapRouteLine", name = "Minimap route line", position = 2,
			description = "Draw the current walking route on the minimap. Requires Minimap markers.",
			section = mappingSection)
	default boolean showMinimapRouteLine() { return true; }

	// Matches The Gauntlet plugin's demi-boss minimap icons.
	@ConfigItem(keyName = "showDemiBossMinimapIcons", name = "Demi-boss minimap icons", position = 3,
			description = "Show each demi-boss's attack style on the minimap.", section = mappingSection)
	default boolean showDemiBossMinimapIcons() { return true; }

	// Colors

	@Alpha
	@ConfigItem(keyName = "currentActionColor", name = "Current action color", position = 0,
			description = "Color of the current instruction.", section = colorsSection)
	default Color currentActionColor() { return new Color(0, 220, 220); }

	@Alpha
	@ConfigItem(keyName = "nextActionColor", name = "Next action color", position = 1,
			description = "Color of the next instruction and the next-task object highlight.", section = colorsSection)
	default Color nextActionColor() { return ResourceSettings.NEXT_COLOR; }

	@Alpha
	@ConfigItem(keyName = "trackerColor", name = "Tracker color", position = 2,
			description = "Color of unfinished tracker counts on the overlay and infoboxes; finished ones turn green.",
			section = colorsSection)
	default Color trackerColor() { return Color.WHITE; }

	// Inventory box colors, one per action.
	@Alpha
	@ConfigItem(keyName = "dropSquareColor", name = "Drop square color", position = 3,
			description = "Fill color for items to drop.",
			section = colorsSection)
	default Color dropSquareColor() { return new Color(255, 0, 0, 80); }

	@Alpha
	@ConfigItem(keyName = "equipOutlineColor", name = "Equip outline color", position = 4,
			description = "Outline color for gear to equip.", section = colorsSection)
	default Color equipOutlineColor() { return new Color(0, 220, 0); }

	@Alpha
	@ConfigItem(keyName = "fillOutlineColor", name = "Fill outline color", position = 5,
			description = "Outline and caption color for vials to fill at the water.", section = colorsSection)
	default Color fillOutlineColor() { return new Color(120, 190, 255); }

	@Alpha
	@ConfigItem(keyName = "mixOutlineColor", name = "Mix outline color", position = 6,
			description = "Outline color for potion ingredients to combine.",
			section = colorsSection)
	default Color mixOutlineColor() { return new Color(255, 230, 0); }

	@Alpha
	@ConfigItem(keyName = "grindOutlineColor", name = "Grind outline color", position = 7,
			description = "Outline color for the pestle and shards to grind.", section = colorsSection)
	default Color grindOutlineColor() { return new Color(255, 140, 0); }

	// Outline widths and tile fill opacity are constants in ResourceOverlay, not settings.

	@Alpha
	@ConfigItem(keyName = "oreOutlineColor", name = "Ore outline color", position = 9,
			description = "Outline color for this resource.", section = colorsSection)
	default Color oreOutlineColor() { return new Color(255, 0, 0, 255); }

	@Alpha
	@ConfigItem(keyName = "phrenRootsOutlineColor", name = "Phren roots outline color", position = 10,
			description = "Outline color for this resource.", section = colorsSection)
	default Color phrenRootsOutlineColor() { return new Color(0, 255, 0, 255); }

	@Alpha
	@ConfigItem(keyName = "linumTirinumOutlineColor", name = "Linum tirinum outline color", position = 11,
			description = "Outline color for this resource.", section = colorsSection)
	default Color linumTirinumOutlineColor() { return new Color(255, 255, 255, 255); }

	@Alpha
	@ConfigItem(keyName = "grymRootOutlineColor", name = "Grym root outline color", position = 12,
			description = "Outline color for this resource.", section = colorsSection)
	default Color grymRootOutlineColor() { return new Color(255, 255, 0, 255); }

	@Alpha
	@ConfigItem(keyName = "fishingSpotOutlineColor", name = "Fishing spot outline color", position = 13,
			description = "Outline color for this resource.", section = colorsSection)
	default Color fishingSpotOutlineColor() { return ResourceSettings.FISH_OUTLINE; }

	@Alpha
	@ConfigItem(keyName = "darkBeastColor", name = "Dark beast color", position = 14,
			description = "Tile color of dark beasts.", section = colorsSection)
	default Color darkBeastColor() { return Color.GREEN; }

	@Alpha
	@ConfigItem(keyName = "dragonColor", name = "Dragon color", position = 15, description = "Tile color of dragons.", section = colorsSection)
	default Color dragonColor() { return Color.BLUE; }

	@Alpha
	@ConfigItem(keyName = "bearColor", name = "Bear color", position = 16, description = "Tile color of bears.", section = colorsSection)
	default Color bearColor() { return Color.RED; }

	// Hunllef

	@ConfigItem(keyName = "blockHunllefEntryWithRawFish", name = "Block Hunllef entry with raw fish", position = 0,
			description = "Moves Pass and Quick-pass on Hunllef's barrier below Walk here while you hold raw paddlefish.",
			section = hunllefSection)
	default boolean blockHunllefEntryWithRawFish() { return true; }

	@ConfigItem(keyName = "outlineHunllefTile", name = "Outline Hunllef tile", position = 1, description = "Outline Hunllef's tile.", section = hunllefSection)
	default boolean outlineHunllefTile() { return true; }

	// The prayer mode, width and colors match The Gauntlet plugin's Hunllef options; the
	// defaults are prayer colors, 2px, no fill.
	@ConfigItem(keyName = "colorTileByPrayer", name = "Color tile by prayer", position = 2,
			description = "Color Hunllef's tile by its protection prayer. Inverted swaps green and blue.", section = hunllefSection)
	default PrayerTileMode colorTileByPrayer() { return PrayerTileMode.ON; }

	@Range(min = 1, max = 5)
	@ConfigItem(keyName = "hunllefTileOutlineWidth", name = "Hunllef tile outline width", position = 3,
			description = "Hunllef tile border width in pixels.", section = hunllefSection)
	default int hunllefTileOutlineWidth() { return 2; }

	@Alpha
	@ConfigItem(keyName = "hunllefOutlineColor", name = "Hunllef outline color", position = 4,
			description = "Hunllef tile border color while Color tile by prayer is Off.", section = hunllefSection)
	default Color hunllefOutlineColor() { return Color.WHITE; }

	@Alpha
	@ConfigItem(keyName = "hunllefFillColor", name = "Hunllef fill color", position = 5,
			description = "Hunllef tile fill color and opacity.", section = hunllefSection)
	default Color hunllefFillColor() { return new Color(255, 255, 255, 0); }

	@ConfigItem(keyName = "outlineTornadoTile", name = "Outline tornado tile", position = 6,
			description = "Outline tornadoes on their animated tile or their true tile, or not at all.",
			section = hunllefSection)
	default TornadoTileMode outlineTornadoTile() { return TornadoTileMode.TRUE_TILE; }

	@Range(min = 1, max = 2)
	@ConfigItem(keyName = "tornadoTileOutlineWidth", name = "Tornado tile outline width", position = 7,
			description = "Tornado tile border width in pixels.", section = hunllefSection)
	default int tornadoTileOutlineWidth() { return 1; }

	@Alpha
	@ConfigItem(keyName = "tornadoOutlineColor", name = "Tornado outline color", position = 8,
			description = "Tornado tile border color.", section = hunllefSection)
	default Color tornadoOutlineColor() { return Color.YELLOW; }

	@Alpha
	@ConfigItem(keyName = "tornadoFillColor", name = "Tornado fill color", position = 9,
			description = "Tornado tile fill color and opacity.", section = hunllefSection)
	default Color tornadoFillColor() { return new Color(255, 255, 0, 50); }

	// A Ground Markers tile pack for the Hunllef room, built in, off by default.
	@ConfigItem(keyName = "showHunllefRoomTileMarkers", name = "Hunllef room tile markers", position = 10,
			description = "Mark eight tiles in Hunllef's room, two in the middle of each side.",
			section = hunllefSection)
	default boolean showHunllefRoomTileMarkers() { return false; }

	@Alpha
	@ConfigItem(keyName = "hunllefRoomTileMarkerColor", name = "Hunllef room tile marker color", position = 11,
			description = "Color of the Hunllef room tile markers.",
			section = hunllefSection)
	default Color hunllefRoomTileMarkerColor() { return new Color(241, 255, 0); }

	// The Hunllef Projectiles plugin's orb swap, built in; Original keeps the game's orb.
	@ConfigItem(keyName = "prayerDisableProjectile", name = "Prayer-disable projectile", position = 12,
			description = "Draw Hunllef's prayer-disable orb as another boss's projectile. Original keeps the game's orb.",
			section = hunllefSection)
	default HunllefProjectileStyle prayerDisableProjectile() { return HunllefProjectileStyle.ORIGINAL; }

	// Diagnostics

	@ConfigItem(keyName = "logAttempts", name = "Log attempts", position = 0,
			description = "Save a tick-by-tick log of each attempt to .runelite/plugin-data/gauntlet-helper/attempts for bug"
			+ " reports.", section = diagnosticsSection)
	default boolean logAttempts() { return false; }

	@ConfigItem(keyName = "keepRecordings", name = "Keep recordings", position = 1,
			description = "How many attempt recordings to keep. Starting a new one deletes the oldest beyond this; 0 keeps them all.",
			section = diagnosticsSection)
	@Range(min = 0, max = 1000)
	default int keepRecordings() { return 50; }
}
