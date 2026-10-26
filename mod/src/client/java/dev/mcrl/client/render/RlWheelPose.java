package dev.mcrl.client.render;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import dev.mcrl.rlassets.WheelRig;

final class RlWheelPose {
	float steer;
	final float[] offset = new float[4];
	private double simTime = Double.NaN;

	void advance(WheelRig rig, double now, double maxDt, float steerTarget, float[] wheelLocal, int contact, float upY) {
		double dt = Double.isNaN(simTime) ? 0 : now - simTime;
		simTime = now;
		double a = Math.min(rig.blendRate() * (dt > 0 ? Math.min(dt, maxDt) : 0), 1);
		steer += (float) (a * (steerTarget - steer));
		for (int i = 0; i < 4; i++) {
			boolean touching = (contact >> i & 1) != 0;
			float target = (float) suspensionTarget(rig, i, touching, wheelLocal[3 * i + 1], upY);
			if (touching && target > offset[i]) {
				offset[i] = target;
			} else {
				offset[i] += (float) (a * (target - offset[i]));
			}
		}
	}

	private static double suspensionTarget(WheelRig rig, int i, boolean contact, float wheelLocalY, float upY) {
		double drop = contact ? rig.presetRestZ()[i] + rig.tz() - UU_PER_BLOCK * wheelLocalY
				: rig.travelMin() + (rig.travelMax() - rig.travelMin()) * (upY + 1) / 2;
		return -Math.clamp(drop, rig.travelMin(), rig.travelMax());
	}
}
