package dev.mcrl.client.fx.effects;

import dev.mcrl.client.fx.EffectsSettings;
import dev.mcrl.client.fx.particle.EmitterFrame;

public record FxFrame(EmitterFrame emitter, EffectsSettings settings) {
	public double dt() {
		return emitter.dt();
	}
}
