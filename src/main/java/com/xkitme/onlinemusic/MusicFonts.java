package com.xkitme.onlinemusic;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class MusicFonts {
	public static final String LYRICS_FONT = "online_music:lyrics";
	public static final String DEFAULT_FONT = LYRICS_FONT;

	private MusicFonts() {
	}

	public static String normalize(String rawFont) {
		String trimmed = rawFont == null ? "" : rawFont.trim();
		if (trimmed.isBlank()) {
			return "";
		}

		String normalized = trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
		try {
			new Identifier(normalized);
			return normalized;
		} catch (RuntimeException exception) {
			return "";
		}
	}

	public static Identifier identifier(String rawFont) {
		String normalized = normalize(rawFont);
		if (normalized.isBlank()) {
			normalized = DEFAULT_FONT;
		}
		return new Identifier(normalized);
	}

	public static MutableText literal(String text, String font) {
		return apply(Text.literal(text), font);
	}

	public static MutableText apply(MutableText text, String font) {
		return text.styled(style -> style.withFont(identifier(font)));
	}

	public static MutableText apply(Text text, String font) {
		return apply(text.copy(), font);
	}
}
