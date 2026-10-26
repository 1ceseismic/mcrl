package dev.mcrl.client.screen.tabs;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.SoundSettings;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.client.sound.ak.Mix;

public final class SoundTab implements SettingsTab {
	@Override
	public Id id() {
		return Id.SOUND;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int left = screen.left();
		int right = screen.left() + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP;
		int top = screen.top();
		screen.add(new MasterSlider(left, top, McrlConfig.get().soundVolume));
		screen.add(CycleButton.booleanBuilder(Component.translatable("mcrl.settings.on"),
				Component.translatable("mcrl.settings.off"), McrlConfig.get().sound.dynamics())
				.withTooltip(v -> Tooltip.create(Component.translatable("mcrl.settings.sound.dynamics.tooltip")))
				.create(right, top, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.sound.dynamics"),
						(b, on) -> McrlConfig.edit(c -> c.sound = c.sound.withDynamics(on))));
		int n = 0;
		for (Mix.Group g : Mix.Group.values()) {
			screen.add(new GroupSlider(n % 2 == 0 ? left : right, top + (1 + n / 2) * McrlSettingsScreen.ROW_H, g,
					McrlConfig.get().sound.level(g)));
			n++;
		}
	}

	private abstract static class PercentSlider extends AbstractSliderButton {
		private final String key;

		PercentSlider(int x, int y, String key, float value) {
			super(x, y, McrlSettingsScreen.COL_W, 20, Component.empty(), value);
			this.key = key;
			updateMessage();
		}

		abstract void set(float v);

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(key, Math.round((float) value * 100) + "%"));
		}

		@Override
		protected void applyValue() {
			float snapped = Math.max(0f, Math.min(1f, Math.round(value * 20) / 20f));
			value = snapped;
			updateMessage();
			set(snapped);
			McrlSettingsScreen.markDirty();
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			McrlSettingsScreen.save();
		}
	}

	private static final class MasterSlider extends PercentSlider {
		MasterSlider(int x, int y, float volume) {
			super(x, y, "mcrl.settings.sound_volume", volume);
		}

		@Override
		protected void updateMessage() {
			if (Math.round((float) value * 100) == 0) {
				setMessage(Component.translatable("mcrl.settings.sound_volume.off"));
			} else {
				super.updateMessage();
			}
		}

		@Override
		void set(float v) {
			boolean turnedOn = McrlConfig.get().soundVolume <= 0f && v > 0f;
			McrlConfig.get().soundVolume = v;
			if (turnedOn) {
				RlConversion.game().retry();
			}
		}
	}

	private static final class GroupSlider extends PercentSlider {
		private final Mix.Group group;

		GroupSlider(int x, int y, Mix.Group group, float level) {
			super(x, y, "mcrl.settings.sound." + SoundSettings.key(group), level);
			this.group = group;
		}

		@Override
		void set(float v) {
			McrlConfig c = McrlConfig.get();
			c.sound = c.sound.withLevel(group, v);
		}
	}
}
