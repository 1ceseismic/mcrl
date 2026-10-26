package dev.mcrl.client.screen.tabs;

import java.util.function.BiFunction;
import java.util.function.Function;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.camera.CameraSettings;
import dev.mcrl.client.camera.CameraSettings.BallCamMode;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.screen.McrlSettingsScreen;

public final class CameraTab implements SettingsTab {
	@Override
	public Id id() {
		return Id.CAMERA;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int left = screen.left();
		int right = screen.left() + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP;
		int top = screen.top();
		int n = 0;
		for (Setting s : Setting.values()) {
			screen.add(new SettingSlider(n % 2 == 0 ? left : right, top + n / 2 * McrlSettingsScreen.ROW_H, s,
					McrlConfig.get().camera));
			n++;
		}
		screen.add(Button.builder(Component.translatable("mcrl.settings.reset"), b -> {
			McrlConfig.get().cameraShake = true;
			update(c -> CameraSettings.RL_DEFAULTS);
			screen.rebuild();
		}).bounds(n % 2 == 0 ? left : right, top + n / 2 * McrlSettingsScreen.ROW_H, McrlSettingsScreen.COL_W, 20)
				.build());
		int rowY = top + (n / 2 + 1) * McrlSettingsScreen.ROW_H;
		CameraSettings cur = McrlConfig.get().camera;
		screen.add(CycleButton.onOffBuilder(cur.invertSwivel())
				.create(left, rowY, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.invert_swivel"),
						(b, v) -> update(c -> new CameraSettings(c.fov(), c.distance(), c.height(),
								c.angle(), c.stiffness(), c.swivelSpeed(), c.transitionSpeed(), v, c.ballCamMode()))));
		screen.add(CycleButton.<BallCamMode>builder(
				m -> Component.translatable("mcrl.settings.ball_cam_mode." + m.name().toLowerCase()), cur.ballCamMode())
				.withValues(BallCamMode.values())
				.create(right, rowY, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.ball_cam_mode"),
						(b, v) -> update(c -> new CameraSettings(c.fov(), c.distance(), c.height(),
								c.angle(), c.stiffness(), c.swivelSpeed(), c.transitionSpeed(), c.invertSwivel(), v))));
		rowY += McrlSettingsScreen.ROW_H;
		screen.add(CycleButton.onOffBuilder(McrlConfig.get().cameraMouseLook)
				.create(left, rowY, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.mouse_look"),
						(b, v) -> McrlConfig.edit(c -> c.cameraMouseLook = v)));
		screen.add(CycleButton.onOffBuilder(McrlConfig.get().cameraShake)
				.create(right, rowY, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.camera_shake"),
						(b, v) -> McrlConfig.edit(c -> c.cameraShake = v)));
	}

	private static void update(Function<CameraSettings, CameraSettings> f) {
		change(f);
		McrlSettingsScreen.save();
	}

	private static void change(Function<CameraSettings, CameraSettings> f) {
		McrlConfig cfg = McrlConfig.get();
		cfg.camera = f.apply(cfg.camera).clamp();
		McrlSettingsScreen.markDirty();
	}

	private enum Setting {
		FOV("fov", CameraSettings.FOV_MIN, CameraSettings.FOV_MAX, 0, 1f, CameraSettings::fov,
				(c, v) -> new CameraSettings(v, c.distance(), c.height(), c.angle(), c.stiffness(), c.swivelSpeed(),
						c.transitionSpeed(), c.invertSwivel(), c.ballCamMode())),
		DISTANCE("distance", CameraSettings.DISTANCE_MIN, CameraSettings.DISTANCE_MAX, 0, 1f, CameraSettings::distance,
				(c, v) -> new CameraSettings(c.fov(), v, c.height(), c.angle(), c.stiffness(), c.swivelSpeed(),
						c.transitionSpeed(), c.invertSwivel(), c.ballCamMode())),
		HEIGHT("height", CameraSettings.HEIGHT_MIN, CameraSettings.HEIGHT_MAX, 0, 1f, CameraSettings::height,
				(c, v) -> new CameraSettings(c.fov(), c.distance(), v, c.angle(), c.stiffness(), c.swivelSpeed(),
						c.transitionSpeed(), c.invertSwivel(), c.ballCamMode())),
		ANGLE("angle", CameraSettings.ANGLE_MIN, CameraSettings.ANGLE_MAX, 0, 1f, CameraSettings::angle,
				(c, v) -> new CameraSettings(c.fov(), c.distance(), c.height(), v, c.stiffness(), c.swivelSpeed(),
						c.transitionSpeed(), c.invertSwivel(), c.ballCamMode())),
		STIFFNESS("stiffness", CameraSettings.STIFFNESS_MIN, CameraSettings.STIFFNESS_MAX, 2, 0.05f,
				CameraSettings::stiffness,
				(c, v) -> new CameraSettings(c.fov(), c.distance(), c.height(), c.angle(), v, c.swivelSpeed(),
						c.transitionSpeed(), c.invertSwivel(), c.ballCamMode())),
		SWIVEL("swivel_speed", CameraSettings.SWIVEL_MIN, CameraSettings.SWIVEL_MAX, 1, 0.1f,
				CameraSettings::swivelSpeed,
				(c, v) -> new CameraSettings(c.fov(), c.distance(), c.height(), c.angle(), c.stiffness(), v,
						c.transitionSpeed(), c.invertSwivel(), c.ballCamMode())),
		TRANSITION("transition_speed", CameraSettings.TRANSITION_MIN, CameraSettings.TRANSITION_MAX, 2, 0.05f,
				CameraSettings::transitionSpeed,
				(c, v) -> new CameraSettings(c.fov(), c.distance(), c.height(), c.angle(), c.stiffness(),
						c.swivelSpeed(), v, c.invertSwivel(), c.ballCamMode()));

		final String key;
		final float min, max;
		final int decimals;
		final float step;
		final Function<CameraSettings, Float> get;
		final BiFunction<CameraSettings, Float, CameraSettings> set;

		Setting(String key, float min, float max, int decimals, float step, Function<CameraSettings, Float> get,
				BiFunction<CameraSettings, Float, CameraSettings> set) {
			this.key = key;
			this.min = min;
			this.max = max;
			this.decimals = decimals;
			this.step = step;
			this.get = get;
			this.set = set;
		}
	}

	private static final class SettingSlider extends AbstractSliderButton {
		private final Setting setting;

		SettingSlider(int x, int y, Setting setting, CameraSettings camera) {
			super(x, y, McrlSettingsScreen.COL_W, 20, Component.empty(),
					(setting.get.apply(camera) - setting.min) / (setting.max - setting.min));
			this.setting = setting;
			updateMessage();
		}

		private float current() {
			return (float) (setting.min + value * (setting.max - setting.min));
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("mcrl.settings." + setting.key,
					String.format("%." + setting.decimals + "f", current())));
		}

		@Override
		protected void applyValue() {
			float snapped = Math.max(setting.min,
					Math.min(setting.max, Math.round(current() / setting.step) * setting.step));
			value = (snapped - setting.min) / (setting.max - setting.min);
			updateMessage();
			change(c -> setting.set.apply(c, snapped));
		}

		@Override
		public void onRelease(MouseButtonEvent event) {
			super.onRelease(event);
			McrlSettingsScreen.save();
		}
	}
}
