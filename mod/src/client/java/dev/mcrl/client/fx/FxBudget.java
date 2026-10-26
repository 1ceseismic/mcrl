package dev.mcrl.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ParticleStatus;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.EffectsSettings.Quality;

public final class FxBudget {
	private FxBudget() {
	}

	public static double spawnFactor(ParticleStatus mc, Quality q) {
		double level = switch (mc) {
			case ALL -> 1.0;
			case DECREASED -> 2 / 3.0;
			case MINIMAL -> 0.0;
		};
		return level * (q == Quality.REDUCED ? 0.5 : 1.0);
	}

	public static double spawnFactor() {
		return spawnFactor(Minecraft.getInstance().options.particles().get(), McrlConfig.get().effects.quality());
	}

	public static int burst(int count, double factor) {
		return (int) Math.round(count * factor);
	}
}
