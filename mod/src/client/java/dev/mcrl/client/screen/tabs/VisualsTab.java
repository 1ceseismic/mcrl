package dev.mcrl.client.screen.tabs;

import java.util.Locale;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.TextureQuality;
import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.client.rl.RlTextures;
import dev.mcrl.client.screen.McrlSettingsScreen;

public final class VisualsTab implements SettingsTab {
	@Override
	public Id id() {
		return Id.VISUALS;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int left = screen.left();
		int right = screen.left() + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP;
		int top = screen.top();
		McrlConfig cfg = McrlConfig.get();
		screen.add(CycleButton.<Visuals>builder(
				v -> Component.translatable("mcrl.settings.visuals." + v.name().toLowerCase(Locale.ROOT)), cfg.visuals)
				.withValues(Visuals.values())
				.create(left, top, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.visuals"),
						(b, v) -> RlConversion.setVisuals(v)));
		screen.add(CycleButton.<TextureQuality>builder(
				q -> Component.translatable("mcrl.settings.texture_quality." + q.name().toLowerCase(Locale.ROOT)),
				cfg.textureQuality)
				.withValues(TextureQuality.values())
				.create(right, top, McrlSettingsScreen.COL_W, 20,
						Component.translatable("mcrl.settings.texture_quality"), (b, q) -> RlTextures.setQuality(q)));
		screen.add(CycleButton.onOffBuilder(cfg.carShadow)
				.withTooltip(v -> Tooltip.create(Component.translatable("mcrl.settings.car_shadow.tooltip")))
				.create(left, top + McrlSettingsScreen.ROW_H, McrlSettingsScreen.COL_W, 20,
						Component.translatable("mcrl.settings.car_shadow"),
						(b, v) -> McrlConfig.edit(c -> c.carShadow = v)));
	}
}
