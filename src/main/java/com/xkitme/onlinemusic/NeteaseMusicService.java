package com.xkitme.onlinemusic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NeteaseMusicService {
	private static final URI SEARCH_URI = URI.create("https://music.163.com/api/search/get/web");
	private static final URI PLAYER_URL_URI = URI.create("https://music.163.com/eapi/song/enhance/player/url/v1");
	private static final String PLAYER_URL_PATH = "/api/song/enhance/player/url/v1";
	private static final URI SONG_DETAIL_URI = URI.create("https://music.163.com/eapi/v3/song/detail");
	private static final String SONG_DETAIL_PATH = "/api/v3/song/detail";
	private static final URI LOGIN_STATUS_URI = URI.create("https://music.163.com/eapi/w/nuser/account/get");
	private static final String LOGIN_STATUS_PATH = "/api/w/nuser/account/get";
	private static final URI VIP_INFO_URI = URI.create("https://music.163.com/eapi/music-vip-membership/client/vip/info");
	private static final String VIP_INFO_PATH = "/api/music-vip-membership/client/vip/info";
	private static final String LYRIC_URL = "https://music.163.com/api/song/lyric?id=%d&lv=1&kv=1&tv=-1";
	private static final Pattern LYRIC_TIME = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]");
	private static final Duration TIMEOUT = Duration.ofSeconds(12);
	private static final int RESULT_LIMIT = 5;
	private static final List<String> PLAYBACK_LEVELS = List.of("exhigh", "higher", "standard");
	private static final String EAPI_KEY = "e82ckenh8dichen8";
	private static final String EAPI_SALT = "36cd479b6b5";
	private static final String DEVICE_ID = randomDeviceId();
	private static final int MAX_LYRIC_LINES = 300;

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(TIMEOUT)
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public List<SongResult> search(String keywords, String cookie) throws IOException, InterruptedException {
		return search(keywords, cookie, 0, RESULT_LIMIT);
	}

	public List<SongResult> search(String keywords, String cookie, int offset, int limit)
			throws IOException, InterruptedException {
		String body = form(
				"s", keywords,
				"type", "1",
				"offset", String.valueOf(Math.max(0, offset)),
				"limit", String.valueOf(Math.max(1, limit)),
				"total", "true");
		HttpRequest request = baseRequest(SEARCH_URI, cookie)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

		JsonObject root = sendJson(request, "网易云搜索");
		JsonObject result = root.has("result") && root.get("result").isJsonObject()
				? root.getAsJsonObject("result") : new JsonObject();
		JsonArray songs = result.getAsJsonArray("songs");
		List<SongResult> results = new ArrayList<>();
		if (songs == null) {
			return results;
		}

		for (JsonElement element : songs) {
			JsonObject song = element.getAsJsonObject();
			long id = song.has("id") ? song.get("id").getAsLong() : 0L;
			String name = stringValue(song, "name");
			String album = nestedStringValue(song, "album", "name");
			String coverUrl = firstNonBlank(nestedStringValue(song, "album", "picUrl"),
					nestedStringValue(song, "al", "picUrl"));
			String artists = firstNonBlank(artists(song.getAsJsonArray("artists")), artists(song.getAsJsonArray("ar")));
			if (id <= 0 || name.isBlank()) {
				continue;
			}
			results.add(new SongResult(id, name, artists, album, coverUrl));
		}
		return results;
	}

	public AccountStatus checkAccount(String cookie) throws IOException, InterruptedException {
		JsonObject login = sendJson(eapiRequest(LOGIN_STATUS_URI, LOGIN_STATUS_PATH, "", cookie).build(),
				"网易云登录状态");
		JsonObject vip = sendJson(eapiRequest(VIP_INFO_URI, VIP_INFO_PATH, "", cookie).build(),
				"网易云 VIP 状态");

		JsonObject account = objectValue(login, "account");
		JsonObject profile = objectValue(login, "profile");
		JsonObject vipData = objectValue(vip, "data");
		JsonObject musicPackage = objectValue(vipData, "musicPackage");
		JsonObject associator = objectValue(vipData, "associator");

		long userId = longValue(profile, "userId", longValue(account, "id", longValue(vipData, "userId", 0L)));
		String nickname = stringValue(profile, "nickname");
		if (nickname.isBlank()) {
			nickname = stringValue(account, "userName");
		}
		boolean anonymous = booleanValue(account, "anonimousUser");
		int accountVipType = intValue(account, "vipType", intValue(profile, "vipType", 0));
		int redVipLevel = intValue(vipData, "redVipLevel", 0);
		int musicPackageCode = intValue(musicPackage, "vipCode", 0);
		int associatorCode = intValue(associator, "vipCode", 0);
		long musicPackageExpireTime = longValue(musicPackage, "expireTime", 0L);
		long associatorExpireTime = longValue(associator, "expireTime", 0L);
		boolean loggedIn = userId > 0L && !anonymous;
		boolean vipActive = redVipLevel > 0 || musicPackageCode > 0 || associatorCode > 0 || accountVipType > 0;
		return new AccountStatus(loggedIn, vipActive, userId, nickname, accountVipType, redVipLevel,
				musicPackageCode, musicPackageExpireTime, associatorCode, associatorExpireTime);
	}

	public ResolvedTrack resolveTrack(SongResult song, String cookie) throws IOException, InterruptedException {
		ResolvedUrl resolvedUrl = resolveUrl(song.id(), cookie);
		SongDetail detail = safeSongDetail(song.id(), cookie);
		return new ResolvedTrack(firstNonBlank(song.displayTitle(), detail.displayTitle()), resolvedUrl.url(),
				resolvedUrl.durationMillis(), firstNonBlank(song.coverUrl(), detail.coverUrl()),
				safeLyrics(song.id(), cookie));
	}

	public ResolvedTrack resolveTrack(long songId, String cookie) throws IOException, InterruptedException {
		ResolvedUrl resolvedUrl = resolveUrl(songId, cookie);
		SongDetail detail = safeSongDetail(songId, cookie);
		return new ResolvedTrack(firstNonBlank(detail.displayTitle(), "网易云歌曲 #" + songId),
				resolvedUrl.url(), resolvedUrl.durationMillis(), detail.coverUrl(),
				safeLyrics(songId, cookie));
	}

	private SongDetail safeSongDetail(long songId, String cookie) {
		try {
			return songDetail(songId, cookie);
		} catch (Exception ignored) {
			return SongDetail.EMPTY;
		}
	}

	private SongDetail songDetail(long songId, String cookie) throws IOException, InterruptedException {
		JsonObject songRef = new JsonObject();
		songRef.addProperty("id", songId);
		JsonArray ids = new JsonArray();
		ids.add(songRef);
		JsonObject payload = new JsonObject();
		payload.addProperty("c", ids.toString());

		JsonObject root = sendJson(eapiRequest(SONG_DETAIL_URI, SONG_DETAIL_PATH, payload, cookie).build(),
				"网易云歌曲详情");
		JsonArray songs = root.getAsJsonArray("songs");
		if (songs == null || songs.size() == 0 || !songs.get(0).isJsonObject()) {
			return SongDetail.EMPTY;
		}

		JsonObject song = songs.get(0).getAsJsonObject();
		String title = stringValue(song, "name");
		String album = nestedStringValue(song, "al", "name");
		String coverUrl = nestedStringValue(song, "al", "picUrl");
		String artists = artists(song.getAsJsonArray("ar"));
		return new SongDetail(title, artists, album, coverUrl);
	}

	private ResolvedUrl resolveUrl(long songId, String cookie) throws IOException, InterruptedException {
		IOException lastFailure = null;
		for (String level : PLAYBACK_LEVELS) {
			try {
				return resolveUrl(songId, cookie, level);
			} catch (IOException exception) {
				lastFailure = exception;
			}
		}

		throw lastFailure == null ? new IOException("网易云没有返回可播放地址。") : lastFailure;
	}

	private ResolvedUrl resolveUrl(long songId, String cookie, String level) throws IOException, InterruptedException {
		JsonObject payload = new JsonObject();
		JsonArray ids = new JsonArray();
		ids.add(String.valueOf(songId));
		payload.addProperty("ids", ids.toString());
		payload.addProperty("level", level);
		payload.addProperty("encodeType", "mp3");

		HttpRequest request = eapiRequest(PLAYER_URL_URI, PLAYER_URL_PATH, payload, cookie)
				.build();

		JsonObject root = sendJson(request, "网易云播放地址");
		JsonArray data = root.getAsJsonArray("data");
		if (data == null || data.size() == 0 || !data.get(0).isJsonObject()) {
			throw new IOException("网易云没有返回播放数据。");
		}

		JsonObject first = data.get(0).getAsJsonObject();
		String url = stringValue(first, "url");
		int code = first.has("code") && !first.get("code").isJsonNull() ? first.get("code").getAsInt() : 0;
		String message = stringValue(first, "message");
		long durationMillis = first.has("time") && !first.get("time").isJsonNull() ? first.get("time").getAsLong() : 0L;
		if (url.isBlank()) {
			if (!message.isBlank()) {
				throw new IOException(message + "（" + level + "）");
			}
			throw new IOException("没有可播放地址，账号可能无权播放这首歌，或登录凭证已失效。（" + level + "）");
		}
		if (code != 0 && code != 200) {
			throw new IOException("网易云返回错误码 " + code + "（" + level + "）");
		}
		if (isTrial(first)) {
			throw new IOException("网易云只返回试听片段，VIP 登录凭证可能没有被识别，或账号无权播放这首歌。");
		}
		if (isUnsupportedAudio(url, first)) {
			throw new IOException("网易云返回了当前播放器暂不支持的音频格式（" + level + "）。");
		}
		return new ResolvedUrl(url, durationMillis);
	}

	private boolean isTrial(JsonObject data) {
		return hasJsonValue(data, "freeTrialInfo")
				|| hasConsumablePrivilege(data, "freeTrialPrivilege")
				|| hasConsumablePrivilege(data, "freeTimeTrialPrivilege");
	}

	private boolean hasJsonValue(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull();
	}

	private boolean hasConsumablePrivilege(JsonObject object, String key) {
		if (!object.has(key) || !object.get(key).isJsonObject()) {
			return false;
		}

		JsonObject privilege = object.getAsJsonObject(key);
		return booleanValue(privilege, "resConsumable")
				|| booleanValue(privilege, "userConsumable")
				|| booleanValue(privilege, "type");
	}

	private boolean booleanValue(JsonObject object, String key) {
		if (!object.has(key) || object.get(key).isJsonNull()) {
			return false;
		}
		JsonElement value = object.get(key);
		if (!value.isJsonPrimitive()) {
			return false;
		}

		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isNumber()) {
			return primitive.getAsInt() != 0;
		}
		return Boolean.parseBoolean(primitive.getAsString());
	}

	private boolean isUnsupportedAudio(String url, JsonObject data) {
		String lowerUrl = url.toLowerCase();
		String type = stringValue(data, "type").toLowerCase();
		String encodeType = stringValue(data, "encodeType").toLowerCase();
		return lowerUrl.contains(".flac") || lowerUrl.contains(".m4a") || lowerUrl.contains(".aac")
				|| "flac".equals(type) || "m4a".equals(type) || "aac".equals(type)
				|| "flac".equals(encodeType) || "m4a".equals(encodeType) || "aac".equals(encodeType);
	}

	private List<LyricLine> safeLyrics(long songId, String cookie) {
		try {
			return lyrics(songId, cookie);
		} catch (Exception ignored) {
			return List.of();
		}
	}

	private List<LyricLine> lyrics(long songId, String cookie) throws IOException, InterruptedException {
		HttpRequest request = baseRequest(URI.create(String.format(LYRIC_URL, songId)), cookie)
				.GET()
				.build();
		JsonObject root = sendJson(request, "网易云歌词");
		if (!root.has("lrc") || !root.get("lrc").isJsonObject()) {
			return List.of();
		}

		String lyricText = stringValue(root.getAsJsonObject("lrc"), "lyric");
		if (lyricText.isBlank()) {
			return List.of();
		}

		List<LyricLine> lines = new ArrayList<>();
		for (String rawLine : lyricText.split("\\R")) {
			Matcher matcher = LYRIC_TIME.matcher(rawLine);
			List<Long> times = new ArrayList<>();
			int lastEnd = 0;
			while (matcher.find()) {
				times.add(toMillis(matcher));
				lastEnd = matcher.end();
			}

			if (times.isEmpty()) {
				continue;
			}

			String text = rawLine.substring(lastEnd).trim();
			if (text.isBlank()) {
				continue;
			}

			for (Long time : times) {
				lines.add(new LyricLine(time, text));
			}
		}

		lines.sort(Comparator.comparingLong(LyricLine::timeMillis));
		if (lines.size() > MAX_LYRIC_LINES) {
			return List.copyOf(lines.subList(0, MAX_LYRIC_LINES));
		}
		return List.copyOf(lines);
	}

	private long toMillis(Matcher matcher) {
		long minutes = Long.parseLong(matcher.group(1));
		long seconds = Long.parseLong(matcher.group(2));
		String fraction = matcher.group(3);
		long millis = 0L;
		if (fraction != null) {
			millis = switch (fraction.length()) {
				case 1 -> Long.parseLong(fraction) * 100L;
				case 2 -> Long.parseLong(fraction) * 10L;
				default -> Long.parseLong(fraction.substring(0, 3));
			};
		}
		return minutes * 60_000L + seconds * 1000L + millis;
	}

	private JsonObject sendJson(HttpRequest request, String action) throws IOException, InterruptedException {
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IOException(action + " 返回 HTTP " + response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private HttpRequest.Builder baseRequest(URI uri, String cookie) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("Accept", "application/json, text/plain, */*")
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Origin", "https://music.163.com")
				.header("Referer", "https://music.163.com/")
				.header("User-Agent", "Mozilla/5.0 OnlineMusicMod/1.0 Minecraft Fabric");

		if (cookie != null && !cookie.isBlank()) {
			builder.header("Cookie", cookie);
		}
		return builder;
	}

	private HttpRequest.Builder eapiRequest(URI uri, String path, JsonObject payload, String cookie) throws IOException {
		return eapiRequest(uri, path, payload.toString(), cookie);
	}

	private HttpRequest.Builder eapiRequest(URI uri, String path, String payload, String cookie) throws IOException {
		String body = eapiBody(path, payload.toString());
		return HttpRequest.newBuilder(uri)
				.timeout(TIMEOUT)
				.header("Accept", "application/json, text/plain, */*")
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Origin", "https://music.163.com")
				.header("Referer", "https://music.163.com/")
				.header("User-Agent", "NeteaseMusic/9.3.40.1753206443(164);Dalvik/2.1.0 (Linux; U; Android 9)")
				.header("Cookie", eapiCookie(cookie))
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
	}

	private String eapiBody(String path, String payload) throws IOException {
		String text = path + "-" + EAPI_SALT + "-" + payload + "-" + EAPI_SALT + "-"
				+ md5Hex("nobody" + path + "use" + payload + "md5forencrypt");
		try {
			Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(EAPI_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
			return "params=" + HexFormat.of().withUpperCase().formatHex(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IOException("网易云 EAPI 加密失败：" + exception.getMessage(), exception);
		}
	}

	private String md5Hex(String text) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("MD5");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IOException("网易云 EAPI 签名失败：" + exception.getMessage(), exception);
		}
	}

	private String eapiCookie(String cookie) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("deviceId", DEVICE_ID);
		parseCookies(cookie, values);
		values.put("appver", "9.3.40");
		values.put("buildver", String.valueOf(System.currentTimeMillis() / 1000L));
		values.put("resolution", "1920x1080");
		values.put("os", "Android");
		if (!values.containsKey("MUSIC_U") && !values.containsKey("MUSIC_A")) {
			values.put("MUSIC_A", "4ee5f776c9ed1e4d5f031b09e084c6cb333e43ee4a841afeebbef9bbf4b7e4152b51ff20ecb9e8ee9e89ab23044cf50d1609e4781e805e73a138419e5583bc7fd1e5933c52368d9127ba9ce4e2f233bf5a77ba40ea6045ae1fc612ead95d7b0e0edf70a74334194e1a190979f5fc12e9968c3666a981495b33a649814e309366");
		}

		StringJoiner joiner = new StringJoiner("; ");
		for (Map.Entry<String, String> entry : values.entrySet()) {
			if (!entry.getKey().isBlank() && !entry.getValue().isBlank()) {
				joiner.add(encodeCookie(entry.getKey()) + "=" + encodeCookie(entry.getValue()));
			}
		}
		return joiner.toString();
	}

	private void parseCookies(String cookie, Map<String, String> values) {
		if (cookie == null || cookie.isBlank()) {
			return;
		}

		for (String part : cookie.split(";")) {
			int equals = part.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			String key = part.substring(0, equals).trim();
			String value = part.substring(equals + 1).trim();
			if (!key.isBlank() && !value.isBlank() && !isCookieAttribute(key)) {
				values.put(key, value);
			}
		}
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

	private String encodeCookie(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static String randomDeviceId() {
		byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	private String form(String... keyValues) {
		StringJoiner joiner = new StringJoiner("&");
		for (int index = 0; index < keyValues.length; index += 2) {
			joiner.add(encode(keyValues[index]) + "=" + encode(keyValues[index + 1]));
		}
		return joiner.toString();
	}

	private String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private String stringValue(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
	}

	private JsonObject objectValue(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
	}

	private int intValue(JsonObject object, String key, int fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
	}

	private long longValue(JsonObject object, String key, long fallback) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : fallback;
	}

	private String nestedStringValue(JsonObject object, String parentKey, String childKey) {
		if (!object.has(parentKey) || !object.get(parentKey).isJsonObject()) {
			return "";
		}
		return stringValue(object.getAsJsonObject(parentKey), childKey);
	}

	private String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private String artists(JsonArray artists) {
		if (artists == null || artists.size() == 0) {
			return "";
		}

		StringJoiner joiner = new StringJoiner("/");
		for (JsonElement element : artists) {
			if (element.isJsonObject()) {
				String name = stringValue(element.getAsJsonObject(), "name");
				if (!name.isBlank()) {
					joiner.add(name);
				}
			}
		}
		return joiner.toString();
	}

	public record SongResult(long id, String title, String artist, String album, String coverUrl) {
		public String displayTitle() {
			if (artist.isBlank()) {
				return title;
			}
			return artist + " - " + title;
		}
	}

	public record ResolvedTrack(String title, String url, long durationMillis, String coverUrl, List<LyricLine> lyrics) {
	}

	private record SongDetail(String title, String artist, String album, String coverUrl) {
		private static final SongDetail EMPTY = new SongDetail("", "", "", "");

		private String displayTitle() {
			if (title.isBlank()) {
				return "";
			}
			if (artist.isBlank()) {
				return title;
			}
			return artist + " - " + title;
		}
	}

	public record AccountStatus(boolean loggedIn, boolean vipActive, long userId, String nickname, int accountVipType,
								int redVipLevel, int musicPackageCode, long musicPackageExpireTime,
								int associatorCode, long associatorExpireTime) {
	}

	private record ResolvedUrl(String url, long durationMillis) {
	}
}
