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
import java.util.Collections;
import java.util.List;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;

/** A five-room U around spawn, in stable instance world coordinates. No NPC state. */
public final class OpeningRoute
{
	/** Gauntlet rooms are 16x16 tiles (live walls at x=11312, y=10672/10688). Door objects sit on the
	 *  inner wall faces, so the two spawn side doors are only ~14 apart; never use that spacing as the pitch. */
	static final int ROOM_PITCH = 16;
	private int centerX, centerY, pitch, bossX, bossY;
	private List<Point> rooms = Collections.emptyList();
	private int step;
	private boolean finished;
	private String endReason;

	public void reset()
	{
		pitch = 0;
		step = 0;
		rooms = Collections.emptyList();
		finished = false;
		endReason = null;
	}

	/** Initialize from the game's public spawn marker, without a boss/barrier object. */
	public boolean initializeFromMap(List<Point> exits, Point home, int startRoom)
	{
		if (pitch != 0 || finished)
			return pitch != 0;
		Point boss = MapCoordinates.getBossDirection(startRoom);
		if (boss == null || exits.size() < 2)
			return false;
		int bx = boss.getX(), by = boss.getY();
		Point bestCenter = null;
		int bestScore = Integer.MAX_VALUE;
		// Select the opposing spawn-side pair, not the extrema of neighboring revealed rooms.
		for (int i = 0; i < exits.size(); i++)
			for (int j = i + 1; j < exits.size(); j++)
			{
				Point first = exits.get(i), second = exits.get(j);
				int size = Math.abs(by != 0 ? first.getX() - second.getX() : first.getY() - second.getY());
				int skew = Math.abs(by != 0 ? first.getY() - second.getY() : first.getX() - second.getX());
				// Opposite spawn side doors are 14-16 tiles apart (inner wall faces); anything else is a different
				// wall/room.
				if (size < 12 || size > 18 || skew > 6)
					continue;
				Point center = new Point((first.getX() + second.getX()) / 2, (first.getY() + second.getY()) / 2);
				int across = by != 0 ? Math.abs(home.getX() - center.getX()) : Math.abs(home.getY() - center.getY());
				int along = by != 0 ? Math.abs(home.getY() - center.getY()) : Math.abs(home.getX() - center.getX());
				if (across > size / 2 || along > size / 2)
					continue;
				int score = Math.max(across, along) * 64 + skew + size;
				if (bestCenter == null || score < bestScore || score == bestScore && (center.getX() < bestCenter.getX()
						|| center.getX() == bestCenter.getX() && center.getY() < bestCenter.getY()))
				{
					bestCenter = center;
					bestScore = score;
				}
			}
		if (bestCenter == null)
			return false;
		centerX = bestCenter.getX();
		centerY = bestCenter.getY();
		pitch = ROOM_PITCH;
		bossX = bx;
		bossY = by;
		return true;
	}

	public void observeMapRoom(int startRoom, int column, int row)
	{
		Point relative = MapCoordinates.getRelativeRoom(startRoom, column, row);
		if (relative != null)
			observeRoom(relative);
	}

	/** Complete only the passage actually planned, once; never skip another room. */
	public boolean confirmPassageArrival(Point expectedRoom)
	{
		Point next = getNextRoom();
		if (next == null || !next.equals(expectedRoom))
			return false;
		observeRoom(expectedRoom);
		return true;
	}

	/** Only perpendicular exits can start the U, even when the opposite exit is closer. */
	public boolean isInitialSideDoor(int x, int y)
	{
		if (!isInitialized())
			return false;
		int along = (x - centerX) * bossX + (y - centerY) * bossY;
		int across = (x - centerX) * -bossY + (y - centerY) * bossX;
		return Math.abs(across) > Math.abs(along) && Math.abs(across) >= pitch / 2 - 3 && Math.abs(across) <= pitch / 2 + 3;
	}

	public int getRoomPitch() { return pitch; }
	public Point getRoomCenter(Point room)
	{
		return new Point(centerX + room.getX() * pitch, centerY + room.getY() * pitch);
	}

