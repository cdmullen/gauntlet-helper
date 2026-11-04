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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default-off attempt recorder: one JSON line per tick with only what changed since the previous tick, so a live run
 * can be read back. A write failure stops the recording rather than the plugin.
 *
 * <p>Line types: {@code {"type":"start",...}} with the planning settings, then {@code {"t":tick,...}} lines holding
 * changed values (a JSON null means the value went away), set additions as {@code "key+"}, removals as
 * {@code "key-"} and events as {@code "ev"}, then {@code {"type":"end",...}}. A tick with no change has no line.</p>
 *
 * <p>Format 11 shrinks the lines without losing anything: a {@code "key-"} of {@value #ALL_REMOVED} removes every
 * previous member (a scene reload re-adds every object with new scene coordinates); an NPC that only moved is one
 * {@code "npc~"} entry ({@link #describeMove}) instead of a removal and an addition; a run-length map (collision,
 * tiles, floors; {@link #MAP_KEYS}) is written as the changes from the key's previous map where that is shorter
 * ({@link AttemptFrame#encodeDelta}, shifted by how far the {@code base} value moved, since the maps are
 * scene-relative); and "=" is written as itself, not {@code \u003d}. Readers apply {@code -}, then {@code ~}, then
 * {@code +}.</p>
 *
 * <p>State is owned by the client thread; file writes run on a single background thread.</p>
 */
public final class AttemptRecorder
{
	public static final int FORMAT = 11;
	/** Value keys holding scene-relative run-length maps, each written as a delta from its previous value when shorter. */
	public static final Set<String> MAP_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"collision", "tiles", "templates", "overlay", "underlay", "heights")));
	/** The value whose first two numbers are the scene base: how far it moved is the maps' shift. */
	public static final String BASE_KEY = "base";
	/** The set whose members move: a "npc~" entry rewrites one member's tile and local point in place. */
	public static final String MOVING_SET = "npc";
	/** A "key-" value meaning every member of the previous tick was removed. */
	public static final String ALL_REMOVED = "*";
	private static final Logger log = LoggerFactory.getLogger(AttemptRecorder.class);
	private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	/** Opens the writer for one attempt file. Called on the writer thread. */
	public interface Sink
	{
		Writer open(String name) throws IOException;

		/**
		 * Deletes the oldest recordings so that at most keep remain, counting the one just opened; 0 keeps them all.
		 * Called on the writer thread after each open. Sinks without files keep nothing to delete.
		 */
		default void deleteOldRecordings(int keep) throws IOException
		{
		}
	}

	/** A recording file and when it was last written, for choosing which old recordings to delete. */
	static final class StoredRecording
	{
		final String path;
		final long modified;

		StoredRecording(String path, long modified)
		{
			this.path = path;
			this.modified = modified;
		}
	}

	/** Where the attempts folder lives; called on the writer thread, since finding it may touch the disk. */
	public interface DirectoryProvider
	{
		Filepath get() throws IOException;
	}

	private final Gson gson;
	private final Sink sink;
	private final Executor writer;
	private final ExecutorService ownedWriter;
	private final Map<String, Object> lastValues = new HashMap<>();
	private final Map<String, TreeSet<String>> lastSets = new HashMap<>();
	private final List<String> pendingEvents = new ArrayList<>();
	private String result;
	private boolean recording;
	private int keepRecordings;
	private int lastTick;
	private String name;
	// Written by the writer thread, read on the client thread.
	private volatile boolean failed;
	// Only touched on the writer thread.
	private Writer out;

	/** Live recorder: files in the given directory, written on its own daemon thread. */
	public AttemptRecorder(Gson gson, DirectoryProvider directory)
	{
		this(gson, createFileSink(directory), Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "cg-attempt-recorder");
			thread.setDaemon(true);
			return thread;
		}));
	}

	/**
	 * Any sink and executor; Runnable::run writes synchronously. An ExecutorService is shut down by close.
	 */
	public AttemptRecorder(Gson gson, Sink sink, Executor writer)
	{
		// Members and maps are full of "=", which Gson's HTML escaping would write as six characters each.
		this.gson = gson.newBuilder().serializeNulls().disableHtmlEscaping().create();
		this.sink = sink;
		this.writer = writer;
		this.ownedWriter = writer instanceof ExecutorService ? (ExecutorService) writer : null;
	}

	public boolean isRecording()
	{
		return recording;
	}

	/** Setting Keep recordings: how many recordings the next start leaves on disk, itself included; 0 keeps all. */
	public void setKeepRecordings(int keep)
	{
		keepRecordings = Math.max(0, keep);
	}

	/**
	 * The folder under the attempts directory for one run: the gauntlet (corrupted or regular) and the armor tier
	 * the run was planned for, e.g. {@code corrupted-gauntlet/t2}.
	 */
	public static String getAttemptFolder(boolean corrupted, String tier)
	{
		return (corrupted ? "corrupted-gauntlet" : "gauntlet") + "/" + tier.toLowerCase(Locale.ROOT);
	}

	/** Starts a new attempt file directly in the attempts directory. */
	public void start(int tick, String reason, Map<String, Object> header)
	{
		start(tick, reason, header, "");
	}

	/**
	 * Starts a new attempt file in a folder below the attempts directory (empty for the directory itself). The
	 * header holds settings and anything else fixed for the attempt.
	 */
	public void start(int tick, String reason, Map<String, Object> header, String folder)
	{
		if (recording)
		{
			stop(tick, "restarted");
		}
		recording = true;
		failed = false;
		lastTick = tick;
		lastValues.clear();
		lastSets.clear();
		pendingEvents.clear();
		result = null;
		name = (folder.isEmpty() ? "" : folder + "/") + "attempt-" + LocalDateTime.now().format(FILE_TIME) + ".jsonl";
		String fileName = name;
		JsonObject line = new JsonObject();
		line.addProperty("type", "start");
		line.addProperty("format", FORMAT);
		line.addProperty("t", tick);
		line.addProperty("started", LocalDateTime.now().toString());
		line.addProperty("reason", reason);
		line.add("header", gson.toJsonTree(header == null ? new LinkedHashMap<>() : header));
		String text = gson.toJson(line);
		int keep = keepRecordings;
		writer.execute(() ->
		{
			try
			{
				closeQuietly();
				out = sink.open(fileName);
				writeLine(text);
			}
			catch (IOException | RuntimeException e)
			{
				fail(e);
				return;
			}
			deleteOldRecordings(keep);
		});
		log.info("Gauntlet attempt recorder: recording {} ({})", fileName, reason);
	}

	/** Something that happened between ticks (a death, a drop click, a loading screen). Written with the next tick. */
	public void recordEvent(String description)
	{
		if (recording)
		{
			pendingEvents.add(description);
		}
	}

	/** How the attempt ended so far, written as "result" on the end line. A recording never given one has none. */
	public void setResult(String result)
	{
		if (recording)
		{
			this.result = result;
		}
	}

	/** Writes the changes since the previous frame. Does nothing while not recording. */
	public void record(AttemptFrame frame)
	{
		if (!recording)
		{
			return;
		}
		if (failed)
		{
			recording = false;
			return;
		}
		lastTick = frame.tick;
		JsonObject line = new JsonObject();
		line.addProperty("t", frame.tick);
		boolean changed = false;
		int[] previousBase = readBase(lastValues.get(BASE_KEY));
		int[] base = frame.values.containsKey(BASE_KEY) ? readBase(frame.values.get(BASE_KEY)) : previousBase;
		for (Map.Entry<String, Object> entry : frame.values.entrySet())
		{
			Object value = entry.getValue();
			if (Objects.equals(value, lastValues.get(entry.getKey())))
			{
				continue;
			}
			line.add(entry.getKey(), gson.toJsonTree(shorten(entry.getKey(), value, previousBase, base)));
			changed = true;
			if (value == null)
			{
				lastValues.remove(entry.getKey());
			}
			else
			{
				lastValues.put(entry.getKey(), value);
			}
		}
		for (Map.Entry<String, TreeSet<String>> entry : frame.sets.entrySet())
		{
			String key = entry.getKey();
			TreeSet<String> previous = lastSets.getOrDefault(key, new TreeSet<>());
			TreeSet<String> current = entry.getValue();
			List<String> added = new ArrayList<>();
			List<String> removed = new ArrayList<>();
			List<String> moved = new ArrayList<>();
			for (String member : current)
			{
				if (!previous.contains(member))
				{
					added.add(member);
				}
			}
			for (String member : previous)
			{
				if (!current.contains(member))
				{
					removed.add(member);
				}
			}
			if (key.equals(MOVING_SET))
			{
				extractMoves(removed, added, moved);
			}
			// Removals first, then moves, then additions: the order a reader applies them in.
			if (!removed.isEmpty())
			{
				line.add(key + "-", removed.size() == previous.size() ? new JsonPrimitive(ALL_REMOVED) : gson.toJsonTree(removed));
			}
			if (!moved.isEmpty())
			{
				line.add(key + "~", gson.toJsonTree(moved));
			}
			if (!added.isEmpty())
			{
				line.add(key + "+", gson.toJsonTree(added));
			}
			changed |= !added.isEmpty() || !removed.isEmpty() || !moved.isEmpty();
			lastSets.put(key, current);
		}
		if (!pendingEvents.isEmpty())
		{
			line.add("ev", gson.toJsonTree(new ArrayList<>(pendingEvents)));
			pendingEvents.clear();
			changed = true;
		}
		if (changed)
		{
			write(gson.toJson(line));
		}
	}

	/** A map as the changes from the key's previous map when that is shorter and both scene bases are known. */
	private Object shorten(String key, Object value, int[] previousBase, int[] base)
	{
		Object previous = lastValues.get(key);
		if (!MAP_KEYS.contains(key) || !(value instanceof String) || !(previous instanceof String) || previousBase == null || base == null)
		{
			return value;
		}
		String delta = AttemptFrame.encodeDelta((String) previous, (String) value, base[0] - previousBase[0], base[1] - previousBase[1]);
		return delta == null ? value : delta;
	}

	/** The first two numbers of a base value (a list of numbers, or a JSON array), or null. */
	static int[] readBase(Object value)
	{
		if (value instanceof List && ((List<?>) value).size() >= 2 && ((List<?>) value).get(0) instanceof Number)
		{
			return new int[]{((Number) ((List<?>) value).get(0)).intValue(), ((Number) ((List<?>) value).get(1)).intValue()};
		}
		if (value instanceof JsonArray && ((JsonArray) value).size() >= 2)
		{
			return new int[]{((JsonArray) value).get(0).getAsInt(), ((JsonArray) value).get(1).getAsInt()};
		}
		return null;
	}

	/** Pairs each removed member with the added member it moved to, turning both into one move entry. */
	private static void extractMoves(List<String> removed, List<String> added, List<String> moved)
	{
		for (Iterator<String> before = removed.iterator(); before.hasNext(); )
		{
			String old = before.next();
			for (Iterator<String> after = added.iterator(); after.hasNext(); )
			{
				String move = describeMove(old, after.next());
				if (move != null)
				{
					moved.add(move);
					after.remove();
					before.remove();
					break;
				}
			}
		}
	}

	/**
	 * The move entry "index@x,y[ l=a,b]" from one member to the next when only its tile or local point changed, or
	 * null. Members are "id#index@x,y token..."; the " l=" token (the local point, kept in the entry whenever the
	 * member has one) may differ, every other token must be the same.
	 */
	public static String describeMove(String before, String after)
	{
		int hash = before.indexOf('#');
		int at = before.indexOf('@');
		if (hash < 0 || at < hash || after.indexOf('@') < 0 || !after.startsWith(before.substring(0, at + 1)))
		{
			return null;
		}
		String[] oldTokens = before.substring(at + 1).split(" ");
		String[] newTokens = after.substring(at + 1).split(" ");
		if (oldTokens.length != newTokens.length)
		{
			return null;
		}
		String local = null;
		for (int i = 1; i < oldTokens.length; i++)
		{
			if (oldTokens[i].startsWith("l=") && newTokens[i].startsWith("l="))
			{
				local = newTokens[i];
			}
			else if (!oldTokens[i].equals(newTokens[i]))
			{
				return null;
			}
		}
		return before.substring(hash + 1, at + 1) + newTokens[0] + (local == null ? "" : " " + local);
	}

	/** The member after a move entry from {@link #describeMove}: its tile and local point replaced in place. */
	public static String applyMove(String member, String move)
	{
		int at = member.indexOf('@');
		String[] tokens = member.substring(at + 1).split(" ");
		String[] change = move.substring(move.indexOf('@') + 1).split(" ");
		tokens[0] = change[0];
		for (int i = 1; i < tokens.length; i++)
		{
			if (tokens[i].startsWith("l="))
			{
				tokens[i] = change[1];
			}
		}
		return member.substring(0, at + 1) + String.join(" ", tokens);
	}

	/** The index a move entry ("index@x,y ...") names. */
	public static String getMoveIndex(String move)
	{
		return move.substring(0, move.indexOf('@'));
	}

	/** The index of a member ("id#index@x,y ..."), or "" for a member without one. */
	public static String getMemberIndex(String member)
	{
		int hash = member.indexOf('#');
		int at = member.indexOf('@');
		return hash < 0 || at < hash ? "" : member.substring(hash + 1, at);
	}

	/** Ends the current recording, if any. */
	public void stop(int tick, String reason)
	{
		if (!recording)
		{
			return;
		}
		recording = false;
		JsonObject line = new JsonObject();
		line.addProperty("type", "end");
		line.addProperty("t", Math.max(tick, lastTick));
		line.addProperty("reason", reason);
		if (result != null)
		{
			line.addProperty("result", result);
		}
		if (!pendingEvents.isEmpty())
		{
			line.add("ev", gson.toJsonTree(new ArrayList<>(pendingEvents)));
			pendingEvents.clear();
		}
		write(gson.toJson(line));
		writer.execute(this::closeQuietly);
		log.info("Gauntlet attempt recorder: finished {} ({})", name, reason);
	}

	/** Plugin shutdown: end the recording and let the writer thread finish its queue. */
	public void close(int tick)
	{
		stop(tick, "plugin stopped");
		if (ownedWriter != null)
		{
			ownedWriter.shutdown();
		}
	}

	private void write(String text)
	{
		writer.execute(() ->
		{
			if (out == null || failed)
			{
				return;
			}
			try
			{
				writeLine(text);
			}
			catch (IOException e)
			{
				fail(e);
			}
		});
	}

	private void writeLine(String text) throws IOException
	{
		out.write(text);
		out.write('\n');
		// Flushed every line so a client crash keeps everything up to the last tick.
		out.flush();
	}

	private void fail(Exception e)
	{
		if (!failed)
		{
			log.warn("Gauntlet attempt recorder: stopped, could not write {}", name, e);
		}
		failed = true;
		closeQuietly();
	}

	/** Cleanup never stops the new recording: a failure is logged and the old files stay. */
	private void deleteOldRecordings(int keep)
	{
		try
		{
			sink.deleteOldRecordings(keep);
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Gauntlet attempt recorder: could not delete old recordings", e);
		}
	}

	/**
	 * The recordings to delete so that keep remain with the current one, which is never in the list: the oldest by
	 * last write, then by name (names hold the start time). Empty when keep is 0.
	 */
	static List<String> findExpired(List<StoredRecording> others, int keep)
	{
		if (keep <= 0 || others.size() < keep)
			return new ArrayList<>();
		return others.stream()
				.sorted(Comparator.comparingLong((StoredRecording r) -> r.modified).thenComparing(r -> r.path).reversed())
				.skip(keep - 1)
				.map(r -> r.path)
				.collect(Collectors.toList());
	}

	private void closeQuietly()
	{
		if (out == null)
		{
			return;
		}
		try
		{
			out.close();
		}
		catch (IOException e)
		{
			log.debug("Gauntlet attempt recorder: close failed", e);
		}
		out = null;
	}

	private static Sink createFileSink(DirectoryProvider directory)
	{
		return new FileSink(directory);
	}

	/** Attempt files below the attempts directory; only the writer thread uses it. */
	private static final class FileSink implements Sink
	{
		private final DirectoryProvider directory;
		private Filepath root;
		private Filepath current;

		FileSink(DirectoryProvider directory)
		{
			this.directory = directory;
		}

		@Override
		public Writer open(String fileName) throws IOException
		{
			root = directory.get();
			Filepath file = resolve(root, fileName);
			// Two attempts started in the same second get a numbered suffix rather than overwriting.
			for (int i = 2; file.exists(); i++)
			{
				file = resolve(root, fileName.replace(".jsonl", "-" + i + ".jsonl"));
			}
			file.getParent().createDirectories();
			current = file;
			return file.openWriter();
		}

		/** Only attempt-*.jsonl files anywhere below the attempts directory count; nothing else there is touched. */
		@Override
		public void deleteOldRecordings(int keep) throws IOException
		{
			if (keep <= 0 || root == null || !root.exists())
				return;
			Map<String, Filepath> files = new HashMap<>();
			List<StoredRecording> others = new ArrayList<>();
			try (Stream<Filepath> walk = root.walk())
			{
				for (Filepath file : (Iterable<Filepath>) walk::iterator)
				{
					String name = file.getFileName();
					if (!file.isFile() || file.equals(current) || !name.startsWith("attempt-") || !name.endsWith(".jsonl"))
						continue;
					files.put(file.toString(), file);
					others.add(new StoredRecording(file.toString(), file.getLastModifiedTime().toMillis()));
				}
			}
			for (String path : findExpired(others, keep))
			{
				// One file that can't be deleted (open elsewhere, say) doesn't stop the rest.
				try
				{
					files.get(path).deleteIfExists();
					log.info("Gauntlet attempt recorder: deleted old recording {}", path);
				}
				catch (IOException e)
				{
					log.warn("Gauntlet attempt recorder: could not delete old recording {}", path, e);
				}
			}
		}
	}

	/** The file for a name like {@code corrupted-gauntlet/t2/attempt-....jsonl} below the attempts directory. */
	private static Filepath resolve(Filepath root, String fileName)
	{
		Filepath path = root;
		for (String segment : fileName.split("/"))
		{
			path = path.joinSegment(segment);
		}
		return path;
	}
}
