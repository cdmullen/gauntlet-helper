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

package com.gauntlethelper.plan;

import net.runelite.api.gameval.ItemID;

/**
 * The rows of the prep plan (Requirements.getRow): gathered resources, drops and the progress rows (potions, armor,
 * weapons). The gathered rows carry the item each one counts, the instruction that gathers it and the tool it takes.
 */
public enum Requirement
{
	ORE("Ore", ItemID.GAUNTLET_ORE_HM, "Mine ore", ItemID.GAUNTLET_PICKAXE_HM),
	BARK("Bark", ItemID.GAUNTLET_BARK_HM, "Chop bark", ItemID.GAUNTLET_AXE_HM),
	LINUM_TIRINUM("Linum tirinum", ItemID.GAUNTLET_FIBRE_HM, "Pick linum tirinum", -1),
	SHARDS("Shards", ItemID.GAUNTLET_CRYSTAL_SHARD_HM),
	FRAMES("Frames", ItemID.GAUNTLET_GENERIC_COMPONENT_HM),
	UPGRADES("Distinct upgrades", ItemID.GAUNTLET_MAGIC_COMPONENT_HM),
	GRYM_LEAVES("Grym leaves", ItemID.GAUNTLET_HERB_HM, "Pick grym leaves", -1),
	VIALS("Vials / unfinished", ItemID.GAUNTLET_VIAL_EMPTY_HM),
	DUST("Dust", ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM, null, ItemID.GAUNTLET_PESTLE),
	FISH("Fish (raw + cooked)", ItemID.GAUNTLET_RAW_FOOD, "Catch paddlefish", ItemID.GAUNTLET_HARPOON_HM),
	COOKED("Cooked fish", ItemID.GAUNTLET_FOOD),
	POTIONS("Potions crafted", ItemID.GAUNTLET_POTION_3),
	ARMOR("Armor pieces", ItemID.GAUNTLET_CHESTPLATE_T1_HM),
	WEAPONS("Perfected types", ItemID.GAUNTLET_MAGIC_T3_HM);

	/** Ore, bark and linum tirinum: the bowl's materials, in instruction order. */
	public static final Requirement[] MATERIALS = {ORE, BARK, LINUM_TIRINUM};

	/** Everything gathered from a node, in instruction order: the materials, then Grym leaves and fish. */
	public static final Requirement[] GATHERED = {ORE, BARK, LINUM_TIRINUM, GRYM_LEAVES, FISH};

	/** The rows gathered with a tool (getTool), in the order finished tools are dropped. */
	public static final Requirement[] TOOLED = {ORE, BARK, FISH, DUST};

	private final String label;
	private final int itemId;
	private final String gatherLabel;
	private final int tool;

	Requirement(String label, int itemId) { this(label, itemId, null, -1); }

	Requirement(String label, int itemId, String gatherLabel, int tool)
	{
		this.label = label;
		this.itemId = itemId;
		this.gatherLabel = gatherLabel;
		this.tool = tool;
	}

	public String getLabel() { return label; }
	public int getItemId() { return itemId; }

	/** The instruction that gathers this resource from a node ("Mine ore"), null for a row nothing gathers. */
	public String getGatherLabel() { return gatherLabel; }

	/** The tool this resource is gathered or ground with (pickaxe, axe, harpoon, pestle), or -1. */
	public int getTool() { return tool; }

	/**
	 * The row an item on the ground or in a pile counts toward: the gathered resources, shards, frames, dust, the
	 * crystal vial, and raw or cooked fish (FISH). Null for upgrade components, tools, armor and anything else, and for
	 * the plain empty vial a drunk potion leaves (GAUNTLET_VIAL_EMPTY), which is dropped at home and never wanted back.
	 */
	public static Requirement ofItem(int id)
	{
		switch (id)
		{
			case ItemID.GAUNTLET_ORE_HM:
				return ORE;
			case ItemID.GAUNTLET_BARK_HM:
				return BARK;
			case ItemID.GAUNTLET_FIBRE_HM:
				return LINUM_TIRINUM;
			case ItemID.GAUNTLET_CRYSTAL_SHARD_HM:
				return SHARDS;
			case ItemID.GAUNTLET_GENERIC_COMPONENT_HM:
				return FRAMES;
			case ItemID.GAUNTLET_HERB_HM:
				return GRYM_LEAVES;
			case ItemID.GAUNTLET_VIAL_EMPTY_HM:
				return VIALS;
			case ItemID.GAUNTLET_CRYSTAL_SHARD_CRUSHED_HM:
				return DUST;
			case ItemID.GAUNTLET_RAW_FOOD:
			case ItemID.GAUNTLET_FOOD:
				return FISH;
			default:
				return null;
		}
	}

	/** The row a tool serves (pickaxe ORE, axe BARK, harpoon FISH, pestle DUST), or null for anything else. */
	public static Requirement ofTool(int id)
	{
		for (Requirement resource : TOOLED)
			if (resource.tool == id)
				return resource;
		return null;
	}
}