	/** Hunllef's room relative to spawn (always adjacent, toward the boss); null until initialized. */
	public Point getHunllefRoom() { return isInitialized() ? new Point(bossX, bossY) : null; }

	public boolean isInitialized() { return pitch != 0; }
	public boolean isActive() { return isInitialized() && !finished; }
	public int getStep() { return step; }
	public List<Point> getRemainingRooms()
	{
		return rooms.isEmpty() ? Collections.emptyList() : List.copyOf(rooms.subList(Math.max(0, step - 1), Math.min(5, rooms.size())));
	}

	/** The next loop room needs no visit after all: its work is done without entering it. */
	public void skipRoom(Point room)
	{
		if (isActive() && step < rooms.size() && rooms.get(step).equals(room))
			step++;
	}

	public void finishWithoutHome()
	{
		if (step >= 5)
		{
			finished = true;
			endReason = "five rooms done; no useful home work";
		}
	}

	/** Home mid-loop with nothing left in the loop's rooms but fish: this is the loop's home visit. */
	public void finishAtHome()
	{
		if (isActive() && step > 0)
		{
			step = rooms.size();
			finished = true;
			endReason = "home with only fish left in the loop";
		}
	}

	/** Why the loop stopped, for diagnostics; null while active or uninitialized. */
	public String getEndReason() { return endReason; }

	/** Compact state for logs: geometry, side order and progress. */
	public String describe()
	{
		return isInitialized() ? "center=(" + centerX + "," + centerY + ") pitch=" + pitch + " boss=(" + bossX + ","
				+ bossY + ") rooms=" + rooms + " step=" + step + (finished ? " finished" : " active") : "uninitialized";
	}

	public boolean hasCompletedSweep() { return finished && step >= 5; }

	/** The fifth loop room, from which the loop comes home; null until a side is chosen. Kept after the loop ends. */
	public Point getFinishRoom() { return rooms.size() >= 5 ? rooms.get(4) : null; }
	public Point getOuterRoom(int startRoom)
	{
		if (!isInitialized())
			return null;
		int last = GauntletMap.SIZE - 1;
		int distance = bossX > 0 ? startRoom % GauntletMap.SIZE : bossX < 0 ? last - startRoom % GauntletMap.SIZE
				: bossY > 0 ? last - startRoom / GauntletMap.SIZE : startRoom / GauntletMap.SIZE;
		return new Point(-bossX * distance, -bossY * distance);
	}

	/** Door boundary between two cardinally adjacent rooms, in world coordinates. */
	public Point getBoundary(Point from, Point to)
	{
		return new Point(centerX + (from.getX() + to.getX()) * pitch / 2, centerY + (from.getY() + to.getY()) * pitch / 2);
	}

	public Point getRoomAt(int x, int y)
	{
		return new Point(Math.floorDiv(x - centerX + pitch / 2, pitch), Math.floorDiv(y - centerY + pitch / 2, pitch));
	}

	/** The room a world tile is in. */
	public Point getRoomAt(WorldPoint point) { return getRoomAt(point.getX(), point.getY()); }

	/** Nearest side first. A user's actual side-room entry can override the initial suggestion. */
	public void chooseSide(boolean reverse)
	{
		if (!isActive() || step > 0)
			return;
		int sx = -bossY * (reverse ? -1 : 1), sy = bossX * (reverse ? -1 : 1);
		int ax = -bossX, ay = -bossY;
		rooms = new ArrayList<>();
		rooms.add(new Point(sx, sy));
		rooms.add(new Point(sx + ax, sy + ay));
		rooms.add(new Point(ax, ay));
		rooms.add(new Point(-sx + ax, -sy + ay));
		rooms.add(new Point(-sx, -sy));
		rooms.add(GauntletMap.HOME);
	}

