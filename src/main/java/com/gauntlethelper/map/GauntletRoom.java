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

package com.gauntlethelper.map;

import com.gauntlethelper.game.EnemyType;
import com.gauntlethelper.game.ObjectKind;
import com.gauntlethelper.plan.GroundLoot;
import com.gauntlethelper.plan.Requirement;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import net.runelite.api.coords.WorldPoint;

/** A persistent room in one attempt. Public access is read-only; contents are observed, never predicted. */
public final class GauntletRoom
{
	public enum Direction
	{
		NORTH(0, 1),
		EAST(1, 0),
		SOUTH(0, -1),
		WEST(-1, 0);

		private final int dx;
		private final int dy;

		Direction(int dx, int dy)
		{
			this.dx = dx;
			this.dy = dy;
		}

		/** The room column step toward this side: east +1, west -1. */
		public int getDx() { return dx; }
		/** The room row step toward this side: north +1, south -1. */
		public int getDy() { return dy; }
	}

	public enum Connection
	{
		UNKNOWN,
		UNLIT,
		OPEN,
		HUNLLEF_BARRIER
	}

	public enum EnemyState
	{
		ALIVE,
		DEFEATED,
		ABSENT
	}

	public enum DemiStatus
	{
		UNKNOWN,
		ALIVE,
		DEFEATED,
		ABSENT
	}

	public static final class ResourceNode
	{
		private final WorldPoint location;
		private final ObjectKind kind;
		private final boolean active;

		ResourceNode(WorldPoint location, ObjectKind kind, boolean active)
		{
			this.location = location;
			this.kind = kind;
			this.active = active;
		}

		public WorldPoint getLocation() { return location; }
		public ObjectKind getKind() { return kind; }
		public boolean isActive() { return active; }

		/** Node count is known; the server's remaining harvest yield is not. */
		OptionalInt getRemainingYield() { return active ? OptionalInt.empty() : OptionalInt.of(0); }
	}

	public static final class Enemy
	{
		private final long key;
		private final int npcId, npcIndex, observedTick;
		private final EnemyType type;
		private final WorldPoint location;
		private final EnemyState state;
		private final boolean visible;

		Enemy(long key, int npcId, int index, EnemyType type, WorldPoint location, EnemyState state, boolean visible, int tick)
		{
			this.key = key;
			this.npcId = npcId;
			this.npcIndex = index;
			this.type = type;
			this.location = location;
			this.state = state;
			this.visible = visible;
			this.observedTick = tick;
		}

		public long getKey() { return key; }
		public EnemyType getType() { return type; }
		public WorldPoint getLocation() { return location; }
		public EnemyState getState() { return state; }
		public boolean isAlive() { return state == EnemyState.ALIVE; }
		public boolean isDemiBoss() { return type.isDemiBoss(); }
		public boolean isVisible() { return visible; }
		public int getObservedTick() { return observedTick; }
		Enemy withState(EnemyState next, boolean visible, int tick)
		{
			return new Enemy(key, npcId, npcIndex, type, location, next, visible, tick);
		}
	}

	private final int row, column;
	private final boolean startingRoom;
	private boolean discovered, visited, contentsObserved, demiSearchChecked;
	private final Map<WorldPoint, ResourceNode> resources = new LinkedHashMap<>();
	private final Map<Long, Enemy> enemies = new LinkedHashMap<>();
	/** Every enemy type ever observed here, kept after the enemy dies or unloads. */
	private final Set<EnemyType> seenTypes = EnumSet.noneOf(EnemyType.class);
	private final Map<WorldPoint, ObjectKind> facilities = new LinkedHashMap<>();
	private final Map<Direction, Connection> connections = new EnumMap<>(Direction.class);
	private List<GroundLoot.Entry> loot = Collections.emptyList();

	GauntletRoom(int row, int column, int startIndex)
	{
		this.row = row;
		this.column = column;
		startingRoom = getIndex() == startIndex;
		if (row > 0)
			connections.put(Direction.NORTH, Connection.UNKNOWN);
		if (column < 6)
			connections.put(Direction.EAST, Connection.UNKNOWN);
		if (row < 6)
			connections.put(Direction.SOUTH, Connection.UNKNOWN);
		if (column > 0)
			connections.put(Direction.WEST, Connection.UNKNOWN);
	}

	public int getRow() { return row; }
	public int getColumn() { return column; }
	public int getIndex() { return row * GauntletMap.SIZE + column; }
	public boolean isStartingRoom() { return startingRoom; }
	public boolean isHunllefRoom() { return row == 3 && column == 3; }
	public boolean isPerimeter() { return row == 0 || row == 6 || column == 0 || column == 6; }

	/** One of the eight rooms around Hunllef's. */
	public boolean isHunllefRing()
	{
		return !isHunllefRoom() && Math.abs(row - 3) <= 1 && Math.abs(column - 3) <= 1;
	}

