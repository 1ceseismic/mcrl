package dev.mcrl.client.screen.tabs;

import java.util.function.BiFunction;
import java.util.function.DoubleFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.input.InputMapper.MapperSettings;
import dev.mcrl.client.screen.McrlSettingsScreen;

public final class ControlsTab implements SettingsTab {
	private static final float DEADZONE_MAX = 0.95f, SENS_MIN = 0.1f, SENS_MAX = 5f;

	@Override
	public Id id() {
		return Id.CONTROLS;
	}

	private static float deadzone(double slider) {
		return (float) (clamp01(slider) * DEADZONE_MAX);
	}

	private static float sensitivity(double slider) {
		return (float) (SENS_MIN + clamp01(slider) * (SENS_MAX - SENS_MIN));
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int x = screen.left();
		int y = screen.top();
		MapperSettings m = McrlConfig.get().controller;
		screen.add(new ControlSlider(x, y, "mcrl.settings.stick_deadzone", clamp01(m.stickDeadzone() / DEADZONE_MAX),
				ControlsTab::deadzone, (s, v) -> new MapperSettings(v, s.triggerDeadzone(), s.sensitivity())));
		y += McrlSettingsScreen.ROW_H;
		screen.add(new ControlSlider(x, y, "mcrl.settings.trigger_deadzone",
				clamp01(m.triggerDeadzone() / DEADZONE_MAX), ControlsTab::deadzone,
				(s, v) -> new MapperSettings(s.stickDeadzone(), v, s.sensitivity())));
		y += McrlSettingsScreen.ROW_H;
		screen.add(new ControlSlider(x, y, "mcrl.settings.sensitivity",
				clamp01((m.sensitivity() - SENS_MIN) / (SENS_MAX - SENS_MIN)), ControlsTab::sensitivity,
				(s, v) -> new MapperSettings(s.stickDeadzone(), s.triggerDeadzone(), v)));
		y += McrlSettingsScreen.ROW_H;
		screen.add(Button.builder(Component.translatable("mcrl.settings.key_bindings"),
				b -> screen.open(parent -> new KeyBindsScreen(parent, Minecraft.getInstance().options)))
				.bounds(x, y, McrlSettingsScreen.COL_W, 20).build());
		screen.add(CycleButton.onOffBuilder(McrlConfig.get().rumble)
				.create(x + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP, screen.top(), McrlSettingsScreen.COL_W,
						20, Component.translatable("mcrl.settings.rumble"), (b, v) -> McrlConfig.edit(c -> c.rumble = v)));
	}

	private static final class ControlSlider extends AbstractSliderButton {
		private final String key;
		private final DoubleFunction<Float> mapping;
		private final BiFunction<MapperSettings, Float, MapperSettings> setter;

		ControlSlider(int x, int y, String key, double initial, DoubleFunction<Float> mapping,
				BiFunction<MapperSettings, Float, MapperSettings> setter) {
			super(x, y, McrlSettingsScreen.COL_W, 20, Component.empty(), initial);
			this.key = key;
			this.mapping = mapping;
			this.setter = setter;
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable(key, String.format("%.2f", mapping.apply(value))));
		}

		@Override
		protected void applyValue() {
			McrlConfig cfg = McrlConfig.get();
			cfg.controller = setter.apply(cfg.controller, mapping.apply(value));
			McrlSettingsScreen.markDirty();
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			McrlSettingsScreen.save();
		}
	}
}
