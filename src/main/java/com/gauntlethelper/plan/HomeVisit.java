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

import java.util.List;

/**
 * One tick's bowl state for the live plan: the home visit's forecast once a task or overlay has asked for it, and whether
 * the visit's crafts have replaced the plan's own next recipes. It stays apart from Requirements, which is immutable and
 * rebuilt by the trip search, and is replaced with each tick's plan.
 */
public final class HomeVisit
{
	private final Requirements requirements;
	private HomeCraftPlan homeCraftPlan;
	private boolean homeCraftPriority;

	public HomeVisit(Requirements requirements)
	{
		this.requirements = requirements;
	}

	/**
	 * At home with a bowl craft makeable now, an equip task waits until the visit's crafts are made: the bowl stays the
	 * task and the equip task becomes Next. The Equip outlines stay (InventoryAdvice.buildActions). A craft waiting for
	 * space, or a piece crafted onto the ground, keeps the equip task first, since equipping frees the slots they need.
	 */
	public static HomeVisit craftingBeforeEquip(Requirements plan)
	{
		if (plan.getInventoryAction() != Requirements.InventoryAction.EQUIP || !plan.getGroundArmor().isEmpty())
			return new HomeVisit(plan);
		HomeCraftPlan home = plan.computeHomeCrafts();
		boolean craftFirst = home.getNextCraft() != null && home.countWaitingForSpace() == 0;
		HomeVisit visit = new HomeVisit(craftFirst ? plan.withEquipAfterCrafts() : plan);
		visit.homeCraftPlan = home;
		return visit;
	}

	public Requirements getRequirements() { return requirements; }
	public boolean isFor(Requirements plan) { return requirements == plan; }

	/** The visit's crafts become the plan's next recipes (canCraft, getCrafts) from here on. */
	public HomeCraftPlan planCrafts()
	{
		homeCraftPriority = true;
		return previewCrafts();
	}

	public HomeCraftPlan previewCrafts()
	{
		homeCraftPlan = requirements.computeHomeCrafts();
		return homeCraftPlan;
	}

	/** The latest forecast; null until something has asked for one. */
	public HomeCraftPlan getHomeCraftPlan() { return homeCraftPlan; }
	public boolean canCraft() { return !homeCraftPriority ? requirements.canCraft() : homeCraftPlan.getNextCraft() != null; }
	public List<Requirements.Craft> getCrafts() { return !homeCraftPriority ? requirements.getCrafts() : homeCraftPlan.getCrafts(); }
}
