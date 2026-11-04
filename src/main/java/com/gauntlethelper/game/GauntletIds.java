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

package com.gauntlethelper.game;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;

/**
 * The regular Gauntlet's item, NPC and object IDs paired with their Corrupted (`_HM`) twins. The plugin reads the game
 * through these at its read boundaries (inventory, equipment, ground items, menus, scene objects, door morphs, NPCs,
 * widget items), so every plan class and simulation keeps seeing `_HM` IDs in both modes.
 * Recordings and logs keep the raw game IDs. IDs both modes share (paddlefish, pestle, vials of water, potions) and
 * the regular empty vial and sceptre, which the Corrupted Gauntlet itself uses and the code reads beside their twins,
 * are not in the table; an ID that isn't listed passes through unchanged.
 */
public final class GauntletIds
{
	private static final Map<Integer, Integer> ITEMS = new HashMap<>();
	private static final Map<Integer, Integer> NPCS = new HashMap<>();
	private static final Map<Integer, Integer> OBJECTS = new HashMap<>();

	static
	{
		ITEMS.put(ItemID.GAUNTLET_CRYSTAL_SHARD, ItemID.GAUNTLET_CRYSTAL_SHARD_HM);
		ITEMS.put(ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED, ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM);
		ITEMS.put(ItemID.GAUNTLET_ORE, ItemID.GAUNTLET_ORE_HM);
		ITEMS.put(ItemID.GAUNTLET_BARK, ItemID.GAUNTLET_BARK_HM);
		ITEMS.put(ItemID.GAUNTLET_FIBRE, ItemID.GAUNTLET_FIBRE_HM);
		ITEMS.put(ItemID.GAUNTLET_HERB, ItemID.GAUNTLET_HERB_HM);
		ITEMS.put(ItemID.GAUNTLET_TELEPORT_CRYSTAL, ItemID.GAUNTLET_TELEPORT_CRYSTAL_HM);
		ITEMS.put(ItemID.GAUNTLET_MAGIC_COMPONENT, ItemID.GAUNTLET_MAGIC_COMPONENT_HM);
		ITEMS.put(ItemID.GAUNTLET_RANGED_COMPONENT, ItemID.GAUNTLET_RANGED_COMPONENT_HM);
		ITEMS.put(ItemID.GAUNTLET_MELEE_COMPONENT, ItemID.GAUNTLET_MELEE_COMPONENT_HM);
		ITEMS.put(ItemID.GAUNTLET_GENERIC_COMPONENT, ItemID.GAUNTLET_GENERIC_COMPONENT_HM);
		ITEMS.put(ItemID.GAUNTLET_PICKAXE, ItemID.GAUNTLET_PICKAXE_HM);
		ITEMS.put(ItemID.GAUNTLET_AXE, ItemID.GAUNTLET_AXE_HM);
		ITEMS.put(ItemID.GAUNTLET_HARPOON, ItemID.GAUNTLET_HARPOON_HM);
		ITEMS.put(ItemID.GAUNTLET_COMBO_FOOD, ItemID.GAUNTLET_COMBO_FOOD_HM);
		ITEMS.put(ItemID.GAUNTLET_ESCAPE_CRYSTAL, ItemID.GAUNTLET_ESCAPE_CRYSTAL_HM);
		ITEMS.put(ItemID.GAUNTLET_HELMET_T1, ItemID.GAUNTLET_HELMET_T1_HM);
		ITEMS.put(ItemID.GAUNTLET_HELMET_T2, ItemID.GAUNTLET_HELMET_T2_HM);
		ITEMS.put(ItemID.GAUNTLET_HELMET_T3, ItemID.GAUNTLET_HELMET_T3_HM);
		ITEMS.put(ItemID.GAUNTLET_CHESTPLATE_T1, ItemID.GAUNTLET_CHESTPLATE_T1_HM);
		ITEMS.put(ItemID.GAUNTLET_CHESTPLATE_T2, ItemID.GAUNTLET_CHESTPLATE_T2_HM);
		ITEMS.put(ItemID.GAUNTLET_CHESTPLATE_T3, ItemID.GAUNTLET_CHESTPLATE_T3_HM);
		ITEMS.put(ItemID.GAUNTLET_PLATELEGS_T1, ItemID.GAUNTLET_PLATELEGS_T1_HM);
		ITEMS.put(ItemID.GAUNTLET_PLATELEGS_T2, ItemID.GAUNTLET_PLATELEGS_T2_HM);
		ITEMS.put(ItemID.GAUNTLET_PLATELEGS_T3, ItemID.GAUNTLET_PLATELEGS_T3_HM);
		ITEMS.put(ItemID.GAUNTLET_MAGIC_T1, ItemID.GAUNTLET_MAGIC_T1_HM);
		ITEMS.put(ItemID.GAUNTLET_MAGIC_T2, ItemID.GAUNTLET_MAGIC_T2_HM);
		ITEMS.put(ItemID.GAUNTLET_MAGIC_T3, ItemID.GAUNTLET_MAGIC_T3_HM);
		ITEMS.put(ItemID.GAUNTLET_RANGED_T1, ItemID.GAUNTLET_RANGED_T1_HM);
		ITEMS.put(ItemID.GAUNTLET_RANGED_T2, ItemID.GAUNTLET_RANGED_T2_HM);
		ITEMS.put(ItemID.GAUNTLET_RANGED_T3, ItemID.GAUNTLET_RANGED_T3_HM);
		ITEMS.put(ItemID.GAUNTLET_MELEE_T1, ItemID.GAUNTLET_MELEE_T1_HM);
		ITEMS.put(ItemID.GAUNTLET_MELEE_T2, ItemID.GAUNTLET_MELEE_T2_HM);
		ITEMS.put(ItemID.GAUNTLET_MELEE_T3, ItemID.GAUNTLET_MELEE_T3_HM);

		NPCS.put(NpcID.CRYSTAL_RAT, NpcID.CRYSTAL_RAT_HM);
		NPCS.put(NpcID.CRYSTAL_SPIDER, NpcID.CRYSTAL_SPIDER_HM);
		NPCS.put(NpcID.CRYSTAL_BAT, NpcID.CRYSTAL_BAT_HM);
		NPCS.put(NpcID.CRYSTAL_UNICORN, NpcID.CRYSTAL_UNICORN_HM);
		NPCS.put(NpcID.CRYSTAL_SCORPION, NpcID.CRYSTAL_SCORPION_HM);
		NPCS.put(NpcID.CRYSTAL_WOLF, NpcID.CRYSTAL_WOLF_HM);
		NPCS.put(NpcID.CRYSTAL_BEAR, NpcID.CRYSTAL_BEAR_HM);
		NPCS.put(NpcID.CRYSTAL_DRAGON, NpcID.CRYSTAL_DRAGON_HM);
		NPCS.put(NpcID.CRYSTAL_DARK_BEAST, NpcID.CRYSTAL_DARK_BEAST_HM);
		NPCS.put(NpcID.CRYSTAL_HUNLLEF_MELEE, NpcID.CRYSTAL_HUNLLEF_MELEE_HM);
		NPCS.put(NpcID.CRYSTAL_HUNLLEF_RANGED, NpcID.CRYSTAL_HUNLLEF_RANGED_HM);
		NPCS.put(NpcID.CRYSTAL_HUNLLEF_MAGIC, NpcID.CRYSTAL_HUNLLEF_MAGIC_HM);
		NPCS.put(NpcID.CRYSTAL_HUNLLEF_DEATH, NpcID.CRYSTAL_HUNLLEF_DEATH_HM);
		NPCS.put(NpcID.CRYSTAL_HUNLLEF_CRYSTALS, NpcID.CRYSTAL_HUNLLEF_CRYSTALS_HM);

		OBJECTS.put(ObjectID.GAUNTLET_ROCK, ObjectID.GAUNTLET_ROCK_HM);
		OBJECTS.put(ObjectID.GAUNTLET_TREE, ObjectID.GAUNTLET_TREE_HM);
		OBJECTS.put(ObjectID.GAUNTLET_FIBRE, ObjectID.GAUNTLET_FIBRE_HM);
		OBJECTS.put(ObjectID.GAUNTLET_HERB, ObjectID.GAUNTLET_HERB_HM);
		OBJECTS.put(ObjectID.GAUNTLET_POND, ObjectID.GAUNTLET_POND_HM);
		OBJECTS.put(ObjectID.GAUNTLET_ROCK_DEPLETED, ObjectID.GAUNTLET_ROCK_DEPLETED_HM);
		OBJECTS.put(ObjectID.GAUNTLET_TREE_DEPLETED, ObjectID.GAUNTLET_TREE_DEPLETED_HM);
		OBJECTS.put(ObjectID.GAUNTLET_FIBRE_DEPLETED, ObjectID.GAUNTLET_FIBRE_DEPLETED_HM);
		OBJECTS.put(ObjectID.GAUNTLET_HERB_DEPLETED, ObjectID.GAUNTLET_HERB_DEPLETED_HM);
		OBJECTS.put(ObjectID.GAUNTLET_POND_DEPLETED, ObjectID.GAUNTLET_POND_DEPLETED_HM);
		OBJECTS.put(ObjectID.GAUNTLET_SINGING_BOWL, ObjectID.GAUNTLET_SINGING_BOWL_HM);
		OBJECTS.put(ObjectID.GAUNTLET_RANGE, ObjectID.GAUNTLET_RANGE_HM);
		OBJECTS.put(ObjectID.GAUNTLET_SINK, ObjectID.GAUNTLET_SINK_HM);
		OBJECTS.put(ObjectID.GAUNTLET_TOOLS, ObjectID.GAUNTLET_TOOLS_HM);
		OBJECTS.put(ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_01, ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_01_HM);
		OBJECTS.put(ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_02, ObjectID.PRIF_GAUNTLET_DOOR_WALL_LIT_02_HM);
		OBJECTS.put(ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_01, ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_01_HM);
		OBJECTS.put(ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_02, ObjectID.PRIF_GAUNTLET_DOOR_WALL_UNLIT_02_HM);
		OBJECTS.put(ObjectID.GAUNTLET_BLOCKADE, ObjectID.GAUNTLET_BLOCKADE_HM);
		OBJECTS.put(ObjectID.GAUNTLET_BLOCKADE_ENTER, ObjectID.GAUNTLET_BLOCKADE_ENTER_HM);
		OBJECTS.put(ObjectID.GAUNTLET_BLOCKADE_ESCAPE, ObjectID.GAUNTLET_BLOCKADE_ESCAPE_HM);
	}

	private GauntletIds() { }

	/** An item ID as the plan reads it: a regular item becomes its Corrupted twin, anything else is unchanged. */
	public static int item(int id) { return ITEMS.getOrDefault(id, id); }

	/** An NPC ID as the plan reads it: a regular NPC becomes its Corrupted twin, anything else is unchanged. */
	public static int npc(int id) { return NPCS.getOrDefault(id, id); }

	/** A scene object ID as the plan reads it: a regular object becomes its Corrupted twin, anything else is unchanged. */
	public static int object(int id) { return OBJECTS.getOrDefault(id, id); }

	/** The pairs, regular to Corrupted. */
	public static Map<Integer, Integer> getItemPairs() { return Collections.unmodifiableMap(ITEMS); }

	/** As getItemPairs, for NPC ids. */
	public static Map<Integer, Integer> getNpcPairs() { return Collections.unmodifiableMap(NPCS); }

	/** As getItemPairs, for object ids. */
	public static Map<Integer, Integer> getObjectPairs() { return Collections.unmodifiableMap(OBJECTS); }
}
