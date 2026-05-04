package com.xkitme.onlinemusic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MusicClientSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("x2k_music_client.json");
	private static final MusicClientSettings INSTANCE = load();

	private int volumePercent = 75;
	private boolean lyricsVisible = true;
	private boolean panelVisible = true;
	private boolean lyricAnimation = true;
	private boolean personalListening = true;
	private boolean dirty;

	private MusicClientSettings() {
	}

	public static MusicClientSettings instance() {
		return INSTANCE;
	}

	public int volumePercent() {
		return volumePercent;
	}

	public boolean lyricsVisible() {
		return lyricsVisible;
	}

	public boolean panelVisible() {
		return panelVisible;
	}

	public boolean lyricAnimation() {
		return lyricAnimation;
	}

	public boolean personalListening() {
		return personalListening;
	}

	public void setVolumePercent(int volumePercent) {
		int clamped = clamp(volumePercent, 0, 100);
		if (this.volumePercent != clamped) {
			this.volumePercent = clamped;
			dirty = true;
		}
	}

	public void setLyricsVisible(boolean lyricsVisible) {
		if (this.lyricsVisible != lyricsVisible) {
			this.lyricsVisible = lyricsVisible;
			dirty = true;
		}
	}

	public void setPanelVisible(boolean panelVisible) {
		if (this.panelVisible != panelVisible) {
			this.panelVisible = panelVisible;
			dirty = true;
		}
	}

	public void setLyricAnimation(boolean lyricAnimation) {
		if (this.lyricAnimation != lyricAnimation) {
			this.lyricAnimation = lyricAnimation;
			dirty = true;
		}
	}

	public void setPersonalListening(boolean personalListening) {
		if (this.personalListening != personalListening) {
			this.personalListening = personalListening;
			dirty = true;
		}
	}

	public void setHudBoolean(int setting, boolean enabled) {
		if (setting == MusicNetworking.HUD_SETTING_LYRICS_VISIBLE) {
			setLyricsVisible(enabled);
		} else if (setting == MusicNetworking.HUD_SETTING_PANEL_VISIBLE) {
			setPanelVisible(enabled);
		} else if (setting == MusicNetworking.HUD_SETTING_LYRIC_ANIMATION) {
			setLyricAnimation(enabled);
		}
	}

	public void apply() {
		OnlineMusicClient.setLocalVolumePercent(volumePercent);
		MusicHud.applySettings(this);
	}

	public void saveIfDirty() {
		if (!dirty) {
			return;
		}
		try {
			save();
			dirty = false;
		} catch (IOException ignored) {
			// Client settings are cosmetic; keep playing even if the file is locked.
		}
	}

	private void save() throws IOException {
		Files.createDirectories(CONFIG_PATH.getParent());
		JsonObject root = new JsonObject();
		root.addProperty("volumePercent", volumePercent);
		root.addProperty("lyricsVisible", lyricsVisible);
		root.addProperty("panelVisible", panelVisible);
		root.addProperty("lyricAnimation", lyricAnimation);
		root.addProperty("personalListening", personalListening);
		try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}
	}

	private static MusicClientSettings load() {
		MusicClientSettings settings = new MusicClientSettings();
		if (!Files.exists(CONFIG_PATH)) {
			return settings;
		}

		try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			settings.volumePercent = clamp(intValue(root, "volumePercent", settings.volumePercent), 0, 100);
			settings.lyricsVisible = booleanValue(root, "lyricsVisible", settings.lyricsVisible);
			settings.panelVisible = booleanValue(root, "panelVisible", settings.panelVisible);
			settings.lyricAnimation = booleanValue(root, "lyricAnimation", settings.lyricAnimation);
			settings.personalListening = booleanValue(root, "personalListening", settings.personalListening);
		} catch (Exception ignored) {
			// Keep defaults when the local file is missing or malformed.
		}
		return settings;
	}

	private static int intValue(JsonObject object, String key, int fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
	}

	private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}
}
