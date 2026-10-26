package dev.mcrl.entity;

import org.jspecify.annotations.Nullable;

public final class BallHitTuning {
	public static final double E_MOB = 0.5, E_PLAYER = 0.3, E_OBJECT = 0.7;
	public static final double DAMAGE_FROM = 20;
	public static final double DAMAGE_FULL_AT = 100;
	public static final double DAMAGE_MIN = 2, DAMAGE_SPAN = 14;
	public static final int COOLDOWN_TICKS = HitTuning.COOLDOWN_TICKS;

	private BallHitTuning() {
	}

	public static double @Nullable [] exchange(double[] vBall, double[] vEnt, double[] n, double mEnt, double e) {
		double vrel = (vBall[0] - vEnt[0]) * n[0] + (vBall[1] - vEnt[1]) * n[1] + (vBall[2] - vEnt[2]) * n[2];
		if (vrel >= 0) {
			return null;
		}
		double j = -(1 + e) * vrel / (1 / EntityMass.BALL + 1 / mEnt);
		double b = j / EntityMass.BALL, t = -j / mEnt;
		return new double[] {b * n[0], b * n[1], b * n[2], t * n[0], t * n[1], t * n[2]};
	}

	public static double @Nullable [] exchangeAfter(double[] vBall, double[] dvRun, double[] vEnt, double[] n,
			double mEnt, double e) {
		double[] dv = exchange(new double[] {vBall[0] + dvRun[0], vBall[1] + dvRun[1], vBall[2] + dvRun[2]}, vEnt, n, mEnt,
				e);
		if (dv != null) {
			dvRun[0] += dv[0];
			dvRun[1] += dv[1];
			dvRun[2] += dv[2];
		}
		return dv;
	}

	public static float damage(double closing, float health) {
		if (closing < DAMAGE_FROM) {
			return 0f;
		}
		double d = DAMAGE_MIN + DAMAGE_SPAN * Math.min(closing - DAMAGE_FROM, DAMAGE_FULL_AT - DAMAGE_FROM)
				/ (DAMAGE_FULL_AT - DAMAGE_FROM);
		return (float) Math.min(d, Math.max(0, health - 1));
	}
}
