package dev.mcrl.client.camera;

import net.minecraft.world.phys.Vec3;

public final class UeMath {
	private UeMath() {
	}

	public static Vec3 vsmooth(Vec3 c, Vec3 t, double rate, double dt) {
		return lerp(c, t, alpha(rate, dt));
	}

	public static double smooth(double c, double t, double rate, double dt) {
		return c + (t - c) * alpha(rate, dt);
	}

	public static Rot rsmooth(Rot c, Rot t, double rate, double dt) {
		return rlerp(c, t, alpha(rate, dt));
	}

	public static Rot rlerp(Rot a, Rot b, double alpha) {
		return a.plus(b.minus(a).normalized().scale(alpha)).normalized();
	}

	public static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	public static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	public static Vec3 clampWithin(Vec3 p, Vec3 centre, double max) {
		Vec3 d = p.subtract(centre);
		double len = d.length();
		return len <= max ? p : centre.add(d.scale(max / len));
	}

	public static Vec3 normal(Vec3 v) {
		double sq = v.lengthSqr();
		return sq < 1e-8 ? Vec3.ZERO : v.scale(1 / Math.sqrt(sq));
	}

	private static double alpha(double rate, double dt) {
		return 1 - Math.exp(-rate * dt);
	}
}
