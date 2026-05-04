package com.xkitme.onlinemusic;

import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Consumer;

public final class Messenger {
	private Messenger() {
	}

	public static Consumer<Text> clientSink() {
		return message -> MinecraftClient.getInstance().execute(() -> {
			if (MinecraftClient.getInstance().player != null) {
				MinecraftClient.getInstance().player.sendMessage(prefix().append(message), false);
			}
		});
	}

	public static void send(FabricClientCommandSource source, Text message) {
		MinecraftClient.getInstance().execute(() -> source.sendFeedback(prefix().append(message)));
	}

	public static Text info(String message) {
		return Text.literal(message).formatted(Formatting.AQUA);
	}

	public static Text success(String message) {
		return Text.literal(message).formatted(Formatting.GREEN);
	}

	public static Text warning(String message) {
		return Text.literal(message).formatted(Formatting.YELLOW);
	}

	public static Text error(String message) {
		return Text.literal(message).formatted(Formatting.RED);
	}

	private static MutableText prefix() {
		return Text.literal("[x²k点歌] ").formatted(Formatting.LIGHT_PURPLE);
	}
}
