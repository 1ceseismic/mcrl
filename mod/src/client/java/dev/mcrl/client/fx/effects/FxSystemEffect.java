package dev.mcrl.client.fx.effects;

import org.joml.Quaternionf;

import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.fx.particle.SystemInstance;

public final class FxSystemEffect implements FxEffect {
	private final SystemInstance system;

	public FxSystemEffect(FxSystemDef def, long seed, FxParams params, double x, double y, double z, Quaternionf rot) {
		system = new SystemInstance(def, seed, params);
		system.setTransform(x, y, z, rot);
	}

	public SystemInstance system() {
		return system;
	}

	@Override
	public void update(FxFrame f) {
		system.update(f.emitter());
	}

	@Override
	public void collect(FxDrawList out) {
		out.add(system);
	}

	@Override
	public boolean done() {
		return system.done();
	}

	@Override
	public void stop() {
		system.deactivate();
	}
}