	public void observe(int x, int y)
	{
		if (!isActive() || rooms.isEmpty())
			return;
		Point current = getRoomAt(x, y);
		// Lighting from the threshold is not entering the next room.
		if (!isRoomInterior(current, x, y))
			return;
		observeRoom(current);
		if (!isActive())
			return;
		// A one-tile threshold excursion while harvesting must not abandon the five-room route.
		Point center = getRoomCenter(current);
		boolean committed = Math.max(Math.abs(x - center.getX()), Math.abs(y - center.getY())) <= pitch / 4;
		// Once the U has started, a side-room detour or an early home visit is an interruption, not the end:
		// guidance resumes at the next unvisited loop room. Before the first loop room (e.g. enabling mid-run
		// elsewhere), an off-loop room still falls back to adaptive guidance.
		if (committed && step == 0 && !current.equals(GauntletMap.HOME) && !rooms.contains(current))
		{
			finished = true;
			endReason = "entered off-loop room " + current + " before the first loop room";
		}
	}

	private void observeRoom(Point current)
	{
		if (!isActive() || rooms.isEmpty())
			return;
		if (step == 0 && current.equals(new Point(-rooms.get(0).getX(), -rooms.get(0).getY())))
			chooseSide(current.getX() == bossY && current.getY() == -bossX);
		// Do not skip unvisited rooms after a detour or teleport.
		if (step < rooms.size() && current.equals(rooms.get(step)))
		{
			step++;
		}
		if (step >= rooms.size())
		{
			finished = true;
			endReason = "completed all five rooms and returned home";
		}
	}

	/** The known exit after finishing a loop room, including the first revealed room before entry. */
	public Point getRoomAfter(Point room)
	{
		int index = rooms.indexOf(room);
		return isActive() && index >= 0 && index + 1 < rooms.size() ? rooms.get(index + 1) : null;
	}

	/** Latched room entry; current position/map updates do not undo an observed visit. */
	public boolean hasEntered(Point room)
	{
		int index = rooms.indexOf(room);
		return index >= 0 && index < step;
	}

	/** Most recently entered U-loop room, retained during brief boundary/side excursions. */
	public Point getCurrentRoom()
	{
		return step > 0 && step <= 5 ? rooms.get(step - 1) : GauntletMap.HOME;
	}

	public Point getNextRoom() { return isActive() && !rooms.isEmpty() ? rooms.get(step) : null; }
	public Point getDestination()
	{
		Point next = getNextRoom();
		return next == null ? null : new Point(centerX + next.getX() * pitch, centerY + next.getY() * pitch);
	}

	/** Cross the observed doorway footprint, not a guessed room-center tile. */
	public Point getEntryDestination(Point min, Point max)
	{
		Point next = getNextRoom();
		if (next == null)
			return null;
		Point previous = step == 0 ? GauntletMap.HOME : rooms.get(step - 1);
		int dx = next.getX() - previous.getX(), dy = next.getY() - previous.getY();
		int x = dx > 0 ? max.getX() + 2 : dx < 0 ? min.getX() - 2 : (min.getX() + max.getX()) / 2;
		int y = dy > 0 ? max.getY() + 2 : dy < 0 ? min.getY() - 2 : (min.getY() + max.getY()) / 2;
		return new Point(x, y);
	}

	public boolean isRoomInterior(Point room, int x, int y)
	{
		return getRoomAt(x, y).equals(room) && Math.abs(x - (centerX + room.getX() * pitch)) < pitch / 2
				&& Math.abs(y - (centerY + room.getY() * pitch)) < pitch / 2;
	}

	public boolean isNextDoor(int x, int y)
	{
		Point next = getNextRoom();
		if (next == null)
			return false;
		if (step == 0)
		{
			int across = (x - centerX) * next.getX() + (y - centerY) * next.getY();
			return isInitialSideDoor(x, y) && across > 0;
		}
		Point previous = rooms.get(step - 1);
		int mx = centerX + (previous.getX() + next.getX()) * pitch / 2;
		int my = centerY + (previous.getY() + next.getY()) * pitch / 2;
		return Math.abs(x - mx) <= 3 && Math.abs(y - my) <= 3;
	}

	public String getLabel(boolean gathering)
	{
		// step counts entered rooms; resources belong to that room, exits to the next.
		int room = gathering ? Math.max(1, step) : step + 1;
		return room > 5 ? "Return home" : "Opening loop " + room + "/5";
	}
}
