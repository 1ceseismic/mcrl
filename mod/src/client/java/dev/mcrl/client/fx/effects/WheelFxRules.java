package dev.mcrl.client.fx.effects;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.math.Pose;

public final class WheelFxRules {
	private static final double SLIDE_LAT = 500;
	private static final double REVERSE_FWD = -50;
	private static final int FR = 1, FL = 1 << 1, BR = 1 << 2, BL = 1 << 3;

	private WheelFxRules() {
	}

	private static int emitting(double fwd, double lat) {
		if (Math.abs(lat) > SLIDE_LAT && Math.abs(lat) > fwd) {
			return lat > 0 ? FL | BL : FR | BR;
		}
		return fwd > REVERSE_FWD ? BR | BL : FR | FL;
	}

	public static int emitting(CarSignals s) {
		Pose p = s.pose();
		Vec3 v = s.velocity();
		Quaternionf q = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw());
		Vector3f axis = q.transform(new Vector3f(1, 0, 0));
		double fwd = v.x * axis.x + v.y * axis.y + v.z * axis.z;
		q.transform(axis.set(0, 0, 1));
		double lat = v.x * axis.x + v.y * axis.y + v.z * axis.z;
		return emitting(fwd, lat);
	}

	public static boolean rear(int wheel) {
		return wheel == 2 || wheel == 3;
	}
}
