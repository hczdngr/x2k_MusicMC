package com.xkitme.onlinemusic;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public final class OnlineMusicServer implements ModInitializer {
	private static final UUID CONSOLE_KEY = new UUID(0L, 0L);
	private static final int SEARCH_RESULT_LIMIT = 5;
	private static final String SUPPORT_SEPARATOR = "----[微软大门保安提供技术支持]----";
	private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
			.withZone(ZoneId.systemDefault());
	private static final OnlineMusicConfig CONFIG = OnlineMusicConfig.load();
	private static final NeteaseMusicService NETEASE = new NeteaseMusicService();
	private static final GlobalMusicManager GLOBAL_MUSIC = new GlobalMusicManager();
	private static final Map<UUID, List<NeteaseMusicService.SongResult>> RECENT_NETEASE_RESULTS = new ConcurrentHashMap<>();
	private static final Map<UUID, SearchSession> RECENT_SEARCH_SESSIONS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> RECENT_SEARCH_GENERATIONS = new ConcurrentHashMap<>();
	private static final AtomicLong SEARCH_GENERATION = new AtomicLong();
	private static final ExecutorService NETEASE_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "NetEase Music Request");
		thread.setDaemon(true);
		return thread;
	});

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(literal("gmusic")
				.executes(context -> withSeparator(context.getSource(), () -> openPanelOrHelp(context.getSource())))
				.then(literal("help")
						.executes(context -> withSeparator(context.getSource(), () -> help(context.getSource()))))
				.then(literal("s")
						.then(argument("keywords", StringArgumentType.greedyString())
								.executes(context -> withSeparator(context.getSource(), () -> searchNetease(context.getSource(),
										StringArgumentType.getString(context, "keywords"))))))
				.then(literal("next")
						.executes(context -> withSeparator(context.getSource(), () -> nextNeteasePage(context.getSource()))))
				.then(literal("play")
						.then(argument("songId", LongArgumentType.longArg(1L))
								.executes(context -> withSeparator(context.getSource(), () -> playNeteaseId(context.getSource(),
										LongArgumentType.getLong(context, "songId"))))))
				.then(literal("netease")
						.executes(context -> withSeparator(context.getSource(), () -> neteaseHelp(context.getSource())))
						.then(literal("search")
								.then(argument("keywords", StringArgumentType.greedyString())
										.executes(context -> withSeparator(context.getSource(), () -> searchNetease(context.getSource(),
												StringArgumentType.getString(context, "keywords"))))))
						.then(literal("next")
								.executes(context -> withSeparator(context.getSource(), () -> nextNeteasePage(context.getSource()))))
						.then(literal("play")
								.then(argument("songId", LongArgumentType.longArg(1L))
										.executes(context -> withSeparator(context.getSource(), () -> playNeteaseId(context.getSource(),
												LongArgumentType.getLong(context, "songId"))))))
						.then(literal("queue")
								.then(argument("songId", LongArgumentType.longArg(1L))
										.executes(context -> withSeparator(context.getSource(), () -> queueNeteaseId(context.getSource(),
												LongArgumentType.getLong(context, "songId"))))))
						.then(literal("playresult")
								.then(argument("index", IntegerArgumentType.integer(1, SEARCH_RESULT_LIMIT))
										.executes(context -> withSeparator(context.getSource(), () -> playNeteaseResult(context.getSource(),
												IntegerArgumentType.getInteger(context, "index"))))))
						.then(literal("queueresult")
								.then(argument("index", IntegerArgumentType.integer(1, SEARCH_RESULT_LIMIT))
										.executes(context -> withSeparator(context.getSource(), () -> queueNeteaseResult(context.getSource(),
												IntegerArgumentType.getInteger(context, "index")))))))
				.then(literal("token")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(context -> withSeparator(context.getSource(), () -> tokenHelp(context.getSource())))
						.then(literal("set")
								.then(argument("cookieOrMusicU", StringArgumentType.greedyString())
										.executes(context -> withSeparator(context.getSource(), () -> setToken(context.getSource(),
												StringArgumentType.getString(context, "cookieOrMusicU"))))))
						.then(literal("clear")
								.executes(context -> withSeparator(context.getSource(), () -> clearToken(context.getSource()))))
						.then(literal("load")
								.executes(context -> withSeparator(context.getSource(), () -> loadToken(context.getSource()))))
						.then(literal("path")
								.executes(context -> withSeparator(context.getSource(), () -> tokenPath(context.getSource()))))
						.then(literal("status")
								.executes(context -> withSeparator(context.getSource(), () -> tokenStatus(context.getSource()))))
						.then(literal("check")
								.executes(context -> withSeparator(context.getSource(), () -> tokenCheck(context.getSource())))))
				.then(literal("stop")
						.requires(source -> source.hasPermissionLevel(2))
						.executes(context -> withSeparator(context.getSource(), () -> stop(context.getSource()))))
				.then(literal("skip")
						.executes(context -> withSeparator(context.getSource(), () -> skip(context.getSource()))))
				.then(literal("prev")
						.executes(context -> withSeparator(context.getSource(), () -> previous(context.getSource()))))
				.then(literal("pause")
						.executes(context -> withSeparator(context.getSource(), () -> pause(context.getSource()))))
				.then(literal("resume")
						.executes(context -> withSeparator(context.getSource(), () -> resume(context.getSource()))))
				.then(literal("remove")
						.then(argument("index", IntegerArgumentType.integer(1))
								.executes(context -> withSeparator(context.getSource(), () -> removeQueued(context.getSource(),
										IntegerArgumentType.getInteger(context, "index"))))))
				.then(literal("delete")
						.then(argument("index", IntegerArgumentType.integer(1))
								.executes(context -> withSeparator(context.getSource(), () -> removeQueued(context.getSource(),
										IntegerArgumentType.getInteger(context, "index"))))))
				.then(literal("top")
						.then(argument("index", IntegerArgumentType.integer(1))
								.executes(context -> withSeparator(context.getSource(), () -> topQueued(context.getSource(),
										IntegerArgumentType.getInteger(context, "index"))))))
				.then(literal("off")
						.executes(context -> withSeparator(context.getSource(), () -> setPersonalListening(context.getSource(), false))))
				.then(literal("on")
						.executes(context -> withSeparator(context.getSource(), () -> setPersonalListening(context.getSource(), true))))
				.then(literal("now")
						.executes(context -> withSeparator(context.getSource(), () -> now(context.getSource()))))
				.then(literal("lyrics")
						.executes(context -> withSeparator(context.getSource(), () -> lyricsHelp(context.getSource())))
						.then(literal("on")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_LYRICS_VISIBLE, true, "歌词显示已打开。", "歌词显示已关闭。"))))
						.then(literal("off")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_LYRICS_VISIBLE, false, "歌词显示已打开。", "歌词显示已关闭。"))))
						.then(literal("open")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_LYRICS_VISIBLE, true, "歌词显示已打开。", "歌词显示已关闭。"))))
						.then(literal("close")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_LYRICS_VISIBLE, false, "歌词显示已打开。", "歌词显示已关闭。"))))
						.then(literal("打开")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_LYRICS_VISIBLE, true, "歌词显示已打开。", "歌词显示已关闭。"))))
						.then(literal("关闭")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_LYRICS_VISIBLE, false, "歌词显示已打开。", "歌词显示已关闭。"))))
						.then(literal("anim")
								.then(literal("on")
										.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
												MusicNetworking.HUD_SETTING_LYRIC_ANIMATION, true, "歌词动效已打开。", "歌词动效已关闭。"))))
								.then(literal("off")
										.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
												MusicNetworking.HUD_SETTING_LYRIC_ANIMATION, false, "歌词动效已打开。", "歌词动效已关闭。"))))
								.then(literal("打开")
										.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
												MusicNetworking.HUD_SETTING_LYRIC_ANIMATION, true, "歌词动效已打开。", "歌词动效已关闭。"))))
								.then(literal("关闭")
										.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
												MusicNetworking.HUD_SETTING_LYRIC_ANIMATION, false, "歌词动效已打开。", "歌词动效已关闭。"))))))
				.then(literal("panel")
						.executes(context -> withSeparator(context.getSource(), () -> panelHelp(context.getSource())))
						.then(literal("on")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_PANEL_VISIBLE, true, "右侧面板已打开。", "右侧面板已关闭。"))))
						.then(literal("off")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_PANEL_VISIBLE, false, "右侧面板已打开。", "右侧面板已关闭。"))))
						.then(literal("open")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_PANEL_VISIBLE, true, "右侧面板已打开。", "右侧面板已关闭。"))))
						.then(literal("close")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_PANEL_VISIBLE, false, "右侧面板已打开。", "右侧面板已关闭。"))))
						.then(literal("打开")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_PANEL_VISIBLE, true, "右侧面板已打开。", "右侧面板已关闭。"))))
						.then(literal("关闭")
								.executes(context -> withSeparator(context.getSource(), () -> setHudBoolean(context.getSource(),
										MusicNetworking.HUD_SETTING_PANEL_VISIBLE, false, "右侧面板已打开。", "右侧面板已关闭。")))))
				.then(literal("list")
						.executes(context -> withSeparator(context.getSource(), () -> list(context.getSource())))
						.then(argument("index", IntegerArgumentType.integer(1, SEARCH_RESULT_LIMIT))
								.executes(context -> withSeparator(context.getSource(), () -> playNeteaseResult(context.getSource(),
										IntegerArgumentType.getInteger(context, "index"))))))));

		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.GLOBAL_FINISHED,
				(server, player, handler, buffer, responseSender) -> {
					long playId = buffer.readLong();
					server.execute(() -> GLOBAL_MUSIC.clientFinished(server, player, playId));
				});
		ServerPlayNetworking.registerGlobalReceiver(MusicNetworking.PANEL_SEARCH,
				(server, player, handler, buffer, responseSender) -> {
					String keywords = buffer.readString();
					int page = buffer.readVarInt();
					server.execute(() -> searchPanel(server, player, keywords, page));
				});
	}

	private int openPanelOrHelp(ServerCommandSource source) {
		if (source.getEntity() instanceof ServerPlayerEntity player
				&& ServerPlayNetworking.canSend(player, MusicNetworking.OPEN_PANEL)) {
			ServerPlayNetworking.send(player, MusicNetworking.OPEN_PANEL, PacketByteBufs.empty());
			GLOBAL_MUSIC.sendQueueTo(player);
			send(source, success("已打开点歌面板。"));
			return 1;
		}
		return help(source);
	}

	private int help(ServerCommandSource source) {
		send(source, success("全局点歌说明：所有玩家听同一首歌，不提供自己单独听。"));
		send(source, commandLine("/gmusic s <歌名>", "搜索网易云前 5 项"));
		send(source, commandLine("/gmusic next", "查看当前搜索的下一页，每页 5 首"));
		send(source, commandLine("/gmusic list <编号>", "选择搜索结果；正在播放时自动加入队列，例如 /gmusic list 1"));
		send(source, commandLine("/gmusic play <歌曲ID>", "用歌曲 ID 点歌；正在播放时自动加入队列"));
		send(source, commandLine("/gmusic netease play <歌曲ID>", "同 /gmusic play <歌曲ID>"));
		send(source, commandLine("/gmusic netease queue <歌曲ID>", "加入全局队列"));
		send(source, commandLine("/gmusic now", "查看当前全员播放"));
		send(source, commandLine("/gmusic list", "查看全局队列"));
		send(source, commandLine("/gmusic pause / resume", "暂停或继续全局点歌"));
		send(source, commandLine("/gmusic skip", "跳过当前全员播放"));
		send(source, commandLine("/gmusic prev", "切回上一首"));
		send(source, commandLine("/gmusic top <队列编号>", "把排队歌曲置顶到下一首"));
		send(source, commandLine("/gmusic remove <队列编号>", "删除一首排队歌曲"));
		send(source, commandLine("/gmusic off", "自己不听当前和后续点歌"));
		send(source, commandLine("/gmusic on", "恢复自己收听全局点歌"));
		send(source, commandLine("/gmusic lyrics on/off", "打开或关闭物品栏上方的一行歌词"));
		send(source, commandLine("/gmusic lyrics anim on/off", "打开或关闭歌词上飞动效"));
		send(source, commandLine("/gmusic panel on/off", "打开或关闭右侧歌曲面板"));
		if (source.hasPermissionLevel(2)) {
			send(source, commandLine("/gmusic token set <MUSIC_U 或完整 cookie>", "房主/OP 保存网易云登录凭证"));
			send(source, commandLine("/gmusic token load", "从服务器本地文件读取网易云登录凭证"));
			send(source, commandLine("/gmusic token path", "显示本地凭证文件位置"));
			send(source, commandLine("/gmusic token check", "检查网易云是否识别为登录/VIP"));
			send(source, commandLine("/gmusic stop", "停止全员播放并清空队列"));
		}
		return 1;
	}

	private int neteaseHelp(ServerCommandSource source) {
		send(source, success("网易云全局点歌：服务器用房主登录凭证换播放地址，再广播给所有客户端。"));
		send(source, commandLine("/gmusic s <歌名>", "搜索前 5 项，推荐用这个短命令"));
		send(source, commandLine("/gmusic next", "查看当前搜索的下一页"));
		send(source, commandLine("/gmusic list <编号>", "选择搜索结果；正在播放时自动加入队列"));
		send(source, commandLine("/gmusic play <歌曲ID>", "不搜索，直接用歌曲 ID 点歌"));
		send(source, commandLine("/gmusic netease play <歌曲ID>", "同 /gmusic play <歌曲ID>"));
		send(source, commandLine("/gmusic netease queue <歌曲ID>", "不搜索，直接用歌曲 ID 加入全局队列"));
		return 1;
	}

	private int lyricsHelp(ServerCommandSource source) {
		send(source, success("歌词 HUD：默认只显示当前一行歌词，使用模组内置字体 online_music:lyrics。"));
		send(source, commandLine("/gmusic lyrics on", "打开歌词显示"));
		send(source, commandLine("/gmusic lyrics off", "关闭歌词显示"));
		send(source, commandLine("/gmusic lyrics anim on", "打开歌词上飞动效"));
		send(source, commandLine("/gmusic lyrics anim off", "关闭歌词动效，固定显示一行"));
		return 1;
	}

	private int panelHelp(ServerCommandSource source) {
		send(source, success("右侧面板：显示当前歌曲、点歌人、专辑封面和完整队列。"));
		send(source, commandLine("/gmusic panel on", "打开右侧面板"));
		send(source, commandLine("/gmusic panel off", "关闭右侧面板"));
		return 1;
	}

	private int tokenHelp(ServerCommandSource source) {
		send(source, success("登录凭证只保存在房主/服务器本机，不会发给其他玩家。"));
		send(source, commandLine("/gmusic token set <MUSIC_U 或完整 cookie>", "保存网易云登录凭证"));
		send(source, commandLine("/gmusic token load", "读取 config/x2k_netease_cookie.txt"));
		send(source, commandLine("/gmusic token path", "显示本地凭证文件位置"));
		send(source, commandLine("/gmusic token status", "查看是否已配置，显示会自动打码"));
		send(source, commandLine("/gmusic token check", "实时检查网易云账号和 VIP 识别状态"));
		send(source, commandLine("/gmusic token clear", "删除本机保存的登录凭证"));
		return 1;
	}

	private int searchNetease(ServerCommandSource source, String keywords) {
		return searchNeteasePage(source, keywords, 0);
	}

	private void searchPanel(MinecraftServer server, ServerPlayerEntity player, String rawKeywords, int page) {
		String keywords = rawKeywords == null ? "" : rawKeywords.trim();
		int safePage = Math.max(0, page);
		if (keywords.isBlank()) {
			sendPanelResults(player, keywords, safePage, false, "请输入歌名或歌手。", List.of());
			return;
		}
		if (!CONFIG.hasNeteaseCookie()) {
			sendPanelResults(player, keywords, safePage, false,
					"还没有设置网易云登录凭证。房主/OP 需要先配置 MUSIC_U。", List.of());
			return;
		}

		UUID key = player.getUuid();
		int offset = safePage * SEARCH_RESULT_LIMIT;
		long generation = SEARCH_GENERATION.incrementAndGet();
		if (safePage == 0) {
			RECENT_NETEASE_RESULTS.remove(key);
			RECENT_SEARCH_SESSIONS.remove(key);
		}
		RECENT_SEARCH_GENERATIONS.put(key, generation);
		CompletableFuture.runAsync(() -> {
			try {
				List<NeteaseMusicService.SongResult> results = NETEASE.search(keywords, CONFIG.neteaseCookie(),
						offset, SEARCH_RESULT_LIMIT);
				server.execute(() -> {
					if (!isLatestSearch(key, generation)) {
						return;
					}
					if (results.isEmpty()) {
						sendPanelResults(player, keywords, safePage, false,
								safePage == 0 ? "没有找到网易云搜索结果。" : "已经没有下一页了。", List.of());
						return;
					}
					List<NeteaseMusicService.SongResult> limitedResults = results.size() > SEARCH_RESULT_LIMIT
							? results.subList(0, SEARCH_RESULT_LIMIT) : results;
					RECENT_NETEASE_RESULTS.put(key, List.copyOf(limitedResults));
					RECENT_SEARCH_SESSIONS.put(key, new SearchSession(keywords, safePage));
					sendPanelResults(player, keywords, safePage, true,
							"第 " + (safePage + 1) + " 页，共 " + limitedResults.size() + " 首", limitedResults);
				});
			} catch (Exception exception) {
				server.execute(() -> {
					if (isLatestSearch(key, generation)) {
						sendPanelResults(player, keywords, safePage, false,
								"网易云搜索失败：" + exception.getMessage(), List.of());
					}
				});
			}
		}, NETEASE_EXECUTOR);
	}

	private int nextNeteasePage(ServerCommandSource source) {
		if (!hasToken(source)) {
			return 0;
		}

		SearchSession session = RECENT_SEARCH_SESSIONS.get(sourceKey(source));
		if (session == null || session.keywords().isBlank()) {
			send(source, error("还没有可翻页的搜索结果。请先输入 /gmusic s <歌名>。"));
			return 0;
		}
		return searchNeteasePage(source, session.keywords(), session.page() + 1);
	}

	private int searchNeteasePage(ServerCommandSource source, String keywords, int page) {
		if (!hasToken(source)) {
			return 0;
		}

		UUID key = sourceKey(source);
		int safePage = Math.max(0, page);
		int offset = safePage * SEARCH_RESULT_LIMIT;
		long generation = SEARCH_GENERATION.incrementAndGet();
		if (safePage == 0) {
			RECENT_NETEASE_RESULTS.remove(key);
			RECENT_SEARCH_SESSIONS.remove(key);
		}
		RECENT_SEARCH_GENERATIONS.put(key, generation);
		send(source, info("正在搜索网易云：" + keywords + "（第 " + (safePage + 1) + " 页）"));
		CompletableFuture.runAsync(() -> {
			try {
				List<NeteaseMusicService.SongResult> results = NETEASE.search(keywords, CONFIG.neteaseCookie(),
						offset, SEARCH_RESULT_LIMIT);
				if (!isLatestSearch(key, generation)) {
					return;
				}

				if (results.isEmpty()) {
					send(source, warning(safePage == 0 ? "没有找到网易云搜索结果。" : "已经没有下一页了。"));
					return;
				}

				List<NeteaseMusicService.SongResult> limitedResults = results.size() > SEARCH_RESULT_LIMIT
						? results.subList(0, SEARCH_RESULT_LIMIT) : results;
				RECENT_NETEASE_RESULTS.put(key, List.copyOf(limitedResults));
				RECENT_SEARCH_SESSIONS.put(key, new SearchSession(keywords, safePage));
				send(source, success("搜索结果第 " + (safePage + 1)
						+ " 页：/gmusic list <编号> 选择；也可以点击 [点歌] 填入 /gmusic play <歌曲ID>。"));
				for (int index = 0; index < limitedResults.size(); index++) {
					send(source, neteaseResultLine(index + 1, limitedResults.get(index)));
				}
				send(source, commandLine("/gmusic next", "下一页"));
			} catch (Exception exception) {
				if (!isLatestSearch(key, generation)) {
					return;
				}
				send(source, error("网易云搜索失败：" + exception.getMessage()));
			}
		}, NETEASE_EXECUTOR);

		return 1;
	}

	private void sendPanelResults(ServerPlayerEntity player, String keywords, int page, boolean success, String message,
								  List<NeteaseMusicService.SongResult> results) {
		if (!ServerPlayNetworking.canSend(player, MusicNetworking.PANEL_RESULTS)) {
			return;
		}

		PacketByteBuf buffer = PacketByteBufs.create();
		buffer.writeString(keywords == null ? "" : keywords);
		buffer.writeVarInt(Math.max(0, page));
		buffer.writeBoolean(success);
		buffer.writeString(message == null ? "" : message);
		buffer.writeVarInt(results.size());
		for (NeteaseMusicService.SongResult result : results) {
			buffer.writeLong(result.id());
			buffer.writeString(result.title());
			buffer.writeString(result.artist());
			buffer.writeString(result.album());
		}
		ServerPlayNetworking.send(player, MusicNetworking.PANEL_RESULTS, buffer);
	}

	private int playNeteaseId(ServerCommandSource source, long songId) {
		return resolveAndSubmit(source, null, songId, true);
	}

	private int queueNeteaseId(ServerCommandSource source, long songId) {
		return resolveAndSubmit(source, null, songId, false);
	}

	private int playNeteaseResult(ServerCommandSource source, int index) {
		NeteaseMusicService.SongResult song = consumeRecentNeteaseResult(source, index);
		if (song == null) {
			send(source, error("没有可用的第 " + index + " 个搜索结果，或搜索结果已失效。请重新输入 /gmusic s <歌名>。"));
			return 0;
		}
		send(source, info("已选择第 " + index + " 首，本次搜索结果已失效。"));
		return resolveAndSubmit(source, song, song.id(), true);
	}

	private int queueNeteaseResult(ServerCommandSource source, int index) {
		NeteaseMusicService.SongResult song = consumeRecentNeteaseResult(source, index);
		if (song == null) {
			send(source, error("没有可用的第 " + index + " 个搜索结果，或搜索结果已失效。请重新输入 /gmusic s <歌名>。"));
			return 0;
		}
		send(source, info("已选择第 " + index + " 首，本次搜索结果已失效。"));
		return resolveAndSubmit(source, song, song.id(), false);
	}

	private int resolveAndSubmit(ServerCommandSource source, NeteaseMusicService.SongResult song, long songId, boolean playNow) {
		if (!hasToken(source)) {
			return 0;
		}

		send(source, info("正在获取网易云播放地址..."));
		CompletableFuture.runAsync(() -> {
			try {
				NeteaseMusicService.ResolvedTrack resolved = song == null
						? NETEASE.resolveTrack(songId, CONFIG.neteaseCookie())
						: NETEASE.resolveTrack(song, CONFIG.neteaseCookie());
				GlobalMusicTrack track = new GlobalMusicTrack(resolved.title(), resolved.url(),
						resolved.durationMillis(), source.getName(), resolved.coverUrl(), resolved.lyrics());

				MinecraftServer server = source.getServer();
				server.execute(() -> {
					int position = GLOBAL_MUSIC.queue(server, track);
					if (position == 0) {
						send(source, success("全员正在播放：" + track.title()));
					} else {
						String prefix = playNow ? "当前已有歌曲在播放，已加入全局队列第 " : "已加入全局队列第 ";
						send(source, success(prefix + position + " 位：" + track.title()));
					}
				});
			} catch (Exception exception) {
				send(source, error("无法播放网易云歌曲：" + exception.getMessage()));
			}
		}, NETEASE_EXECUTOR);

		return 1;
	}

	private int setToken(ServerCommandSource source, String cookieOrMusicU) {
		CONFIG.setNeteaseCookie(cookieOrMusicU);
		if (!CONFIG.hasMusicU()) {
			CONFIG.clearNeteaseCookie();
			send(source, error("这串内容里没有识别到 MUSIC_U，已拒绝保存。Minecraft 聊天框可能会截断超长 cookie，请只复制 MUSIC_U=后面的值，或直接编辑 config/online_music.json。"));
			return 0;
		}

		try {
			CONFIG.save();
			send(source, success("网易云登录凭证已保存在房主/服务器本机：" + CONFIG.maskedNeteaseCookie()));
			return 1;
		} catch (IOException exception) {
			send(source, error("保存登录凭证失败：" + exception.getMessage()));
			return 0;
		}
	}

	private int clearToken(ServerCommandSource source) {
		CONFIG.clearNeteaseCookie();
		try {
			CONFIG.save();
			send(source, success("网易云登录凭证已删除。"));
			return 1;
		} catch (IOException exception) {
			send(source, error("保存配置失败：" + exception.getMessage()));
			return 0;
		}
	}

	private int loadToken(ServerCommandSource source) {
		try {
			if (!CONFIG.loadNeteaseCookieFromLocalFile()) {
				CONFIG.clearNeteaseCookie();
				send(source, error("本地文件里没有识别到 MUSIC_U，未保存。请把 MUSIC_U=... 或完整 Cookie 放入：" + CONFIG.cookieFilePath()));
				return 0;
			}

			CONFIG.save();
			send(source, success("已从本地文件读取网易云登录凭证：" + CONFIG.maskedNeteaseCookie()));
			send(source, info("建议继续输入 /gmusic token check 检查网易云是否识别为登录/VIP。"));
			return 1;
		} catch (IOException exception) {
			send(source, error("读取本地登录凭证失败：" + exception.getMessage()));
			return 0;
		} catch (RuntimeException exception) {
			send(source, error("读取本地登录凭证时出现异常：" + exception.getClass().getSimpleName() + "：" + exception.getMessage()));
			return 0;
		}
	}

	private int tokenPath(ServerCommandSource source) {
		try {
			CONFIG.prepareCookieFile();
			send(source, info("请把 MUSIC_U=... 放到这个文件："));
			send(source, success(CONFIG.cookieFilePath().toAbsolutePath().toString()));
			send(source, info("保存文件后，在游戏内执行 /gmusic token load"));
			return 1;
		} catch (Exception exception) {
			send(source, error("准备本地凭证文件失败：" + exception.getMessage()));
			return 0;
		}
	}

	private int tokenStatus(ServerCommandSource source) {
		send(source, info("网易云登录凭证：" + CONFIG.maskedNeteaseCookie()));
		return CONFIG.hasNeteaseCookie() ? 1 : 0;
	}

	private int tokenCheck(ServerCommandSource source) {
		if (!hasToken(source)) {
			return 0;
		}

		send(source, info("正在向网易云检查登录和 VIP 状态..."));
		CompletableFuture.runAsync(() -> {
			try {
				NeteaseMusicService.AccountStatus status = NETEASE.checkAccount(CONFIG.neteaseCookie());
				send(source, status.loggedIn()
						? success("网易云已识别登录：" + displayAccount(status))
						: error("网易云没有识别为已登录。请重新复制当前浏览器/客户端的 MUSIC_U 或完整 cookie。"));
				send(source, status.vipActive()
						? success("VIP 已识别：" + displayVip(status))
						: error("VIP 未识别：" + displayVip(status) + "。如果你确认账号有 VIP，请重新设置最新 MUSIC_U。"));
			} catch (Exception exception) {
				send(source, error("检查网易云登录/VIP 状态失败：" + exception.getMessage()));
			}
		}, NETEASE_EXECUTOR);
		return 1;
	}

	private String displayAccount(NeteaseMusicService.AccountStatus status) {
		String name = status.nickname().isBlank() ? "未知昵称" : status.nickname();
		return name + "（ID " + status.userId() + "，账号 VIP 类型 " + status.accountVipType() + "）";
	}

	private String displayVip(NeteaseMusicService.AccountStatus status) {
		return "黑胶等级 " + status.redVipLevel()
				+ "，音乐包 " + status.musicPackageCode() + " 到期 " + displayTime(status.musicPackageExpireTime())
				+ "，会员 " + status.associatorCode() + " 到期 " + displayTime(status.associatorExpireTime());
	}

	private String displayTime(long millis) {
		if (millis <= 0L) {
			return "无";
		}
		return TIME_FORMATTER.format(Instant.ofEpochMilli(millis));
	}

	private int stop(ServerCommandSource source) {
		boolean stopped = GLOBAL_MUSIC.stop(source.getServer());
		send(source, stopped ? success("已停止全员播放并清空队列。") : warning("当前没有全员播放。"));
		return stopped ? 1 : 0;
	}

	private int skip(ServerCommandSource source) {
		boolean skipped = GLOBAL_MUSIC.skip(source.getServer());
		send(source, skipped ? success("已跳过当前歌曲。") : warning("当前没有全员播放。"));
		return skipped ? 1 : 0;
	}

	private int previous(ServerCommandSource source) {
		boolean changed = GLOBAL_MUSIC.previous(source.getServer());
		send(source, changed ? success("已切回上一首。") : warning("没有可切回的上一首。"));
		return changed ? 1 : 0;
	}

	private int pause(ServerCommandSource source) {
		boolean paused = GLOBAL_MUSIC.pause(source.getServer());
		send(source, paused ? success("已暂停全局点歌。") : warning("当前没有播放，或已经暂停。"));
		return paused ? 1 : 0;
	}

	private int resume(ServerCommandSource source) {
		boolean resumed = GLOBAL_MUSIC.resume(source.getServer());
		send(source, resumed ? success("已继续全局点歌。") : warning("当前没有暂停中的歌曲。"));
		return resumed ? 1 : 0;
	}

	private int removeQueued(ServerCommandSource source, int index) {
		GlobalMusicTrack removed = GLOBAL_MUSIC.removeQueued(source.getServer(), index);
		send(source, removed == null ? error("队列里没有第 " + index + " 首。")
				: success("已删除队列第 " + index + " 首：" + removed.title()));
		return removed == null ? 0 : 1;
	}

	private int topQueued(ServerCommandSource source, int index) {
		GlobalMusicTrack moved = GLOBAL_MUSIC.topQueued(source.getServer(), index);
		send(source, moved == null ? error("队列里没有第 " + index + " 首。")
				: success("已置顶到下一首：" + moved.title()));
		return moved == null ? 0 : 1;
	}

	private int setPersonalListening(ServerCommandSource source, boolean listening) {
		if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
			send(source, error("只有玩家可以切换个人收听状态。"));
			return 0;
		}

		boolean changed = GLOBAL_MUSIC.setMuted(source.getServer(), player, !listening);
		if (listening) {
			send(source, changed ? success("你已恢复收听全局点歌。") : warning("你已经在收听全局点歌。"));
		} else {
			send(source, changed ? success("你已关闭个人收听，右侧公告栏仍会显示当前歌曲。") : warning("你已经关闭个人收听。"));
		}
		return 1;
	}

	private int setHudBoolean(ServerCommandSource source, int setting, boolean enabled, String enabledMessage,
							  String disabledMessage) {
		if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
			send(source, error("只有玩家可以切换自己的歌词/面板显示。"));
			return 0;
		}
		if (!ServerPlayNetworking.canSend(player, MusicNetworking.HUD_SETTINGS)) {
			send(source, error("你的客户端没有安装当前版本的 x²k点歌，无法切换 HUD 设置。"));
			return 0;
		}

		PacketByteBuf buffer = PacketByteBufs.create();
		buffer.writeVarInt(setting);
		buffer.writeBoolean(enabled);
		ServerPlayNetworking.send(player, MusicNetworking.HUD_SETTINGS, buffer);
		send(source, success(enabled ? enabledMessage : disabledMessage));
		return 1;
	}

	private int now(ServerCommandSource source) {
		GlobalMusicTrack track = GLOBAL_MUSIC.currentTrack();
		if (track == null) {
			send(source, warning("当前没有全员播放。"));
			return 0;
		}

		send(source, info("当前歌曲：" + track.title() + "，点歌人：" + track.requestedBy()));
		return 1;
	}

	private int list(ServerCommandSource source) {
		GlobalMusicTrack current = GLOBAL_MUSIC.currentTrack();
		List<GlobalMusicTrack> queued = GLOBAL_MUSIC.queuedTracks();
		if (current == null && queued.isEmpty()) {
			send(source, warning("当前没有播放，队列也为空。"));
			return 0;
		}

		send(source, success("完整歌单："));
		int count = 0;
		if (current != null) {
			send(source, Text.literal("正在播放. " + current.title() + "，点歌人：" + current.requestedBy())
					.formatted(Formatting.AQUA));
			count++;
		}
		for (int index = 0; index < queued.size(); index++) {
			GlobalMusicTrack track = queued.get(index);
			send(source, Text.literal((index + 1) + ". " + track.title() + "，点歌人：" + track.requestedBy())
					.formatted(Formatting.WHITE));
			count++;
		}
		return count;
	}

	private MutableText neteaseResultLine(int index, NeteaseMusicService.SongResult result) {
		String listCommand = "/gmusic list " + index;
		String playCommand = "/gmusic play " + result.id();

		MutableText line = Text.literal(index + ". ").formatted(Formatting.GRAY)
				.append(Text.literal(result.displayTitle()).formatted(Formatting.WHITE))
				.append(Text.literal(" #" + result.id()).formatted(Formatting.DARK_GRAY));

		if (!result.album().isBlank()) {
			line.append(Text.literal(" (" + result.album() + ")").formatted(Formatting.DARK_GRAY));
		}

		line.append(Text.literal(" "));
		line.append(action("[点歌]", playCommand, "点击填入聊天框：" + playCommand, Formatting.GREEN));
		line.append(Text.literal(" "));
		line.append(action("[编号]", listCommand, "点击填入聊天框：" + listCommand, Formatting.YELLOW));
		line.append(Text.literal("  " + listCommand).formatted(Formatting.GRAY));
		return line;
	}

	private Text action(String label, String command, String hover, Formatting color) {
		return Text.literal(label).styled(style -> style
				.withColor(color)
				.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal(hover))));
	}

	private Text commandLine(String command, String description) {
		MutableText line = Text.literal(command).styled(style -> style
				.withColor(Formatting.GREEN)
				.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
				.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("点击填入聊天框"))));
		return line.append(Text.literal("  " + description).formatted(Formatting.GRAY));
	}

	private NeteaseMusicService.SongResult consumeRecentNeteaseResult(ServerCommandSource source, int index) {
		UUID key = sourceKey(source);
		List<NeteaseMusicService.SongResult> results = RECENT_NETEASE_RESULTS.get(key);
		if (results == null) {
			return null;
		}

		int zeroBasedIndex = index - 1;
		if (zeroBasedIndex < 0 || zeroBasedIndex >= results.size()) {
			return null;
		}

		NeteaseMusicService.SongResult result = results.get(zeroBasedIndex);
		RECENT_NETEASE_RESULTS.remove(key);
		RECENT_SEARCH_SESSIONS.remove(key);
		RECENT_SEARCH_GENERATIONS.remove(key);
		return result;
	}

	private boolean isLatestSearch(UUID key, long generation) {
		return RECENT_SEARCH_GENERATIONS.getOrDefault(key, -1L) == generation;
	}

	private boolean hasToken(ServerCommandSource source) {
		if (CONFIG.hasNeteaseCookie()) {
			return true;
		}
		send(source, error("还没有设置网易云登录凭证。房主/OP 可以输入 /gmusic token set <MUSIC_U 或完整 cookie>。"));
		return false;
	}

	private UUID sourceKey(ServerCommandSource source) {
		if (source.getEntity() instanceof ServerPlayerEntity player) {
			return player.getUuid();
		}
		return CONSOLE_KEY;
	}

	private int withSeparator(ServerCommandSource source, CommandRunner command) {
		sendSeparator(source);
		return command.run();
	}

	private void sendSeparator(ServerCommandSource source) {
		source.getServer().execute(() -> source.sendFeedback(() ->
				Text.literal(SUPPORT_SEPARATOR).formatted(Formatting.DARK_GRAY), false));
	}

	private void send(ServerCommandSource source, Text message) {
		source.getServer().execute(() -> source.sendFeedback(() -> prefix().append(message), false));
	}

	private MutableText prefix() {
		return Text.literal("[x²k点歌] ").formatted(Formatting.LIGHT_PURPLE);
	}

	private Text info(String message) {
		return Text.literal(message).formatted(Formatting.AQUA);
	}

	private Text success(String message) {
		return Text.literal(message).formatted(Formatting.GREEN);
	}

	private Text warning(String message) {
		return Text.literal(message).formatted(Formatting.YELLOW);
	}

	private Text error(String message) {
		return Text.literal(message).formatted(Formatting.RED);
	}

	@FunctionalInterface
	private interface CommandRunner {
		int run();
	}

	private record SearchSession(String keywords, int page) {
	}
}
