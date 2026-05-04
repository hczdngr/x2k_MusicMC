package com.xkitme.onlinemusic;

import net.minecraft.util.Identifier;

public final class MusicNetworking {
	public static final Identifier GLOBAL_PLAY = new Identifier("online_music", "global_play");
	public static final Identifier GLOBAL_STOP = new Identifier("online_music", "global_stop");
	public static final Identifier GLOBAL_PAUSE = new Identifier("online_music", "global_pause");
	public static final Identifier GLOBAL_FINISHED = new Identifier("online_music", "global_finished");
	public static final Identifier GLOBAL_QUEUE = new Identifier("online_music", "global_queue");
	public static final Identifier HUD_SETTINGS = new Identifier("online_music", "hud_settings");
	public static final Identifier OPEN_PANEL = new Identifier("online_music", "open_panel");
	public static final Identifier PANEL_SEARCH = new Identifier("online_music", "panel_search");
	public static final Identifier PANEL_RESULTS = new Identifier("online_music", "panel_results");

	public static final int HUD_SETTING_LYRICS_VISIBLE = 0;
	public static final int HUD_SETTING_PANEL_VISIBLE = 1;
	public static final int HUD_SETTING_LYRIC_ANIMATION = 2;

	private MusicNetworking() {
	}
}
