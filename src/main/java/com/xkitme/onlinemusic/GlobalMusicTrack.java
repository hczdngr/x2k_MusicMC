package com.xkitme.onlinemusic;

import java.util.List;

public record GlobalMusicTrack(String title, String url, long durationMillis, String requestedBy, String coverUrl,
							   List<LyricLine> lyrics) {
}
