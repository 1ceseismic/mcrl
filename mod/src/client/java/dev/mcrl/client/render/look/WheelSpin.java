package dev.mcrl.client.render.look;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import org.jspecify.annotations.Nullable;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.WheelSignal;
import dev.mcrl.rlassets.WheelRig;
import dev.mcrl.sim.HitboxPreset;

public final class WheelSpin {
	private final float[] angle = new float[4];
	private final double[] rate = new double[4];
	private double simTime = Double.NaN;

	public float angle(int i) {
		return angle[i];
	}

	public void advance(double now, double maxDt, @Nullable CarSignals s, HitboxPreset p) {
		double dt = Double.isNaN(simTime) || !(now > simTime) ? 0 : Math.min(now - simTime, maxDt);
		simTime = now;
		if (s != null) {
			for (int i = 0; i < 4; i++) {
				WheelSignal w = s.wheel(i);
				if (w.contact()) {
					double radius = (WheelRig.front(i) ? p.front() : p.back()).wheelRadius();
					rate[i] = w.forwardSpeed() / (radius * UU_PER_BLOCK);
				}
			}
		}
		if (dt > 0) {
			for (int i = 0; i < 4; i++) {
				angle[i] = (float) ((angle[i] + rate[i] * dt) % (2 * Math.PI));
			}
		}
	}
}
