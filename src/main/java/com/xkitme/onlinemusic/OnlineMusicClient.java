package com.xkitme.onlinemusic;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public final class OnlineMusicClient implements ClientModInitializer {
	private static final int MAX_EARLY_STOP_RETRIES = 12;
	private static final long FINISH_GRACE_MILLIS = 5_000L;
	private static final long RETRY_DELAY_MILLIS = 1_000L;

	private static final OnlineMusicPlayer PLAYER = new OnlineMusicPlayer(Messenger.clientSink(),
			OnlineMusicClient::minecraftMusicVolume);
	private static volatile boolean globalMusicPlaying;
	private static String currentTitle = "";
	private static String currentUrl = "";
	private static long currentPlayId;
	private static long currentDurationMillis;
	private static long lyricStartMillis;
	private static boolean playbackStarted;
	private static boolean finishReported;
	private static boolean globalMusicPaused;
	private static int earlyStopRetries;
	private static long nextRetryAtMillis;

	@Override
	public void onInitializeClient() {
		MusicClientSettings.instance().apply();
		registerGlobalMusicNetworking();
		HudRenderCallback.EVENT.register(MusicHud::render);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
			if (!MusicClientSettings.instance().personalListening()) {
				sendPersonalListeningCommand(false);
			}
		}));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				client.execute(() -> clearClientState(client)));
		ClientLifecycleEvents.CLIENT_STOPPING.register(OnlineMusicClient::clearClientState);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (globalMusicPlaying) {
				client.getMusicTracker().stop();
				client.getSoundManager().stopSounds(null, SoundCategory.MUSIC);
				reportFinishedIfNeeded();
			}
		});
	}

	private void registerGlobalMusicNetworking() {
		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.GLOBAL_PLAY, (client, handler, buffer, responseSender) -> {
			String title = buffer.readString();
			String url = buffer.readString();
			String requestedBy = buffer.readString();
			String coverUrl = buffer.readString();
			long playId = buffer.readLong();
			long startOffsetMillis = buffer.readLong();
			long durationMillis = buffer.readLong();
			int lyricCount = buffer.readVarInt();
			List<LyricLine> lyrics = new ArrayList<>(lyricCount);
			for (int index = 0; index < lyricCount; index++) {
				lyrics.add(new LyricLine(buffer.readLong(), buffer.readString()));
			}

			client.execute(() -> {
				globalMusicPlaying = true;
				currentTitle = title;
				currentUrl = url;
				currentPlayId = playId;
				currentDurationMillis = durationMillis;
				lyricStartMillis = System.currentTimeMillis() - Math.max(0L, startOffsetMillis);
				playbackStarted = false;
				finishReported = false;
				globalMusicPaused = false;
				earlyStopRetries = 0;
				nextRetryAtMillis = 0L;
				client.getMusicTracker().stop();
				client.getSoundManager().stopSounds(null, SoundCategory.MUSIC);
				MusicHud.play(title, requestedBy, coverUrl, lyrics, startOffsetMillis, durationMillis);
				MusicPanelScreen.updatePlaybackPaused(false);
				PLAYER.playNow(new Track("[全局] " + title, url, startOffsetMillis));
				Messenger.clientSink().accept(Messenger.success("点歌人：" + requestedBy + "，歌曲：" + title));
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.GLOBAL_STOP, (client, handler, buffer, responseSender) ->
				client.execute(() -> stopLocalPlayback(client)));

		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.GLOBAL_PAUSE, (client, handler, buffer, responseSender) ->
				client.execute(() -> {
					globalMusicPaused = true;
					PLAYER.stopAll();
					MusicHud.setPaused(true);
					MusicPanelScreen.updatePlaybackPaused(true);
				}));

		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.GLOBAL_QUEUE, (client, handler, buffer, responseSender) -> {
			MusicHud.QueueEntry current = buffer.readBoolean() ? readQueueEntry(buffer) : null;
			int queueSize = buffer.readVarInt();
			List<MusicHud.QueueEntry> queue = new ArrayList<>(queueSize);
			for (int index = 0; index < queueSize; index++) {
				queue.add(readQueueEntry(buffer));
			}
			boolean paused = buffer.readableBytes() > 0 && buffer.readBoolean();
			long elapsedMillis = buffer.readableBytes() >= Long.BYTES ? buffer.readLong() : 0L;
			long durationMillis = buffer.readableBytes() >= Long.BYTES ? buffer.readLong() : 0L;
			client.execute(() -> {
				MusicHud.updateQueue(current, queue);
				MusicHud.setPaused(paused);
				MusicPanelScreen.updatePlayback(current, queue, paused, elapsedMillis, durationMillis);
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.HUD_SETTINGS, (client, handler, buffer, responseSender) -> {
			int setting = buffer.readVarInt();
			boolean enabled = buffer.readBoolean();
			client.execute(() -> {
				MusicClientSettings settings = MusicClientSettings.instance();
				settings.setHudBoolean(setting, enabled);
				settings.saveIfDirty();
				MusicHud.applySettings(settings);
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.OPEN_PANEL, (client, handler, buffer, responseSender) ->
				client.execute(() -> client.setScreen(new MusicPanelScreen(null))));

		ClientPlayNetworking.registerGlobalReceiver(MusicNetworking.PANEL_RESULTS, (client, handler, buffer, responseSender) -> {
			String keywords = buffer.readString();
			int page = buffer.readVarInt();
			boolean success = buffer.readBoolean();
			String message = buffer.readString();
			int resultCount = buffer.readVarInt();
			List<MusicPanelScreen.SearchResult> results = new ArrayList<>(resultCount);
			for (int index = 0; index < resultCount; index++) {
				results.add(new MusicPanelScreen.SearchResult(buffer.readLong(), buffer.readString(),
						buffer.readString(), buffer.readString()));
			}
			client.execute(() -> MusicPanelScreen.receiveResults(keywords, page, success, message, results));
		});
	}

	static void setLocalVolumePercent(int percent) {
		PLAYER.setVolumePercent(percent);
	}

	static int localVolumePercent() {
		return PLAYER.volumePercent();
	}

	static void requestPanelSearch(String keywords, int page) {
		PacketByteBuf buffer = PacketByteBufs.create();
		buffer.writeString(keywords == null ? "" : keywords);
		buffer.writeVarInt(Math.max(0, page));
		ClientPlayNetworking.send(MusicNetworking.PANEL_SEARCH, buffer);
	}

	static void sendPanelCommand(String command) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.player.networkHandler == null) {
			return;
		}
		client.player.networkHandler.sendChatCommand(command);
	}

	static void sendPlayCommand(long songId) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.player.networkHandler == null) {
			return;
		}
		client.player.networkHandler.sendChatCommand("gmusic play " + songId);
	}

	private static double minecraftMusicVolume() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options == null) {
			return 1.0;
		}
		return client.options.getSoundVolume(SoundCategory.MASTER)
				* client.options.getSoundVolume(SoundCategory.MUSIC);
	}

	static void sendPersonalListeningCommand(boolean listening) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null || client.player.networkHandler == null) {
			return;
		}
		client.player.networkHandler.sendChatCommand(listening ? "gmusic on" : "gmusic off");
	}

	private static MusicHud.QueueEntry readQueueEntry(PacketByteBuf buffer) {
		return new MusicHud.QueueEntry(buffer.readString(), buffer.readString(), buffer.readString());
	}

	private static void clearClientState(MinecraftClient client) {
		stopLocalPlayback(client);
		MusicHud.clearAll();
	}

	private static void stopLocalPlayback(MinecraftClient client) {
		globalMusicPlaying = false;
		currentTitle = "";
		currentUrl = "";
		currentPlayId = 0L;
		currentDurationMillis = 0L;
		playbackStarted = false;
		finishReported = false;
		globalMusicPaused = false;
		earlyStopRetries = 0;
		nextRetryAtMillis = 0L;
		PLAYER.stopAll();
		MusicHud.stopPlayback();
		if (client.player != null) {
			client.player.sendMessage(Text.empty(), true);
		}
	}

	private void reportFinishedIfNeeded() {
		if (globalMusicPaused) {
			return;
		}
		if (PLAYER.currentTrack() != null) {
			playbackStarted = true;
			return;
		}

		if (!playbackStarted || finishReported) {
			return;
		}

		long elapsedMillis = System.currentTimeMillis() - lyricStartMillis;
		if (shouldResumeEarlyStop(elapsedMillis)) {
			resumeEarlyStop(elapsedMillis);
			return;
		}

		if (currentDurationMillis <= 0L || elapsedMillis + FINISH_GRACE_MILLIS < currentDurationMillis) {
			return;
		}

		finishReported = true;
		PacketByteBuf buffer = PacketByteBufs.create();
		buffer.writeLong(currentPlayId);
		ClientPlayNetworking.send(MusicNetworking.GLOBAL_FINISHED, buffer);
	}

	private boolean shouldResumeEarlyStop(long elapsedMillis) {
		return !currentUrl.isBlank()
				&& currentDurationMillis > 0L
				&& elapsedMillis + FINISH_GRACE_MILLIS < currentDurationMillis;
	}

	private void resumeEarlyStop(long elapsedMillis) {
		long now = System.currentTimeMillis();
		if (earlyStopRetries >= MAX_EARLY_STOP_RETRIES || now < nextRetryAtMillis) {
			return;
		}

		earlyStopRetries++;
		nextRetryAtMillis = now + RETRY_DELAY_MILLIS;
		long resumeOffsetMillis = Math.max(0L, elapsedMillis - 1_500L);
		PLAYER.playNow(new Track("[全局] " + currentTitle, currentUrl, resumeOffsetMillis));
		Messenger.clientSink().accept(Messenger.warning("播放中断，正在从当前进度继续：" + currentTitle));
	}
}
