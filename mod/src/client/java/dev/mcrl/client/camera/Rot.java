package dev.mcrl.client.camera;

import net.minecraft.world.phys.Vec3;

public record Rot(double pitch, double yaw, double roll) {
	public static final Rot ZERO = new Rot(0, 0, 0);
	private static final double UNITS_PER_RAD = 32768 / Math.PI;

	public static Rot of(Vec3 v) {
		if (v.x == 0 && v.y == 0 && v.z == 0) {
			return ZERO;
		}
		return new Rot(Math.atan2(v.z, Math.sqrt(v.x * v.x + v.y * v.y)) * UNITS_PER_RAD,
				Math.atan2(v.y, v.x) * UNITS_PER_RAD, 0);
	}

	public Vec3 vector() {
		double p = pitch / UNITS_PER_RAD, y = yaw / UNITS_PER_RAD;
		double cp = Math.cos(p);
		return new Vec3(cp * Math.cos(y), cp * Math.sin(y), Math.sin(p));
	}

	public Rot normalized() {
		return new Rot(wrap(pitch), wrap(yaw), wrap(roll));
	}

	public Rot plus(Rot o) {
		return new Rot(pitch + o.pitch, yaw + o.yaw, roll + o.roll);
	}

	public Rot minus(Rot o) {
		return new Rot(pitch - o.pitch, yaw - o.yaw, roll - o.roll);
	}

	public Rot scale(double k) {
		return new Rot(pitch * k, yaw * k, roll * k);
	}

	public Rot withPitch(double p) {
		return new Rot(p, yaw, roll);
	}

	public Rot withYaw(double y) {
		return new Rot(pitch, y, roll);
	}

	public Rot withRoll(double r) {
		return new Rot(pitch, yaw, r);
	}

	public boolean finite() {
		return Double.isFinite(pitch) && Double.isFinite(yaw) && Double.isFinite(roll);
	}

	private static double wrap(double a) {
		double w = a - 65536 * Math.floor((a + 32768) / 65536);
		return w >= 32768 ? w - 65536 : w; // floor rounding can land on 32768
	}
}
