package com.xkitme.onlinemusic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.MultilineTextWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.text.Text;

public final class MusicSettingsScreen extends Screen {
	private final Screen parent;
	private final MusicClientSettings settings = MusicClientSettings.instance();

	public MusicSettingsScreen(Screen parent) {
		super(Text.literal("x²k点歌设置"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int contentWidth = Math.min(280, width - 32);
		int x = (width - contentWidth) / 2;
		int y = Math.max(24, height / 2 - 98);

		addDrawableChild(new TextWidget(x, y, contentWidth, 20, Text.literal("x²k点歌设置"), textRenderer)
				.alignCenter()
				.setTextColor(0xFFFFFFFF));
		y += 26;

		addDrawableChild(new VolumeSlider(x, y, contentWidth, 20));
		y += 28;

		addDrawableChild(toggleButton(x, y, contentWidth, "歌词显示", settings.lyricsVisible(),
				value -> {
					settings.setLyricsVisible(value);
					settings.saveIfDirty();
					MusicHud.applySettings(settings);
				}));
		y += 24;

		addDrawableChild(toggleButton(x, y, contentWidth, "歌词动效", settings.lyricAnimation(),
				value -> {
					settings.setLyricAnimation(value);
					settings.saveIfDirty();
					MusicHud.applySettings(settings);
				}));
		y += 24;

		addDrawableChild(toggleButton(x, y, contentWidth, "右侧面板", settings.panelVisible(),
				value -> {
					settings.setPanelVisible(value);
					settings.saveIfDirty();
					MusicHud.applySettings(settings);
				}));
		y += 24;

		addDrawableChild(toggleButton(x, y, contentWidth, "个人收听", settings.personalListening(),
				value -> {
					settings.setPersonalListening(value);
					settings.saveIfDirty();
					OnlineMusicClient.sendPersonalListeningCommand(value);
				}));
		y += 28;

		MultilineTextWidget note = new MultilineTextWidget(x, y,
				Text.literal("歌词和面板使用 Minecraft 默认方块字体。实际音量会跟随 Minecraft 主音量和音乐音量。"),
				textRenderer);
		note.setMaxWidth(contentWidth).setMaxRows(3).setTextColor(0xFFB8B8B8);
		addDrawableChild(note);
		y += 44;

		addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> close())
				.dimensions(x, Math.min(height - 28, y), contentWidth, 20)
				.build());
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		renderBackground(context);
		super.render(context, mouseX, mouseY, delta);
	}

	@Override
	public void close() {
		settings.saveIfDirty();
		MinecraftClient.getInstance().setScreen(parent);
	}

	private ButtonWidget toggleButton(int x, int y, int width, String label, boolean initial, BooleanSetter setter) {
		boolean[] value = {initial};
		return ButtonWidget.builder(toggleText(label, value[0]), button -> {
			value[0] = !value[0];
			setter.set(value[0]);
			button.setMessage(toggleText(label, value[0]));
		}).dimensions(x, y, width, 20).build();
	}

	private Text toggleText(String label, boolean value) {
		return Text.literal(label + "：" + (value ? "开" : "关"));
	}

	@FunctionalInterface
	private interface BooleanSetter {
		void set(boolean value);
	}

	private static final class VolumeSlider extends SliderWidget {
		private VolumeSlider(int x, int y, int width, int height) {
			super(x, y, width, height, Text.empty(), MusicClientSettings.instance().volumePercent() / 100.0);
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Text.literal("点歌音量：" + volumePercent() + "%"));
		}

		@Override
		protected void applyValue() {
			int percent = volumePercent();
			MusicClientSettings.instance().setVolumePercent(percent);
			OnlineMusicClient.setLocalVolumePercent(percent);
		}

		@Override
		public void onRelease(double mouseX, double mouseY) {
			super.onRelease(mouseX, mouseY);
			MusicClientSettings.instance().saveIfDirty();
		}

		private int volumePercent() {
			return Math.max(0, Math.min(100, (int) Math.round(value * 100.0)));
		}
	}
}
