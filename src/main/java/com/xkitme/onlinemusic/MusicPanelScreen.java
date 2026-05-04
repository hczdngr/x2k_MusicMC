package com.xkitme.onlinemusic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class MusicPanelScreen extends Screen {
	private static MusicHud.QueueEntry currentEntry;
	private static List<MusicHud.QueueEntry> queuedEntries = List.of();
	private static boolean playbackPaused;
	private static long playbackElapsedMillis;
	private static long playbackDurationMillis;
	private static long playbackSnapshotAtMillis;

	private final Screen parent;

	private TextFieldWidget searchField;
	private boolean queueView;
	private String query = "";
	private int page;
	private int queuePage;
	private boolean loading;
	private boolean lastSuccess = true;
	private String status = "输入歌名搜索网易云";
	private List<SearchResult> results = List.of();

	public MusicPanelScreen(Screen parent) {
		super(Text.literal("x²k点歌"));
		this.parent = parent;
	}

	public static void receiveResults(String keywords, int page, boolean success, String message,
									  List<SearchResult> results) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.currentScreen instanceof MusicPanelScreen screen) {
			screen.applyResults(keywords, page, success, message, results);
		}
	}

	public static void updatePlayback(MusicHud.QueueEntry current, List<MusicHud.QueueEntry> queue, boolean paused,
									  long elapsedMillis, long durationMillis) {
		currentEntry = current;
		queuedEntries = queue == null ? List.of() : List.copyOf(queue);
		playbackPaused = paused;
		playbackElapsedMillis = Math.max(0L, elapsedMillis);
		playbackDurationMillis = Math.max(0L, durationMillis);
		playbackSnapshotAtMillis = System.currentTimeMillis();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.currentScreen instanceof MusicPanelScreen screen && screen.queueView) {
			screen.refresh();
		}
	}

	public static void updatePlaybackPaused(boolean paused) {
		playbackPaused = paused;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.currentScreen instanceof MusicPanelScreen screen && screen.queueView) {
			screen.refresh();
		}
	}

	@Override
	protected void init() {
		int panelWidth = panelWidth();
		int panelHeight = panelHeight();
		int x = panelX();
		int y = panelY();

		if (queueView) {
			initQueueView(panelWidth, panelHeight, x, y);
		} else {
			initSearchView(panelWidth, panelHeight, x, y);
		}
		addCommonButtons(panelWidth, panelHeight, x, y);
	}

	private void initSearchView(int panelWidth, int panelHeight, int x, int y) {
		int searchY = y + 34;
		int searchWidth = panelWidth - 184;
		searchField = new TextFieldWidget(textRenderer, x + 14, searchY, searchWidth, 20, Text.literal("搜索歌曲"));
		searchField.setMaxLength(64);
		searchField.setText(query);
		searchField.setSuggestion(query.isBlank() ? "输入歌名/歌手" : "");
		searchField.setChangedListener(value -> {
			query = value;
			searchField.setSuggestion(value.isBlank() ? "输入歌名/歌手" : "");
		});
		addDrawableChild(searchField);
		setInitialFocus(searchField);

		ButtonWidget searchButton = ButtonWidget.builder(Text.literal(loading ? "搜索中" : "搜索"),
						button -> search(0))
				.dimensions(x + panelWidth - 164, searchY, 58, 20)
				.build();
		searchButton.active = !loading;
		addDrawableChild(searchButton);
		addDrawableChild(ButtonWidget.builder(Text.literal("歌曲列表"), button -> {
					queueView = true;
					status = playbackStatus();
					refresh();
				})
				.dimensions(x + panelWidth - 96, searchY, 82, 20)
				.build());

		int rowTop = y + 78;
		for (int index = 0; index < results.size(); index++) {
			SearchResult result = results.get(index);
			int rowY = rowTop + index * 31;
			ButtonWidget playButton = ButtonWidget.builder(Text.literal("点歌"), button -> play(result))
					.dimensions(x + panelWidth - 78, rowY + 5, 58, 20)
					.build();
			playButton.active = !loading;
			addDrawableChild(playButton);
		}

		ButtonWidget prevButton = ButtonWidget.builder(Text.literal("上一页"), button -> search(page - 1))
				.dimensions(x + panelWidth / 2 - 64, y + panelHeight - 26, 58, 20)
				.build();
		prevButton.active = !loading && !query.isBlank() && page > 0;
		addDrawableChild(prevButton);
		ButtonWidget nextButton = ButtonWidget.builder(Text.literal("下一页"), button -> search(page + 1))
				.dimensions(x + panelWidth / 2 + 6, y + panelHeight - 26, 58, 20)
				.build();
		nextButton.active = !loading && !query.isBlank();
		addDrawableChild(nextButton);
	}

	private void initQueueView(int panelWidth, int panelHeight, int x, int y) {
		int topY = y + 34;
		int rowTop = queueRowTop(y);
		int pageSize = queuePageSize(y, panelHeight, rowTop);
		clampQueuePage(pageSize);
		addDrawableChild(ButtonWidget.builder(Text.literal("返回搜索"), button -> {
					queueView = false;
					refresh();
				})
				.dimensions(x + 14, topY, 70, 20)
				.build());
		ButtonWidget previousButton = ButtonWidget.builder(Text.literal("上一首"), button ->
						sendCommand("gmusic prev", "已请求切回上一首"))
				.dimensions(x + 96, topY, 58, 20)
				.build();
		previousButton.active = currentEntry != null;
		addDrawableChild(previousButton);
		ButtonWidget pauseButton = ButtonWidget.builder(Text.literal(playbackPaused ? "继续" : "暂停"), button ->
						sendCommand(playbackPaused ? "gmusic resume" : "gmusic pause", playbackPaused ? "已请求继续播放" : "已请求暂停"))
				.dimensions(x + 164, topY, 58, 20)
				.build();
		pauseButton.active = currentEntry != null;
		addDrawableChild(pauseButton);
		ButtonWidget nextButton = ButtonWidget.builder(Text.literal("下一首"), button ->
						sendCommand("gmusic skip", "已请求切到下一首"))
				.dimensions(x + 232, topY, 58, 20)
				.build();
		nextButton.active = currentEntry != null;
		addDrawableChild(nextButton);

		int startIndex = queuePage * Math.max(1, pageSize);
		int visibleRows = Math.min(pageSize, Math.max(0, queuedEntries.size() - startIndex));
		for (int index = 0; index < visibleRows; index++) {
			int rowY = rowTop + index * 31;
			int oneBasedIndex = startIndex + index + 1;
			addDrawableChild(ButtonWidget.builder(Text.literal("置顶"), button ->
							sendCommand("gmusic top " + oneBasedIndex, "已请求置顶第 " + oneBasedIndex + " 首"))
					.dimensions(x + panelWidth - 122, rowY + 5, 50, 20)
					.build());
			addDrawableChild(ButtonWidget.builder(Text.literal("删除"), button ->
							sendCommand("gmusic remove " + oneBasedIndex, "已请求删除第 " + oneBasedIndex + " 首"))
					.dimensions(x + panelWidth - 66, rowY + 5, 50, 20)
					.build());
		}

		ButtonWidget prevPageButton = ButtonWidget.builder(Text.literal("上一页"), button -> {
					queuePage--;
					refresh();
				})
				.dimensions(x + panelWidth / 2 - 64, y + panelHeight - 26, 58, 20)
				.build();
		prevPageButton.active = queuePage > 0;
		addDrawableChild(prevPageButton);
		ButtonWidget nextPageButton = ButtonWidget.builder(Text.literal("下一页"), button -> {
					queuePage++;
					refresh();
				})
				.dimensions(x + panelWidth / 2 + 6, y + panelHeight - 26, 58, 20)
				.build();
		nextPageButton.active = queuePage + 1 < queuePageCount(pageSize);
		addDrawableChild(nextPageButton);
	}

	private void addCommonButtons(int panelWidth, int panelHeight, int x, int y) {
		addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), button -> close())
				.dimensions(x + 12, y + panelHeight - 26, 58, 20)
				.build());
		addDrawableChild(ButtonWidget.builder(Text.literal("x²k设置"), button ->
						MinecraftClient.getInstance().setScreen(new MusicSettingsScreen(this)))
				.dimensions(x + panelWidth - 92, y + panelHeight - 26, 80, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		int panelWidth = panelWidth();
		int panelHeight = panelHeight();
		int x = panelX();
		int y = panelY();

		context.fill(x, y, x + panelWidth, y + panelHeight, 0xE0101014);
		context.fill(x, y, x + panelWidth, y + 1, 0x55FFFFFF);
		context.fill(x, y + panelHeight - 1, x + panelWidth, y + panelHeight, 0x77000000);
		context.fill(x, y, x + 1, y + panelHeight, 0x55FFFFFF);
		context.fill(x + panelWidth - 1, y, x + panelWidth, y + panelHeight, 0x33000000);

		context.drawCenteredTextWithShadow(textRenderer, "x²k点歌", x + panelWidth / 2, y + 12, 0xFFFFFFFF);
		if (queueView) {
			renderQueueView(context, panelWidth, panelHeight, x, y);
		} else {
			renderSearchView(context, panelWidth, panelHeight, x, y);
		}

		super.render(context, mouseX, mouseY, delta);
	}

	private void renderSearchView(DrawContext context, int panelWidth, int panelHeight, int x, int y) {
		context.drawTextWithShadow(textRenderer, status, x + 14, y + 61, lastSuccess ? 0xFFA8E6A1 : 0xFFFF9A9A);
		if (!query.isBlank()) {
			context.drawCenteredTextWithShadow(textRenderer, "第 " + (page + 1) + " 页", x + panelWidth / 2,
					y + panelHeight - 39, 0xFFB8B8B8);
		}

		int rowTop = y + 78;
		if (results.isEmpty()) {
			String empty = loading ? "正在搜索..." : "暂无搜索结果";
			context.drawCenteredTextWithShadow(textRenderer, empty, x + panelWidth / 2, rowTop + 42, 0xFFAAAAAA);
		}
		for (int index = 0; index < results.size(); index++) {
			SearchResult result = results.get(index);
			int rowY = rowTop + index * 31;
			context.fill(x + 12, rowY, x + panelWidth - 12, rowY + 28, 0x551A1D22);
			context.fill(x + 12, rowY, x + 13, rowY + 28, 0x66FFFFFF);
			drawTrimmed(context, (index + 1) + ". " + result.title(), x + 20, rowY + 5,
					panelWidth - 112, 0xFFFFFFFF);
			drawTrimmed(context, result.subtitle(), x + 20, rowY + 17, panelWidth - 112, 0xFFB8B8B8);
		}
	}

	private void renderQueueView(DrawContext context, int panelWidth, int panelHeight, int x, int y) {
		context.drawTextWithShadow(textRenderer, playbackStatus(), x + 14, y + 61,
				playbackPaused ? 0xFFFFD37A : 0xFFA8E6A1);
		int currentY = y + 76;
		context.fill(x + 12, currentY, x + panelWidth - 12, currentY + 42, 0x551A1D22);
		context.fill(x + 12, currentY, x + 13, currentY + 42, 0x66FFFFFF);
		if (currentEntry == null) {
			drawTrimmed(context, "当前没有播放", x + 20, currentY + 9, panelWidth - 40, 0xFFB8B8B8);
		} else {
			drawTrimmed(context, "当前：" + currentEntry.title(), x + 20, currentY + 5, panelWidth - 40, 0xFFFFFFFF);
			drawTrimmed(context, "点歌：" + currentEntry.requestedBy() + "  " + progressText(), x + 20, currentY + 17,
					panelWidth - 40, 0xFFB8B8B8);
			drawProgressBar(context, x + 20, currentY + 32, panelWidth - 40, 4, progressRatio());
		}

		int rowTop = queueRowTop(y);
		int pageSize = queuePageSize(y, panelHeight, rowTop);
		clampQueuePage(pageSize);
		int pageCount = queuePageCount(pageSize);
		int startIndex = queuePage * Math.max(1, pageSize);
		drawTrimmed(context, "播放队列（" + queuedEntries.size() + "）  第 " + (queuePage + 1) + "/" + pageCount + " 页",
				x + 14, rowTop - 14, panelWidth - 28, 0xFFE8E8E8);
		if (queuedEntries.isEmpty()) {
			context.drawCenteredTextWithShadow(textRenderer, "队列为空", x + panelWidth / 2, rowTop + 42, 0xFFAAAAAA);
			return;
		}

		int visibleRows = Math.min(pageSize, Math.max(0, queuedEntries.size() - startIndex));
		for (int index = 0; index < visibleRows; index++) {
			MusicHud.QueueEntry entry = queuedEntries.get(startIndex + index);
			int rowY = rowTop + index * 31;
			context.fill(x + 12, rowY, x + panelWidth - 12, rowY + 28, 0x551A1D22);
			context.fill(x + 12, rowY, x + 13, rowY + 28, 0x66FFFFFF);
			drawTrimmed(context, (startIndex + index + 1) + ". " + entry.title(), x + 20, rowY + 5,
					panelWidth - 154, 0xFFFFFFFF);
			drawTrimmed(context, entry.requestedBy(), x + 20, rowY + 17, panelWidth - 154, 0xFFB8B8B8);
		}
	}

	@Override
	public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (!queueView && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
			search(0);
			return true;
		}
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override
	public void close() {
		MinecraftClient.getInstance().setScreen(parent);
	}

	@Override
	public boolean shouldPause() {
		return false;
	}

	private void search(int targetPage) {
		String trimmed = query.trim();
		if (trimmed.isBlank()) {
			lastSuccess = false;
			status = "请输入歌名或歌手。";
			refresh();
			return;
		}

		query = trimmed;
		page = Math.max(0, targetPage);
		loading = true;
		lastSuccess = true;
		status = "正在搜索：" + query + "（第 " + (page + 1) + " 页）";
		if (page == 0) {
			results = List.of();
		}
		refresh();
		OnlineMusicClient.requestPanelSearch(query, page);
	}

	private void play(SearchResult result) {
		OnlineMusicClient.sendPlayCommand(result.id());
		lastSuccess = true;
		status = "已发送点歌：" + result.title();
		refresh();
	}

	private void sendCommand(String command, String message) {
		OnlineMusicClient.sendPanelCommand(command);
		lastSuccess = true;
		status = message;
		refresh();
	}

	private void applyResults(String keywords, int page, boolean success, String message, List<SearchResult> results) {
		this.query = keywords == null ? "" : keywords;
		this.page = Math.max(0, page);
		this.loading = false;
		this.lastSuccess = success;
		this.status = message == null || message.isBlank() ? (success ? "搜索完成" : "搜索失败") : message;
		this.results = results == null ? List.of() : List.copyOf(results);
		this.queueView = false;
		refresh();
	}

	private void refresh() {
		if (client != null) {
			clearAndInit();
		}
	}

	private String playbackStatus() {
		if (currentEntry == null) {
			return "播放器状态：空闲";
		}
		return "播放器状态：" + (playbackPaused ? "已暂停" : "播放中") + "  队列 " + queuedEntries.size() + " 首";
	}

	private int queueRowTop(int y) {
		return y + 142;
	}

	private int queuePageSize(int y, int panelHeight, int rowTop) {
		return Math.max(1, (y + panelHeight - 36 - rowTop) / 31);
	}

	private int queuePageCount(int pageSize) {
		return Math.max(1, (queuedEntries.size() + Math.max(1, pageSize) - 1) / Math.max(1, pageSize));
	}

	private void clampQueuePage(int pageSize) {
		queuePage = Math.max(0, Math.min(queuePage, queuePageCount(pageSize) - 1));
	}

	private String progressText() {
		if (playbackDurationMillis <= 0L) {
			return formatMillis(displayElapsedMillis());
		}
		return formatMillis(displayElapsedMillis()) + "/" + formatMillis(playbackDurationMillis);
	}

	private float progressRatio() {
		if (playbackDurationMillis <= 0L) {
			return 0.0f;
		}
		return Math.max(0.0f, Math.min(1.0f, displayElapsedMillis() / (float) playbackDurationMillis));
	}

	private long displayElapsedMillis() {
		if (currentEntry == null || playbackPaused || playbackSnapshotAtMillis <= 0L) {
			return playbackElapsedMillis;
		}
		long elapsed = playbackElapsedMillis + Math.max(0L, System.currentTimeMillis() - playbackSnapshotAtMillis);
		return playbackDurationMillis <= 0L ? elapsed : Math.min(elapsed, playbackDurationMillis);
	}

	private String formatMillis(long millis) {
		long seconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(millis));
		return (seconds / 60L) + ":" + String.format("%02d", seconds % 60L);
	}

	private void drawTrimmed(DrawContext context, String value, int x, int y, int maxWidth, int color) {
		String safeValue = value == null ? "" : value;
		String trimmed = textRenderer.trimToWidth(safeValue, Math.max(1, maxWidth));
		if (!trimmed.equals(safeValue) && maxWidth > textRenderer.getWidth("...")) {
			trimmed = textRenderer.trimToWidth(safeValue, maxWidth - textRenderer.getWidth("...")) + "...";
		}
		context.drawTextWithShadow(textRenderer, trimmed, x, y, color);
	}

	private void drawProgressBar(DrawContext context, int x, int y, int width, int height, float progress) {
		context.fill(x, y, x + width, y + height, 0x66000000);
		int filled = Math.max(0, Math.min(width, Math.round(width * progress)));
		if (filled > 0) {
			context.fill(x, y, x + filled, y + height, 0xFFE8E8E8);
		}
	}

	private int panelWidth() {
		return Math.min(438, Math.max(300, width - 32));
	}

	private int panelHeight() {
		return Math.min(274, Math.max(214, height - 36));
	}

	private int panelX() {
		return (width - panelWidth()) / 2;
	}

	private int panelY() {
		return (height - panelHeight()) / 2;
	}

	public record SearchResult(long id, String title, String artist, String album) {
		private String subtitle() {
			StringBuilder builder = new StringBuilder();
			if (artist != null && !artist.isBlank()) {
				builder.append(artist);
			}
			if (album != null && !album.isBlank()) {
				if (!builder.isEmpty()) {
					builder.append(" · ");
				}
				builder.append(album);
			}
			if (!builder.isEmpty()) {
				builder.append("  #").append(id);
				return builder.toString();
			}
			return "#" + id;
		}
	}
}
