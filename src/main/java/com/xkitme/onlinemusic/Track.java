package com.xkitme.onlinemusic;

public record Track(String title, String url, long startOffsetMillis) {
	public Track(String title, String url) {
		this(title, url, 0L);
	}
}
