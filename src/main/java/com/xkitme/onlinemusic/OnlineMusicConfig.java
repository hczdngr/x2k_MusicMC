package com.xkitme.onlinemusic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.StringJoiner;

public final class OnlineMusicConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("online_music.json");
	private static final Path COOKIE_FILE_PATH = FabricLoader.getInstance().getConfigDir().resolve("x2k_netease_cookie.txt");

	private String neteaseCookie = "";

	public static OnlineMusicConfig load() {
		OnlineMusicConfig config = new OnlineMusicConfig();
		if (!Files.exists(CONFIG_PATH)) {
			return config;
		}

		try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
			JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
			if (root.has("neteaseCookie") && !root.get("neteaseCookie").isJsonNull()) {
				config.neteaseCookie = root.get("neteaseCookie").getAsString();
			}
		} catch (Exception ignored) {
			// Use defaults when the local config is missing or malformed.
		}
		return config;
	}

	public void save() throws IOException {
		Files.createDirectories(CONFIG_PATH.getParent());
		JsonObject root = new JsonObject();
		root.addProperty("neteaseCookie", neteaseCookie);
		try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
			GSON.toJson(root, writer);
		}
	}

	public Path cookieFilePath() {
		return COOKIE_FILE_PATH;
	}

	public void prepareCookieFile() throws IOException {
		ensureCookieFileExists();
	}

	public boolean loadNeteaseCookieFromLocalFile() throws IOException {
		ensureCookieFileExists();
		setNeteaseCookie(Files.readString(COOKIE_FILE_PATH, StandardCharsets.UTF_8));
		return hasMusicU();
	}

	public String neteaseCookie() {
		return neteaseCookie;
	}

	public boolean hasNeteaseCookie() {
		return !neteaseCookie.isBlank();
	}

	public boolean hasMusicU() {
		return !extractCookieValue("MUSIC_U").isBlank();
	}

	public void setNeteaseCookie(String cookieOrMusicU) {
		this.neteaseCookie = normalizeCookie(cookieOrMusicU);
	}

	public void clearNeteaseCookie() {
		this.neteaseCookie = "";
	}

	public String maskedNeteaseCookie() {
		if (neteaseCookie.isBlank()) {
			return "未设置";
		}

		String musicU = extractCookieValue("MUSIC_U");
		if (!musicU.isBlank()) {
			return "MUSIC_U=" + mask(musicU);
		}
		return mask(neteaseCookie);
	}

	private String normalizeCookie(String cookieOrMusicU) {
		String trimmed = unwrapCookieInput(stripCommentLines(cookieOrMusicU));
		if (trimmed.isBlank()) {
			return "";
		}

		String decoded = decodeOnce(trimmed);
		String musicU = extractCookieValue(decoded, "MUSIC_U");
		if (!musicU.isBlank()) {
			return "MUSIC_U=" + musicU;
		}

		musicU = extractCookieValue(trimmed, "MUSIC_U");
		if (!musicU.isBlank()) {
			return "MUSIC_U=" + musicU;
		}

		if (trimmed.contains("=") || trimmed.contains(";")) {
			return normalizeCookiePairs(decoded);
		}
		return "MUSIC_U=" + trimmed;
	}

	private String extractCookieValue(String key) {
		return extractCookieValue(neteaseCookie, key);
	}

	private String extractCookieValue(String cookieText, String key) {
		for (String part : cookieText.split("[;&]|\\R")) {
			String trimmed = part.trim();
			int equals = trimmed.indexOf('=');
			if (equals <= 0) {
				continue;
			}

			String cookieKey = trimmed.substring(0, equals).trim();
			if (cookieKey.regionMatches(true, 0, key, 0, key.length())
					&& cookieKey.length() == key.length()) {
				return trimCookieValue(trimmed.substring(equals + 1));
			}
		}
		return "";
	}

	private String unwrapCookieInput(String input) {
		String trimmed = input.trim();
		if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
				|| (trimmed.startsWith("'") && trimmed.endsWith("'"))
				|| (trimmed.startsWith("`") && trimmed.endsWith("`"))) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		}

		for (String line : trimmed.split("\\R")) {
			String lineTrimmed = line.trim();
			if (lineTrimmed.regionMatches(true, 0, "cookie:", 0, "cookie:".length())) {
				return stripTrailingQuote(lineTrimmed.substring("cookie:".length()).trim());
			}
		}

		int cookieHeader = trimmed.toLowerCase(Locale.ROOT).indexOf("cookie:");
		if (cookieHeader >= 0) {
			return stripTrailingQuote(trimmed.substring(cookieHeader + "cookie:".length()).trim());
		}
		return stripTrailingQuote(trimmed);
	}

	private String stripTrailingQuote(String value) {
		String trimmed = value.trim();
		while (!trimmed.isEmpty()) {
			char last = trimmed.charAt(trimmed.length() - 1);
			if (last == '"' || last == '\'' || last == '`') {
				trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
			} else {
				break;
			}
		}
		return trimmed;
	}

	private String decodeOnce(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8);
		} catch (IllegalArgumentException exception) {
			return value;
		}
	}

	private String normalizeCookiePairs(String cookieText) {
		StringJoiner joiner = new StringJoiner("; ");
		for (String part : cookieText.split("[;&]|\\R")) {
			String trimmed = part.trim();
			int equals = trimmed.indexOf('=');
			if (equals <= 0) {
				continue;
			}

			String key = trimmed.substring(0, equals).trim();
			String value = trimCookieValue(trimmed.substring(equals + 1));
			if (!key.isBlank() && !value.isBlank() && !isCookieAttribute(key)) {
				joiner.add(key + "=" + value);
			}
		}
		return joiner.toString();
	}

	private void ensureCookieFileExists() throws IOException {
		Files.createDirectories(COOKIE_FILE_PATH.getParent());
		if (Files.exists(COOKIE_FILE_PATH)) {
			return;
		}

		Files.writeString(COOKIE_FILE_PATH,
				"# 把网易云 MUSIC_U 或完整 Cookie 放在这个文件里，然后在游戏内执行 /gmusic token load\n"
						+ "# 推荐只放这一项：MUSIC_U=你的值\n",
				StandardCharsets.UTF_8);
	}

	private String stripCommentLines(String input) {
		StringJoiner joiner = new StringJoiner("\n");
		for (String line : input.split("\\R")) {
			String trimmed = line.trim();
			if (!trimmed.isBlank() && !trimmed.startsWith("#")) {
				joiner.add(line);
			}
		}
		return joiner.toString();
	}

	private boolean isCookieAttribute(String key) {
		return "path".equalsIgnoreCase(key)
				|| "domain".equalsIgnoreCase(key)
				|| "expires".equalsIgnoreCase(key)
				|| "max-age".equalsIgnoreCase(key)
				|| "samesite".equalsIgnoreCase(key)
				|| "secure".equalsIgnoreCase(key)
				|| "httponly".equalsIgnoreCase(key);
	}

	private String trimCookieValue(String value) {
		String trimmed = value.trim();
		while (!trimmed.isEmpty()) {
			char last = trimmed.charAt(trimmed.length() - 1);
			if (last == '"' || last == '\'' || last == '`' || Character.isWhitespace(last)) {
				trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
			} else {
				break;
			}
		}
		return trimmed;
	}

	private String mask(String value) {
		if (value.length() <= 10) {
			return "***";
		}
		return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
	}
}
