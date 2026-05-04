package com.xkitme.onlinemusic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MusicHud {
	private static final String FONT_ID = MusicFonts.LYRICS_FONT;
	private static final Duration COVER_TIMEOUT = Duration.ofSeconds(8);
	private static final int PANEL_WIDTH = 178;
	private static final int PANEL_HEIGHT = 186;
	private static final int COVER_SIZE = 42;
	private static final float PANEL_SCALE = 0.68f;
	private static final int MAX_CACHED_COVERS = 24;
	private static final long PANEL_QUEUE_AFTER_SWITCH_MILLIS = 10_000L;
	private static final long PANEL_QUEUE_BEFORE_SWITCH_MILLIS = 15_000L;
	private static final ExecutorService COVER_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "x2k Music Cover Loader");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, CoverTexture> COVER_CACHE = new LinkedHashMap<>(16, 0.75f, true);
	private static final Set<String> LOADING_COVERS = ConcurrentHashMap.newKeySet();

	private static boolean lyricsVisible = true;
	private static boolean panelVisible = true;
	private static boolean lyricAnimation = true;
	private static boolean playbackActive;
	private static boolean playbackPaused;
	private static long pausedAtMillis;
	private static String currentTitle = "";
	private static String currentRequestedBy = "";
	private static String currentCoverUrl = "";
	private static List<LyricLine> currentLyrics = List.of();
	private static long lyricStartMillis;
	private static long durationMillis;
	private static int activeLyricIndex = Integer.MIN_VALUE;
	private static long lyricChangedAtMillis;
	private static QueueEntry panelCurrent;
	private static List<QueueEntry> panelQueue = List.of();
	private static String cachedLyricLine = "";
	private static int cachedLyricMaxWidth = -1;
	private static Text cachedLyricText = Text.empty();
	private static int cachedLyricTextWidth;

	private MusicHud() {
	}

	public static void play(String title, String requestedBy, String coverUrl, List<LyricLine> lyrics,
							long startOffsetMillis, long trackDurationMillis) {
		playbackActive = true;
		playbackPaused = false;
		pausedAtMillis = 0L;
		currentTitle = title == null ? "" : title;
		currentRequestedBy = requestedBy == null ? "" : requestedBy;
		currentCoverUrl = coverUrl == null ? "" : coverUrl;
		currentLyrics = lyrics == null ? List.of() : List.copyOf(lyrics);
		lyricStartMillis = System.currentTimeMillis() - Math.max(0L, startOffsetMillis);
		durationMillis = Math.max(0L, trackDurationMillis);
		activeLyricIndex = Integer.MIN_VALUE;
		lyricChangedAtMillis = System.currentTimeMillis();
		requestCover(currentCoverUrl);
	}

	public static void stopPlayback() {
		playbackActive = false;
		playbackPaused = false;
		pausedAtMillis = 0L;
		currentTitle = "";
		currentRequestedBy = "";
		currentCoverUrl = "";
		currentLyrics = List.of();
		durationMillis = 0L;
		activeLyricIndex = Integer.MIN_VALUE;
		lyricChangedAtMillis = 0L;
		cachedLyricLine = "";
		cachedLyricMaxWidth = -1;
		cachedLyricText = Text.empty();
		cachedLyricTextWidth = 0;
	}

	public static void clearAll() {
		stopPlayback();
		panelCurrent = null;
		panelQueue = List.of();
	}

	public static void updateQueue(QueueEntry current, List<QueueEntry> queue) {
		panelCurrent = current;
		panelQueue = queue == null ? List.of() : List.copyOf(queue);
		if (current != null) {
			requestCover(current.coverUrl());
		}
	}

	public static void setPaused(boolean paused) {
		if (playbackPaused == paused) {
			return;
		}
		playbackPaused = paused;
		pausedAtMillis = paused ? System.currentTimeMillis() : 0L;
	}

	public static void setBoolean(int setting, boolean enabled) {
		if (setting == MusicNetworking.HUD_SETTING_LYRICS_VISIBLE) {
			lyricsVisible = enabled;
		} else if (setting == MusicNetworking.HUD_SETTING_PANEL_VISIBLE) {
			panelVisible = enabled;
		} else if (setting == MusicNetworking.HUD_SETTING_LYRIC_ANIMATION) {
			lyricAnimation = enabled;
		}
	}

	public static void applySettings(MusicClientSettings settings) {
		lyricsVisible = settings.lyricsVisible();
		panelVisible = settings.panelVisible();
		lyricAnimation = settings.lyricAnimation();
	}

	public static void render(DrawContext context, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.options.hudHidden || client.player == null) {
			return;
		}

		if (lyricsVisible && playbackActive) {
			renderLyric(context, client.textRenderer);
		}
		if (panelVisible && (playbackActive || panelCurrent != null || !panelQueue.isEmpty())) {
			renderPanel(context, client.textRenderer);
		}
	}

	private static void renderLyric(DrawContext context, TextRenderer textRenderer) {
		String line = currentLyricText();
		if (line.isBlank()) {
			return;
		}

		int screenWidth = context.getScaledWindowWidth();
		int screenHeight = context.getScaledWindowHeight();
		long now = playbackNowMillis();
		LyricMotion motion = lyricAnimation && !playbackPaused ? lyricMotion(now, screenHeight)
				: new LyricMotion(screenHeight - 68.0f, 0.62f, 1.0f);
		float scale = motion.scale();
		int maxTextWidth = Math.max(60, Math.min(screenWidth - 34, Math.round(screenWidth / Math.max(1.0f, scale)) - 34));
		if (!line.equals(cachedLyricLine) || maxTextWidth != cachedLyricMaxWidth) {
			cachedLyricLine = line;
			cachedLyricMaxWidth = maxTextWidth;
			cachedLyricText = MusicFonts.literal(trimWithEllipsis(textRenderer, line, maxTextWidth), FONT_ID);
			cachedLyricTextWidth = textRenderer.getWidth(cachedLyricText);
		}
		int centerX = screenWidth / 2;
		int alphaByte = Math.max(0, Math.min(255, Math.round(motion.alpha() * 255.0f)));
		int color = (alphaByte << 24) | 0xF6F6F6;

		context.getMatrices().push();
		context.getMatrices().translate(centerX, motion.y(), 0.0f);
		context.getMatrices().scale(scale, scale, 1.0f);
		context.drawText(textRenderer, cachedLyricText, -cachedLyricTextWidth / 2, 0, color, true);
		context.getMatrices().pop();
	}

	private static String currentLyricText() {
		if (currentLyrics.isEmpty()) {
			return currentTitle.isBlank() ? "" : "♪ " + currentTitle;
		}

		long elapsedMillis = Math.max(0L, playbackNowMillis() - lyricStartMillis);
		int lyricIndex = lyricIndex(elapsedMillis);
		if (lyricIndex < 0) {
			return currentTitle.isBlank() ? "" : "♪ " + currentTitle;
		}
		if (lyricIndex != activeLyricIndex) {
			activeLyricIndex = lyricIndex;
			lyricChangedAtMillis = playbackNowMillis();
		}
		return currentLyrics.get(lyricIndex).text();
	}

	private static int lyricIndex(long elapsedMillis) {
		int selectedIndex = -1;
		for (int index = 0; index < currentLyrics.size(); index++) {
			if (currentLyrics.get(index).timeMillis() > elapsedMillis) {
				break;
			}
			selectedIndex = index;
		}
		return selectedIndex;
	}

	private static LyricMotion lyricMotion(long now, int screenHeight) {
		float bottom = screenHeight - 58.0f;
		float middle = screenHeight - 68.0f;
		float top = screenHeight - 78.0f;
		if (currentLyrics.isEmpty() || activeLyricIndex < 0) {
			return new LyricMotion(middle, 0.62f, 1.0f);
		}

		long lineDuration = currentLineDurationMillis();
		long elapsedInLine = currentLineElapsedMillis(now);
		long inMillis = clampMillis(lineDuration / 8L, 120L, 260L);
		long outMillis = clampMillis(lineDuration / 7L, 140L, 340L);
		long outStartMillis = Math.max(inMillis, lineDuration - outMillis);

		if (elapsedInLine < inMillis) {
			float t = easeOutCubic(elapsedInLine / (float) inMillis);
			return new LyricMotion(lerp(bottom, middle, t), lerp(0.44f, 0.66f, t),
					lerp(0.0f, 1.0f, t));
		}
		if (elapsedInLine < outStartMillis) {
			return new LyricMotion(middle, 0.66f, 1.0f);
		}

		float t = easeInOut((elapsedInLine - outStartMillis) / (float) Math.max(1L, outMillis));
		return new LyricMotion(lerp(middle, top, t), lerp(0.66f, 0.50f, t),
				lerp(1.0f, 0.68f, t));
	}

	private static long currentLineDurationMillis() {
		if (activeLyricIndex < 0 || activeLyricIndex >= currentLyrics.size()) {
			return 2200L;
		}

		long currentAt = currentLyrics.get(activeLyricIndex).timeMillis();
		if (activeLyricIndex + 1 < currentLyrics.size()) {
			return Math.max(800L, currentLyrics.get(activeLyricIndex + 1).timeMillis() - currentAt);
		}
		if (durationMillis > currentAt) {
			return Math.max(1400L, durationMillis - currentAt);
		}
		return 3000L;
	}

	private static long currentLineElapsedMillis(long now) {
		if (activeLyricIndex < 0 || activeLyricIndex >= currentLyrics.size()) {
			return Math.max(0L, now - lyricChangedAtMillis);
		}
		long elapsedTrack = Math.max(0L, now - lyricStartMillis);
		return Math.max(0L, elapsedTrack - currentLyrics.get(activeLyricIndex).timeMillis());
	}

	private static long playbackNowMillis() {
		return playbackPaused && pausedAtMillis > 0L ? pausedAtMillis : System.currentTimeMillis();
	}

	private static void renderPanel(DrawContext context, TextRenderer textRenderer) {
		int screenWidth = context.getScaledWindowWidth();
		int screenHeight = context.getScaledWindowHeight();
		int scaledWidth = Math.round(PANEL_WIDTH * PANEL_SCALE);
		int scaledHeight = Math.round(PANEL_HEIGHT * PANEL_SCALE);
		int x = screenWidth - scaledWidth - 4;
		int y = Math.max(8, (screenHeight - scaledHeight) / 2);
		int right = PANEL_WIDTH;
		int bottom = PANEL_HEIGHT;

		context.getMatrices().push();
		context.getMatrices().translate(x, y, 0.0f);
		context.getMatrices().scale(PANEL_SCALE, PANEL_SCALE, 1.0f);

		context.fill(0, 0, right, bottom, 0xA80B0D10);
		context.fill(0, 0, right, 1, 0x55FFFFFF);
		context.fill(0, bottom - 1, right, bottom, 0x44000000);
		context.fill(0, 0, 1, bottom, 0x33FFFFFF);

		if (shouldShowQueuePanel(System.currentTimeMillis())) {
			renderQueuePanelContent(context, textRenderer, right, bottom);
		} else {
			renderLyricPanelContent(context, textRenderer, right, bottom);
		}
		context.getMatrices().pop();
	}

	private static boolean shouldShowQueuePanel(long now) {
		if (!playbackActive) {
			return true;
		}

		long elapsedMillis = Math.max(0L, now - lyricStartMillis);
		if (elapsedMillis <= PANEL_QUEUE_AFTER_SWITCH_MILLIS) {
			return true;
		}
		return durationMillis > 0L && durationMillis - elapsedMillis <= PANEL_QUEUE_BEFORE_SWITCH_MILLIS;
	}

	private static void renderQueuePanelContent(DrawContext context, TextRenderer textRenderer, int right, int bottom) {
		int cursorY = 8;
		drawTinyLabel(context, textRenderer, playbackPaused ? "已暂停" : "正在播放", 9, cursorY, 0xFFE8E8E8);
		cursorY += 14;

		QueueEntry current = panelCurrent;
		if (current == null && playbackActive) {
			current = new QueueEntry(currentTitle, currentRequestedBy, currentCoverUrl);
		}

		if (current != null) {
			drawCover(context, current.coverUrl(), 9, cursorY, COVER_SIZE);
			int textX = 9 + COVER_SIZE + 8;
			int textWidth = right - textX - 9;
			TitleParts titleParts = splitTitle(current.title());
			int textY = cursorY + 1;
			textY = drawWrappedText(context, textRenderer, titleParts.song(), textX, textY, textWidth, 1, 0xFFFFFFFF);
			if (!titleParts.artist().isBlank()) {
				textY = drawWrappedText(context, textRenderer, titleParts.artist(), textX, textY + 1, textWidth, 1,
						0xFFD8D8D8);
			}
			textY = drawWrappedText(context, textRenderer, "点歌：" + current.requestedBy(), textX, textY + 1,
					textWidth, 1, 0xFFD8D8D8);
			drawWrappedText(context, textRenderer, queueStatusText(), textX, textY + 1, textWidth, 1, 0xFFA8A8A8);
			drawProgressBar(context, 9, cursorY + COVER_SIZE + 4, PANEL_WIDTH - 18, 3, progressRatio());
		} else {
			drawTrimmedText(context, textRenderer, "暂无歌曲", 9, cursorY + 8, PANEL_WIDTH - 18, 0xFFD8D8D8);
		}

		cursorY += COVER_SIZE + 14;
		context.fill(9, cursorY, right - 9, cursorY + 1, 0x33FFFFFF);
		cursorY += 7;
		drawTinyLabel(context, textRenderer, "播放队列", 9, cursorY, 0xFFE8E8E8);
		cursorY += 13;

		if (panelQueue.isEmpty()) {
			drawTrimmedText(context, textRenderer, "队列为空", 9, cursorY, PANEL_WIDTH - 18, 0xFFA8A8A8);
			return;
		}

		int availableBottom = bottom - 8;
		for (int index = 0; index < panelQueue.size(); index++) {
			if (cursorY + 20 > availableBottom) {
				drawTrimmedText(context, textRenderer, "还有 " + (panelQueue.size() - index) + " 首 / 输入 /gmusic list 查看",
						9, cursorY, PANEL_WIDTH - 18, 0xFFA8A8A8);
				break;
			}
			QueueEntry entry = panelQueue.get(index);
			context.fill(9, cursorY - 1, 10, cursorY + 15, 0x44FFFFFF);
			drawTrimmedText(context, textRenderer, (index + 1) + ". " + entry.title(), 16, cursorY,
					PANEL_WIDTH - 25, 0xFFF2F2F2);
			drawTrimmedText(context, textRenderer, entry.requestedBy(), 16, cursorY + 10, PANEL_WIDTH - 25,
					0xFFA8A8A8);
			cursorY += 22;
		}
	}

	private static void renderLyricPanelContent(DrawContext context, TextRenderer textRenderer, int right, int bottom) {
		int cursorY = 8;
		drawTinyLabel(context, textRenderer, "当前歌词", 9, cursorY, 0xFFE8E8E8);
		cursorY += 14;

		QueueEntry current = panelCurrent;
		if (current == null && playbackActive) {
			current = new QueueEntry(currentTitle, currentRequestedBy, currentCoverUrl);
		}
		if (current != null) {
			int coverSize = 36;
			drawCover(context, current.coverUrl(), 9, cursorY, coverSize);
			int textX = 9 + coverSize + 8;
			int textWidth = right - textX - 9;
			TitleParts titleParts = splitTitle(current.title());
			int textY = cursorY + 1;
			textY = drawWrappedText(context, textRenderer, titleParts.song(), textX, textY, textWidth, 1, 0xFFFFFFFF);
			if (!titleParts.artist().isBlank()) {
				textY = drawWrappedText(context, textRenderer, titleParts.artist(), textX, textY + 1, textWidth, 1,
						0xFFB8B8B8);
			}
			drawProgressBar(context, textX, cursorY + coverSize - 5, textWidth, 3, progressRatio());
			cursorY += coverSize + 9;
		} else {
			drawTrimmedText(context, textRenderer, "暂无歌曲", 9, cursorY, PANEL_WIDTH - 18, 0xFFB8B8B8);
			cursorY += 13;
		}

		context.fill(9, cursorY + 2, right - 9, cursorY + 3, 0x33FFFFFF);
		cursorY += 10;

		String fallbackLine = currentLyricText();
		if (currentLyrics.isEmpty()) {
			drawTrimmedText(context, textRenderer, fallbackLine.isBlank() ? "暂无歌词" : fallbackLine, 9, cursorY,
					PANEL_WIDTH - 18, 0xFFFFFFFF);
			return;
		}

		int selectedIndex = activeLyricIndex;
		if (selectedIndex < 0) {
			selectedIndex = Math.max(0, lyricIndex(Math.max(0L, System.currentTimeMillis() - lyricStartMillis)));
		}
		selectedIndex = Math.max(0, Math.min(currentLyrics.size() - 1, selectedIndex));

		int startIndex = Math.max(0, selectedIndex - 2);
		int availableBottom = bottom - 8;
		for (int index = startIndex; index < currentLyrics.size(); index++) {
			if (cursorY + 12 > availableBottom) {
				break;
			}
			boolean currentLine = index == selectedIndex;
			int color = currentLine ? 0xFFFFFFFF : index < selectedIndex ? 0xFF888888 : 0xFFC8C8C8;
			int x = currentLine ? 9 : 13;
			int maxWidth = PANEL_WIDTH - x - 9;
			int maxLines = currentLine ? 3 : 2;
			cursorY = drawWrappedPanelText(context, textRenderer, currentLyrics.get(index).text(), x, cursorY,
					maxWidth, availableBottom, maxLines, color);
			cursorY += currentLine ? 5 : 4;
		}
	}

	private static String queueStatusText() {
		if (panelQueue.isEmpty()) {
			return "队列为空";
		}
		return "队列 " + panelQueue.size() + " 首";
	}

	private static float progressRatio() {
		if (!playbackActive || durationMillis <= 0L) {
			return 0.0f;
		}
		return Math.max(0.0f, Math.min(1.0f, currentElapsedMillis() / (float) durationMillis));
	}

	private static long currentElapsedMillis() {
		return Math.max(0L, playbackNowMillis() - lyricStartMillis);
	}

	private static void drawProgressBar(DrawContext context, int x, int y, int width, int height, float progress) {
		context.fill(x, y, x + width, y + height, 0x66000000);
		int filled = Math.max(0, Math.min(width, Math.round(width * progress)));
		if (filled > 0) {
			context.fill(x, y, x + filled, y + height, 0xFFE8E8E8);
		}
	}

	private static void drawTinyLabel(DrawContext context, TextRenderer textRenderer, String value, int x, int y, int color) {
		context.drawText(textRenderer, MusicFonts.literal(value, FONT_ID), x, y, color, false);
	}

	private static void drawTrimmedText(DrawContext context, TextRenderer textRenderer, String value, int x, int y,
										int maxWidth, int color) {
		String trimmed = trimWithEllipsis(textRenderer, value == null ? "" : value, maxWidth);
		context.drawText(textRenderer, MusicFonts.literal(trimmed, FONT_ID), x, y, color, true);
	}

	private static String trimWithEllipsis(TextRenderer textRenderer, String value, int maxWidth) {
		String safeValue = value == null ? "" : value;
		if (styledWidth(textRenderer, safeValue) <= maxWidth) {
			return safeValue;
		}
		if (maxWidth <= styledWidth(textRenderer, "...")) {
			return "";
		}
		String ellipsis = "...";
		int[] codePoints = safeValue.codePoints().toArray();
		int low = 0;
		int high = codePoints.length;
		int best = 0;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (styledWidth(textRenderer, valueFrom(codePoints, mid) + ellipsis) <= maxWidth) {
				best = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return valueFrom(codePoints, best).stripTrailing() + ellipsis;
	}

	private static int drawWrappedText(DrawContext context, TextRenderer textRenderer, String value, int x, int y,
									   int maxWidth, int maxLines, int color) {
		if ((value == null || value.isBlank()) || maxLines <= 0 || maxWidth <= 0) {
			return y;
		}
		String trimmed = trimWithEllipsis(textRenderer, value, maxWidth);
		context.drawText(textRenderer, MusicFonts.literal(trimmed, FONT_ID), x, y, color, true);
		return y + textRenderer.fontHeight + 1;
	}

	private static int drawWrappedPanelText(DrawContext context, TextRenderer textRenderer, String value, int x, int y,
											int maxWidth, int availableBottom, int maxLines, int color) {
		String remaining = value == null ? "" : value.strip();
		int lineHeight = textRenderer.fontHeight + 3;
		int lineCount = 0;
		while (!remaining.isBlank() && lineCount < maxLines && y + lineHeight <= availableBottom) {
			String line = trimLine(textRenderer, remaining, maxWidth);
			if (line.isBlank()) {
				break;
			}
			boolean hasMore = line.length() < remaining.length();
			String display = hasMore && lineCount + 1 >= maxLines
					? trimWithEllipsis(textRenderer, remaining, maxWidth)
					: line;
			context.drawText(textRenderer, MusicFonts.literal(display, FONT_ID), x, y, color, true);
			if (!hasMore || lineCount + 1 >= maxLines) {
				break;
			}
			remaining = remaining.substring(line.length()).stripLeading();
			y += lineHeight;
			lineCount++;
		}
		return y + lineHeight;
	}

	private static String trimLine(TextRenderer textRenderer, String value, int maxWidth) {
		if (styledWidth(textRenderer, value) <= maxWidth) {
			return value;
		}
		int[] codePoints = value.codePoints().toArray();
		int low = 0;
		int high = codePoints.length;
		int best = 0;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (styledWidth(textRenderer, valueFrom(codePoints, mid)) <= maxWidth) {
				best = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return valueFrom(codePoints, Math.max(1, best)).stripTrailing();
	}

	private static String valueFrom(int[] codePoints, int length) {
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < length; index++) {
			builder.appendCodePoint(codePoints[index]);
		}
		return builder.toString();
	}

	private static int styledWidth(TextRenderer textRenderer, String value) {
		return textRenderer.getWidth(MusicFonts.literal(value, FONT_ID));
	}

	private static TitleParts splitTitle(String rawTitle) {
		String title = rawTitle == null ? "" : rawTitle.trim();
		String separator = " - ";
		int separatorIndex = title.indexOf(separator);
		if (separatorIndex <= 0 || separatorIndex + separator.length() >= title.length()) {
			return new TitleParts(title, "");
		}
		String artist = title.substring(0, separatorIndex).trim();
		String song = title.substring(separatorIndex + separator.length()).trim();
		return new TitleParts(song.isBlank() ? title : song, artist);
	}

	private static void drawCover(DrawContext context, String coverUrl, int x, int y, int size) {
		String sizedUrl = coverUrl(coverUrl);
		CoverTexture cover = sizedUrl.isBlank() ? null : cachedCover(sizedUrl);
		if (cover != null) {
			context.fill(x, y, x + size, y + size, 0xFF151719);
			int drawWidth = size;
			int drawHeight = size;
			if (cover.width() > cover.height()) {
				drawHeight = Math.max(1, Math.round(size * (cover.height() / (float) cover.width())));
			} else if (cover.height() > cover.width()) {
				drawWidth = Math.max(1, Math.round(size * (cover.width() / (float) cover.height())));
			}
			int drawX = x + (size - drawWidth) / 2;
			int drawY = y + (size - drawHeight) / 2;
			context.drawTexture(cover.identifier(), drawX, drawY, drawWidth, drawHeight, 0.0f, 0.0f,
					cover.width(), cover.height(), cover.width(), cover.height());
			context.fill(x, y, x + size, y + 1, 0x55FFFFFF);
			context.fill(x, y + size - 1, x + size, y + size, 0x66000000);
			return;
		}

		requestCover(sizedUrl);
		context.fill(x, y, x + size, y + size, 0xFF17202A);
		context.fill(x + 2, y + 2, x + size - 2, y + size - 2, 0xFF21313C);
		Text note = MusicFonts.literal("♪", FONT_ID);
		int noteWidth = MinecraftClient.getInstance().textRenderer.getWidth(note);
		context.drawText(MinecraftClient.getInstance().textRenderer, note, x + (size - noteWidth) / 2, y + 14,
				0xFFE8E8E8, true);
	}

	private static void requestCover(String rawUrl) {
		String url = coverUrl(rawUrl);
		if (url.isBlank() || hasCachedCover(url) || !LOADING_COVERS.add(url)) {
			return;
		}

		COVER_EXECUTOR.execute(() -> {
			NativeImage image = null;
			try {
				URLConnection connection = URI.create(url).toURL().openConnection();
				connection.setConnectTimeout((int) COVER_TIMEOUT.toMillis());
				connection.setReadTimeout((int) COVER_TIMEOUT.toMillis());
				connection.setRequestProperty("User-Agent", "Mozilla/5.0 x2kMusicHud/1.0");
				try (InputStream input = connection.getInputStream()) {
					image = NativeImage.read(input);
				}
				NativeImage finalImage = image;
				MinecraftClient.getInstance().execute(() -> {
					NativeImageBackedTexture texture = new NativeImageBackedTexture(finalImage);
					Identifier identifier = MinecraftClient.getInstance().getTextureManager()
							.registerDynamicTexture("x2k_album_cover", texture);
					putCachedCover(url, new CoverTexture(identifier, texture, finalImage.getWidth(), finalImage.getHeight()));
					LOADING_COVERS.remove(url);
				});
				image = null;
			} catch (Exception ignored) {
				if (image != null) {
					image.close();
				}
				LOADING_COVERS.remove(url);
			}
		});
	}

	private static CoverTexture cachedCover(String url) {
		synchronized (COVER_CACHE) {
			return COVER_CACHE.get(url);
		}
	}

	private static boolean hasCachedCover(String url) {
		synchronized (COVER_CACHE) {
			return COVER_CACHE.containsKey(url);
		}
	}

	private static void putCachedCover(String url, CoverTexture cover) {
		synchronized (COVER_CACHE) {
			CoverTexture previous = COVER_CACHE.put(url, cover);
			if (previous != null) {
				previous.close();
			}
			Iterator<Map.Entry<String, CoverTexture>> iterator = COVER_CACHE.entrySet().iterator();
			while (COVER_CACHE.size() > MAX_CACHED_COVERS && iterator.hasNext()) {
				Map.Entry<String, CoverTexture> eldest = iterator.next();
				CoverTexture evicted = eldest.getValue();
				iterator.remove();
				evicted.close();
			}
		}
	}

	private static String coverUrl(String rawUrl) {
		String trimmed = rawUrl == null ? "" : rawUrl.trim();
		if (trimmed.isBlank()) {
			return "";
		}
		if (trimmed.contains("param=")) {
			return trimmed;
		}
		if (trimmed.contains("?")) {
			return trimmed + "&param=128y128";
		}
		return trimmed + "?param=128y128";
	}

	private static float easeOutCubic(float value) {
		float clamped = clamp(value, 0.0f, 1.0f);
		float inverse = 1.0f - clamped;
		return 1.0f - inverse * inverse * inverse;
	}

	private static float easeInOut(float value) {
		float clamped = clamp(value, 0.0f, 1.0f);
		return clamped * clamped * (3.0f - 2.0f * clamped);
	}

	private static float lerp(float start, float end, float delta) {
		return start + (end - start) * clamp(delta, 0.0f, 1.0f);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private static long clampMillis(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	private record LyricMotion(float y, float scale, float alpha) {
	}

	private record TitleParts(String song, String artist) {
	}

	public record QueueEntry(String title, String requestedBy, String coverUrl) {
	}

	private record CoverTexture(Identifier identifier, NativeImageBackedTexture texture, int width, int height) {
		private void close() {
			texture.close();
		}
	}
}
