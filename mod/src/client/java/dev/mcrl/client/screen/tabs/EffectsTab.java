package dev.mcrl.client.screen.tabs;

import java.util.EnumSet;
import java.util.Locale;
import java.util.function.Predicate;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.EffectsSettings;
import dev.mcrl.client.fx.EffectsSettings.Quality;
import dev.mcrl.client.screen.McrlSettingsScreen;

public final class EffectsTab implements SettingsTab {
	@Override
	public Id id() {
		return Id.EFFECTS;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		McrlConfig cfg = McrlConfig.get();
		screen.add(toggle(screen, cfg, Switch.ENABLED, 0));
		screen.add(CycleButton.<Quality>builder(
				q -> Component.translatable("mcrl.settings.effects.quality." + q.name().toLowerCase(Locale.ROOT)),
				cfg.effects.quality())
				.withValues(Quality.values())
				.create(x(screen, 1), y(screen, 1), McrlSettingsScreen.COL_W, 20,
						Component.translatable("mcrl.settings.effects.quality"), (b, q) -> {
							EffectsSettings e = cfg.effects;
							cfg.effects = new EffectsSettings(e.enabled(), e.boost(), e.supersonic(), e.wheels(),
									e.movement(), e.impacts(), e.ball(), e.spawn(), e.ownerOnly(), q);
							cfg.save();
						}));
		int n = 2;
		for (Switch s : EnumSet.range(Switch.BOOST, Switch.OWNER_ONLY)) {
			screen.add(toggle(screen, cfg, s, n++));
		}
	}

	private static int x(McrlSettingsScreen screen, int n) {
		return screen.left() + n % 2 * (McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP);
	}

	private static int y(McrlSettingsScreen screen, int n) {
		return screen.top() + n / 2 * McrlSettingsScreen.ROW_H;
	}

	private static CycleButton<Boolean> toggle(McrlSettingsScreen screen, McrlConfig cfg, Switch s, int n) {
		CycleButton.Builder<Boolean> b = CycleButton.onOffBuilder(s.get.test(cfg.effects));
		if (s == Switch.OWNER_ONLY) {
			b = b.withTooltip(
					v -> Tooltip.create(Component.translatable("mcrl.settings.effects.owner_only.tooltip")));
		}
		return b.create(x(screen, n), y(screen, n), McrlSettingsScreen.COL_W, 20,
				Component.translatable("mcrl.settings.effects." + s.key), (button, v) -> {
					cfg.effects = with(cfg.effects, s, v);
					cfg.save();
				});
	}

	private static EffectsSettings with(EffectsSettings e, Switch s, boolean v) {
		return new EffectsSettings(s == Switch.ENABLED ? v : e.enabled(), s == Switch.BOOST ? v : e.boost(),
				s == Switch.SUPERSONIC ? v : e.supersonic(), s == Switch.WHEELS ? v : e.wheels(),
				s == Switch.MOVEMENT ? v : e.movement(), s == Switch.IMPACTS ? v : e.impacts(),
				s == Switch.BALL ? v : e.ball(), s == Switch.SPAWN ? v : e.spawn(),
				s == Switch.OWNER_ONLY ? v : e.ownerOnly(), e.quality());
	}

	private enum Switch {
		ENABLED("enabled", EffectsSettings::enabled),
		BOOST("boost", EffectsSettings::boost),
		SUPERSONIC("supersonic", EffectsSettings::supersonic),
		WHEELS("wheels", EffectsSettings::wheels),
		MOVEMENT("movement", EffectsSettings::movement),
		IMPACTS("impacts", EffectsSettings::impacts),
		BALL("ball", EffectsSettings::ball),
		SPAWN("spawn", EffectsSettings::spawn),
		OWNER_ONLY("owner_only", EffectsSettings::ownerOnly);

		final String key;
		final Predicate<EffectsSettings> get;

		Switch(String key, Predicate<EffectsSettings> get) {
			this.key = key;
			this.get = get;
		}
	}
}
