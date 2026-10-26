package dev.mcrl.client.screen.tabs;

import java.util.function.BiFunction;
import java.util.function.Function;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.hud.HudSettings;
import dev.mcrl.client.screen.McrlSettingsScreen;

public final class HudTab implements SettingsTab {
	private enum Toggle {
		BOOST_METER("hud_boost_meter", HudSettings::boostMeter,
				(h, v) -> new HudSettings(v, h.ballCamIndicator(), h.speed(), h.hideVanilla())),
		BALL_CAM_INDICATOR("hud_ball_cam_indicator", HudSettings::ballCamIndicator,
				(h, v) -> new HudSettings(h.boostMeter(), v, h.speed(), h.hideVanilla())),
		SPEED("hud_speed", HudSettings::speed,
				(h, v) -> new HudSettings(h.boostMeter(), h.ballCamIndicator(), v, h.hideVanilla())),
		HIDE_VANILLA("hud_hide_vanilla", HudSettings::hideVanilla,
				(h, v) -> new HudSettings(h.boostMeter(), h.ballCamIndicator(), h.speed(), v));

		final String key;
		final Function<HudSettings, Boolean> get;
		final BiFunction<HudSettings, Boolean, HudSettings> set;

		Toggle(String key, Function<HudSettings, Boolean> get, BiFunction<HudSettings, Boolean, HudSettings> set) {
			this.key = key;
			this.get = get;
			this.set = set;
		}
	}

	@Override
	public Id id() {
		return Id.HUD;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int left = screen.left();
		int right = screen.left() + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP;
		int n = 0;
		for (Toggle t : Toggle.values()) {
			screen.add(CycleButton.onOffBuilder(t.get.apply(McrlConfig.get().hud))
					.create(n % 2 == 0 ? left : right, screen.top() + n / 2 * McrlSettingsScreen.ROW_H,
							McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings." + t.key),
							(b, v) -> McrlConfig.edit(c -> c.hud = t.set.apply(c.hud, v))));
			n++;
		}
	}
}
