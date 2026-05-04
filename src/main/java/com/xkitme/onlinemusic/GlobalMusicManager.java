package com.xkitme.onlinemusic;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardCriterion;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class GlobalMusicManager implements AutoCloseable {
	private static final long FALLBACK_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(8);
	private static final long MIN_WATCHDOG_MILLIS = TimeUnit.MINUTES.toMillis(10);
	private static final long WATCHDOG_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(2);
	private static final long CLIENT_FINISH_GRACE_MILLIS = TimeUnit.SECONDS.toMillis(10);
	private static final String SIDEBAR_OBJECTIVE = "gmusic_now";
	private static final int MAX_HISTORY_SIZE = 20;

	private final Object lock = new Object();
	private final Deque<GlobalMusicTrack> queue = new ArrayDeque<>();
	private final Deque<GlobalMusicTrack> history = new ArrayDeque<>();
	private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Global Music Queue");
		thread.setDaemon(true);
		return thread;
	});

	private GlobalMusicTrack currentTrack;
	private long currentStartedAtMillis;
	private long pausedElapsedMillis;
	private long generation;
	private boolean paused;
	private ScoreboardObjective previousSidebarObjective;

	public void playNow(MinecraftServer server, GlobalMusicTrack track) {
		synchronized (lock) {
			queue.clear();
			startTrack(server, track);
		}
	}

	public int queue(MinecraftServer server, GlobalMusicTrack track) {
		synchronized (lock) {
			if (currentTrack == null) {
				startTrack(server, track);
				return 0;
			}
			queue.addLast(track);
			broadcastQueue(server);
			return queue.size();
		}
	}

	public boolean setMuted(MinecraftServer server, ServerPlayerEntity player, boolean muted) {
		synchronized (lock) {
			boolean changed = muted ? mutedPlayers.add(player.getUuid()) : mutedPlayers.remove(player.getUuid());
			if (muted) {
				sendStop(player);
			} else if (currentTrack != null) {
				sendPlay(player, currentTrack, elapsedMillis());
			}
			sendQueue(player);
			return changed;
		}
	}

	public boolean isMuted(ServerPlayerEntity player) {
		return mutedPlayers.contains(player.getUuid());
	}

	public boolean skip(MinecraftServer server) {
		synchronized (lock) {
			if (currentTrack == null) {
				return false;
			}
			playNextOrStop(server);
			return true;
		}
	}

	public boolean previous(MinecraftServer server) {
		synchronized (lock) {
			GlobalMusicTrack previous = history.pollLast();
			if (previous == null) {
				return false;
			}
			if (currentTrack != null) {
				queue.addFirst(currentTrack);
			}
			startTrack(server, previous);
			return true;
		}
	}

	public boolean pause(MinecraftServer server) {
		synchronized (lock) {
			if (currentTrack == null || paused) {
				return false;
			}
			pausedElapsedMillis = elapsedMillis();
			paused = true;
			generation++;
			broadcastPause(server);
			broadcastQueue(server);
			return true;
		}
	}

	public boolean resume(MinecraftServer server) {
		synchronized (lock) {
			if (currentTrack == null || !paused) {
				return false;
			}
			paused = false;
			currentStartedAtMillis = System.currentTimeMillis() - Math.max(0L, pausedElapsedMillis);
			generation++;
			broadcastPlay(server, currentTrack, generation, pausedElapsedMillis);
			broadcastQueue(server);
			scheduleNext(server, generation, currentTrack);
			return true;
		}
	}

	public GlobalMusicTrack removeQueued(MinecraftServer server, int oneBasedIndex) {
		synchronized (lock) {
			GlobalMusicTrack removed = removeAt(queue, oneBasedIndex);
			if (removed != null) {
				broadcastQueue(server);
			}
			return removed;
		}
	}

	public GlobalMusicTrack topQueued(MinecraftServer server, int oneBasedIndex) {
		synchronized (lock) {
			GlobalMusicTrack moved = removeAt(queue, oneBasedIndex);
			if (moved != null) {
				queue.addFirst(moved);
				broadcastQueue(server);
			}
			return moved;
		}
	}

	public void clientFinished(MinecraftServer server, ServerPlayerEntity player, long playId) {
		synchronized (lock) {
			if (currentTrack == null || paused || generation != playId || mutedPlayers.contains(player.getUuid())) {
				return;
			}
			if (currentTrack.durationMillis() > 0L
					&& elapsedMillis() + CLIENT_FINISH_GRACE_MILLIS < currentTrack.durationMillis()) {
				return;
			}
			playNextOrStop(server);
		}
	}

	public boolean stop(MinecraftServer server) {
		synchronized (lock) {
			boolean hadWork = currentTrack != null || !queue.isEmpty();
			queue.clear();
			history.clear();
			currentTrack = null;
			currentStartedAtMillis = 0L;
			pausedElapsedMillis = 0L;
			paused = false;
			generation++;
			clearSidebar(server);
			broadcastStop(server);
			broadcastQueue(server);
			return hadWork;
		}
	}

	public GlobalMusicTrack currentTrack() {
		synchronized (lock) {
			return currentTrack;
		}
	}

	public List<GlobalMusicTrack> queuedTracks() {
		synchronized (lock) {
			return new ArrayList<>(queue);
		}
	}

	public boolean isPaused() {
		synchronized (lock) {
			return paused;
		}
	}

	public void sendQueueTo(ServerPlayerEntity player) {
		synchronized (lock) {
			sendQueue(player);
		}
	}

	@Override
	public void close() {
		scheduler.shutdownNow();
	}

	private void scheduleNext(MinecraftServer server, long expectedGeneration, GlobalMusicTrack track) {
		long baseDelayMillis = track.durationMillis() > 0 ? track.durationMillis() * 2L + WATCHDOG_GRACE_MILLIS
				: FALLBACK_DURATION_MILLIS;
		long delayMillis = Math.max(baseDelayMillis, MIN_WATCHDOG_MILLIS);
		scheduler.schedule(() -> server.execute(() -> watchdogAdvance(server, expectedGeneration)),
				delayMillis, TimeUnit.MILLISECONDS);
	}

	private void watchdogAdvance(MinecraftServer server, long expectedGeneration) {
		synchronized (lock) {
			if (generation != expectedGeneration) {
				return;
			}
			playNextOrStop(server);
		}
	}

	private void playNextOrStop(MinecraftServer server) {
		rememberCurrent();
		GlobalMusicTrack next = queue.pollFirst();
		if (next == null) {
			currentTrack = null;
			currentStartedAtMillis = 0L;
			pausedElapsedMillis = 0L;
			paused = false;
			generation++;
			clearSidebar(server);
			broadcastStop(server);
			broadcastQueue(server);
			return;
		}

		startTrack(server, next);
	}

	private void startTrack(MinecraftServer server, GlobalMusicTrack next) {
		generation++;
		currentTrack = next;
		currentStartedAtMillis = System.currentTimeMillis();
		pausedElapsedMillis = 0L;
		paused = false;
		clearSidebar(server);
		broadcastPlay(server, next, generation);
		broadcastQueue(server);
		scheduleNext(server, generation, next);
	}

	private void broadcastPlay(MinecraftServer server, GlobalMusicTrack track, long playId) {
		broadcastPlay(server, track, playId, 0L);
	}

	private void broadcastPlay(MinecraftServer server, GlobalMusicTrack track, long playId, long elapsedMillis) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendPlay(player, track, playId, elapsedMillis);
		}
	}

	private void broadcastStop(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendStop(player);
		}
	}

	private void broadcastPause(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendPause(player);
		}
	}

	private void broadcastQueue(MinecraftServer server) {
		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			sendQueue(player);
		}
	}

	private void sendPlay(ServerPlayerEntity player, GlobalMusicTrack track, long elapsedMillis) {
		sendPlay(player, track, generation, elapsedMillis);
	}

	private void sendPlay(ServerPlayerEntity player, GlobalMusicTrack track, long playId, long elapsedMillis) {
		if (mutedPlayers.contains(player.getUuid()) || !ServerPlayNetworking.canSend(player, MusicNetworking.GLOBAL_PLAY)) {
			return;
		}

		PacketByteBuf buffer = PacketByteBufs.create();
		buffer.writeString(track.title());
		buffer.writeString(track.url());
		buffer.writeString(track.requestedBy());
		buffer.writeString(track.coverUrl());
		buffer.writeLong(playId);
		buffer.writeLong(Math.max(0L, elapsedMillis));
		buffer.writeLong(Math.max(0L, track.durationMillis()));
		buffer.writeVarInt(track.lyrics().size());
		for (LyricLine lyric : track.lyrics()) {
			buffer.writeLong(lyric.timeMillis());
			buffer.writeString(lyric.text());
		}
		ServerPlayNetworking.send(player, MusicNetworking.GLOBAL_PLAY, buffer);
	}

	private void sendQueue(ServerPlayerEntity player) {
		if (!ServerPlayNetworking.canSend(player, MusicNetworking.GLOBAL_QUEUE)) {
			return;
		}

		PacketByteBuf buffer = PacketByteBufs.create();
		buffer.writeBoolean(currentTrack != null);
		if (currentTrack != null) {
			writeTrackSummary(buffer, currentTrack);
		}

		buffer.writeVarInt(queue.size());
		for (GlobalMusicTrack track : queue) {
			writeTrackSummary(buffer, track);
		}
		buffer.writeBoolean(paused);
		buffer.writeLong(elapsedMillis());
		buffer.writeLong(currentTrack == null ? 0L : currentTrack.durationMillis());
		ServerPlayNetworking.send(player, MusicNetworking.GLOBAL_QUEUE, buffer);
	}

	private void writeTrackSummary(PacketByteBuf buffer, GlobalMusicTrack track) {
		buffer.writeString(track.title());
		buffer.writeString(track.requestedBy());
		buffer.writeString(track.coverUrl());
	}

	private void sendStop(ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, MusicNetworking.GLOBAL_STOP)) {
			ServerPlayNetworking.send(player, MusicNetworking.GLOBAL_STOP, PacketByteBufs.empty());
		}
	}

	private void sendPause(ServerPlayerEntity player) {
		if (ServerPlayNetworking.canSend(player, MusicNetworking.GLOBAL_PAUSE)) {
			ServerPlayNetworking.send(player, MusicNetworking.GLOBAL_PAUSE, PacketByteBufs.empty());
		}
	}

	private long elapsedMillis() {
		if (paused) {
			return pausedElapsedMillis;
		}
		return currentStartedAtMillis == 0L ? 0L : System.currentTimeMillis() - currentStartedAtMillis;
	}

	private void rememberCurrent() {
		if (currentTrack == null) {
			return;
		}
		history.addLast(currentTrack);
		while (history.size() > MAX_HISTORY_SIZE) {
			history.removeFirst();
		}
	}

	private GlobalMusicTrack removeAt(Deque<GlobalMusicTrack> tracks, int oneBasedIndex) {
		if (oneBasedIndex <= 0 || oneBasedIndex > tracks.size()) {
			return null;
		}

		List<GlobalMusicTrack> copy = new ArrayList<>(tracks);
		GlobalMusicTrack removed = copy.remove(oneBasedIndex - 1);
		tracks.clear();
		tracks.addAll(copy);
		return removed;
	}

	private void updateSidebar(MinecraftServer server, GlobalMusicTrack track) {
		Scoreboard scoreboard = server.getScoreboard();
		ScoreboardObjective currentSidebar = scoreboard.getObjectiveForSlot(Scoreboard.SIDEBAR_DISPLAY_SLOT_ID);
		if (currentSidebar == null || !SIDEBAR_OBJECTIVE.equals(currentSidebar.getName())) {
			previousSidebarObjective = currentSidebar;
		}

		ScoreboardObjective oldObjective = scoreboard.getNullableObjective(SIDEBAR_OBJECTIVE);
		if (oldObjective != null) {
			scoreboard.removeObjective(oldObjective);
		}

		ScoreboardObjective objective = scoreboard.addObjective(SIDEBAR_OBJECTIVE, ScoreboardCriterion.DUMMY,
				Text.literal("当前点歌"), ScoreboardCriterion.RenderType.INTEGER);
		scoreboard.setObjectiveSlot(Scoreboard.SIDEBAR_DISPLAY_SLOT_ID, objective);
		scoreboard.getPlayerScore("歌曲: " + sidebarText(track.title()), objective).setScore(2);
		scoreboard.getPlayerScore("点歌: " + sidebarText(track.requestedBy()), objective).setScore(1);
	}

	private void clearSidebar(MinecraftServer server) {
		Scoreboard scoreboard = server.getScoreboard();
		ScoreboardObjective objective = scoreboard.getNullableObjective(SIDEBAR_OBJECTIVE);
		if (objective != null) {
			scoreboard.removeObjective(objective);
		}

		if (previousSidebarObjective != null
				&& scoreboard.getNullableObjective(previousSidebarObjective.getName()) != null) {
			scoreboard.setObjectiveSlot(Scoreboard.SIDEBAR_DISPLAY_SLOT_ID, previousSidebarObjective);
		}
		previousSidebarObjective = null;
	}

	private String sidebarText(String value) {
		if (value.length() <= 28) {
			return value;
		}
		return value.substring(0, 25) + "...";
	}
}
