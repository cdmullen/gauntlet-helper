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

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Point;

/** Public map state: script 2910 places spawn, 2912 places the player. */
public final class MapCoordinates
{
	private MapCoordinates() { }
	public static Point getBossDirection(int startRoom)
	{
		// Map indices are row * 7 + column. Screen rows grow southwards.
		switch (startRoom)
		{
			case 17:
				return new Point(0, -1);
			case 23:
				return new Point(1, 0);
			case 25:
				return new Point(-1, 0);
			case 31:
				return new Point(0, 1);
			default:
				return null;
		}
	}

	/** Inverse of getBossDirection: the start encoding whose spawn has Hunllef in this direction. */
	public static int getStartRoomFor(Point hunllefDirection)
	{
		if (hunllefDirection == null)
			return -1;
		for (int start : new int[]{17, 23, 25, 31})
			if (hunllefDirection.equals(getBossDirection(start)))
				return start;
		return -1;
	}

	/**
	 * Map-widget squares versus the instance's real compass. The map's Hunllef direction can differ from the
	 * physical one, so world geometry is authoritative and every map varbit
	 * (room found, current room) is converted through this orthogonal transform about the center square.
	 */
	public static final class Orientation
	{
		private static final int[][] ALL = {{1, 0, 0, -1}, {1, 0, 0, 1}, {-1, 0, 0, 1}, {-1, 0, 0, -1}, {0, 1, 1, 0},
				{0, -1, -1, 0}, {0, 1, -1, 0}, {0, -1, 1, 0}};
		private final int a, b, c, d;
		final boolean confirmed;

		private Orientation(int[] matrix, boolean confirmed)
		{
			a = matrix[0];
			b = matrix[1];
			c = matrix[2];
			d = matrix[3];
			this.confirmed = confirmed;
		}

		public static final Orientation IDENTITY = new Orientation(new int[]{1, 0, 0, 1}, false);

		/** World (north-up) vector for a map vector, where a map vector is (column change, -row change). */
		public Point toWorld(Point mapVector)
		{
			return new Point(a * mapVector.getX() + b * mapVector.getY(), c * mapVector.getX() + d * mapVector.getY());
		}

		public Point toMap(Point worldVector)
		{
			return new Point(a * worldVector.getX() + c * worldVector.getY(), b * worldVector.getX() + d * worldVector.getY());
		}

		public boolean isConfirmed() { return confirmed; }

		/**
		 * Candidates mapping the map's Hunllef direction onto the observed one; the north/south flip seen in every live
		 * run first.
		 */
		static List<Orientation> findCandidates(Point mapBoss, Point worldBoss)
		{
			List<Orientation> result = new ArrayList<>();
			for (int[] matrix : ALL)
			{
				Orientation orientation = new Orientation(matrix, false);
				if (orientation.toWorld(mapBoss).equals(worldBoss))
					result.add(orientation);
			}
			return result;
		}

		public static Orientation guess(Point mapBoss, Point worldBoss)
		{
			List<Orientation> found = findCandidates(mapBoss, worldBoss);
			return found.isEmpty() ? IDENTITY : found.get(0);
		}

		/** Settle the ambiguity from one non-home room seen both on the map and physically; null if inconsistent. */
		public static Orientation confirm(Point mapBoss, Point worldBoss, Point mapRoom, Point worldRoom)
		{
			for (Orientation orientation : findCandidates(mapBoss, worldBoss))
				if (orientation.toWorld(mapRoom).equals(worldRoom))
					return new Orientation(new int[]{orientation.a, orientation.b, orientation.c, orientation.d}, true);
			return null;
		}

		@Override
		public String toString()
		{
			return "[" + a + "," + b + ";" + c + "," + d + "]" + (confirmed ? " confirmed" : " provisional");
		}
	}

	/**
	 * Map (column,row) of the game's varbits to the world-oriented grid (column,row) used by GauntletMap, about center D4.
	 */
	public static int[] mapToWorldSquare(Orientation orientation, int column, int row)
	{
		Point worldVector = orientation.toWorld(new Point(column - 3, 3 - row));
		return new int[]{3 + worldVector.getX(), 3 - worldVector.getY()};
	}

	public static int[] worldToMapSquare(Orientation orientation, int column, int row)
	{
		Point mapVector = orientation.toMap(new Point(column - 3, 3 - row));
		return new int[]{3 + mapVector.getX(), 3 - mapVector.getY()};
	}

	public static Point getRelativeRoom(int startRoom, int column, int row)
	{
		if (getBossDirection(startRoom) == null || column < 0 || column >= GauntletMap.SIZE || row < 0 || row >= GauntletMap.SIZE)
			return null;
		return new Point(column - startRoom % GauntletMap.SIZE, startRoom / GauntletMap.SIZE - row);
	}
}