	/** Demis can spawn in any edge or corner room (OSRS Wiki change history), guaranteed or not. */
	public boolean canHaveDemiSpawn() { return isPerimeter(); }
	public boolean isGuaranteedDemiSpawn()
	{
		return row == 3 && (column == 0 || column == 6) || column == 3 && (row == 0 || row == 6);
	}

	public boolean isDiscovered() { return discovered; }
	public boolean isVisited() { return visited; }

	/** True only after an entire room interior and its NPC collection were available to observe. */
	public boolean hasObservedContents() { return contentsObserved; }
	public boolean isDemiSearchChecked() { return demiSearchChecked; }
	public List<ResourceNode> getResources() { return List.copyOf(resources.values()); }
	public List<Enemy> getEnemies() { return List.copyOf(enemies.values()); }
	public Map<WorldPoint, ObjectKind> getFacilities()
	{
		return Collections.unmodifiableMap(new LinkedHashMap<>(facilities));
	}

	public Map<Direction, Connection> getConnections()
	{
		return Collections.unmodifiableMap(new EnumMap<>(connections));
	}

	/** The connection in one direction, without copying the map (the path searches read this per room step). */
	public Connection getConnection(Direction direction) { return connections.get(direction); }

	/** Whether a facility of this kind was observed here, without copying the map. */
	public boolean hasFacility(ObjectKind kind) { return facilities.containsValue(kind); }

	/** The remembered enemy with this key, or null. */
	Enemy findEnemy(long key) { return enemies.get(key); }

	public List<GroundLoot.Entry> getLoot() { return loot; }
	public int countResourceNodes(Requirement resource)
	{
		return (int) resources.values().stream().filter(n -> n.active && n.kind.resource == resource).count();
	}

	/** Nodes of this resource ever observed here, depleted ones included. */
	public int countSeenResourceNodes(Requirement resource)
	{
		return (int) resources.values().stream().filter(n -> n.kind.resource == resource).count();
	}

	/** Whether an enemy of this type was ever observed here, alive or not. */
	public boolean hasSeenEnemy(EnemyType type) { return seenTypes.contains(type); }

	public int countLivingEnemies(EnemyType type)
	{
		return (int) enemies.values().stream().filter(e -> e.isAlive() && e.type == type).count();
	}

	public Map<EnemyType, Integer> countLivingEnemiesByType()
	{
		Map<EnemyType, Integer> result = new EnumMap<>(EnemyType.class);
		for (Enemy enemy : enemies.values())
			if (enemy.isAlive())
				result.merge(enemy.type, 1, Integer::sum);
		return Collections.unmodifiableMap(result);
	}

	/** Last observed living demi; unloading alone does not claim that an enemy died. */
	public boolean hasLivingDemiBoss()
	{
		return enemies.values().stream().anyMatch(e -> e.isDemiBoss() && e.isAlive());
	}

	public DemiStatus getDemiStatus()
	{
		if (hasLivingDemiBoss())
			return DemiStatus.ALIVE;
		if (enemies.values().stream().anyMatch(e -> e.isDemiBoss() && e.state == EnemyState.DEFEATED))
			return DemiStatus.DEFEATED;
		return contentsObserved ? DemiStatus.ABSENT : DemiStatus.UNKNOWN;
	}

	void discover() { discovered = true; }
	void visit()
	{
		discovered = true;
		visited = true;
	}

	void recordResource(WorldPoint at, ObjectKind kind, boolean active, int tick)
	{
		ResourceNode previous = resources.get(at);
		if (kind == null && previous != null)
			kind = previous.kind;
		if (kind != null && kind.resource != null)
			resources.put(at, new ResourceNode(at, kind, active));
	}

	void recordFacility(WorldPoint at, ObjectKind kind) { facilities.put(at, kind); }
	public void removeFacility(WorldPoint at) { facilities.remove(at); }
	void setConnection(Direction direction, Connection state) { connections.put(direction, state); }
	void putEnemy(Enemy enemy)
	{
		enemies.put(enemy.key, enemy);
		seenTypes.add(enemy.type);
		if (enemy.isDemiBoss())
		{
			if (enemy.isAlive())
				demiSearchChecked = false;
			else if (enemy.state == EnemyState.DEFEATED && !hasLivingDemiBoss())
				demiSearchChecked = true;
		}
	}

	void removeEnemy(long key)
	{
		Enemy previous = enemies.remove(key);
		if (previous != null && previous.isDemiBoss() && contentsObserved && !hasLivingDemiBoss())
			demiSearchChecked = true;
	}

	void beginObservation()
	{
		for (Enemy enemy : getEnemies())
			enemies.put(enemy.key, enemy.withState(enemy.state, false, enemy.observedTick));
	}

	void completeObservation(int tick)
	{
		contentsObserved = true;
		for (Enemy enemy : getEnemies())
			if (enemy.isAlive() && !enemy.visible)
				enemies.put(enemy.key, enemy.withState(EnemyState.ABSENT, false, tick));
		demiSearchChecked = !hasLivingDemiBoss();
	}

	public void setLoot(List<GroundLoot.Entry> entries) { loot = List.copyOf(entries); }
}
