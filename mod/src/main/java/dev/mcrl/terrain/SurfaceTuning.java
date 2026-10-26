package dev.mcrl.terrain;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.MemorySegment;

import org.jspecify.annotations.Nullable;

public final class SurfaceTuning {
	// Vanilla's default block friction and LivingEntity's ground retention.
	public static final float STONE_FRICTION = 0.6f;
	public static final double GROUND_RETENTION = 0.91;
	public static final double LAT_EXP = 0.8;
	public static final double LONG_EXP = 0.6;
	public static final double MIN_GRIP = 0.05;
	public static final double SLOW_DRAG = 6.0;
	public static final double BALL_ROUGH_DRAG = 1.5;
	public static final double BALL_BOUNCE = 0.95;
	public static final double PAD = 0.9;
	public static final double BASE_BALL_RESTITUTION = 0.6;

	private static final double KEY_SCALE = 1e4;

	private SurfaceTuning() {
	}

	public record Material(float wheelLat, float wheelLong, float wheelDrag, float chassisFriction,
			float chassisRestitution, float ballFriction, float ballRestitution, float ballDrag, float padRestitution) {
		public void writeTo(MemorySegment s) {
			float[] v = {wheelLat, wheelLong, wheelDrag, chassisFriction, chassisRestitution, ballFriction,
					ballRestitution, ballDrag, padRestitution};
			for (int i = 0; i < v.length; i++) {
				s.set(JAVA_FLOAT, 4L * i, v[i]);
			}
			s.set(JAVA_INT, 36, 0);
		}
	}

	public static double grip(float friction) {
		double g = (1 - GROUND_RETENTION * friction) / (1 - GROUND_RETENTION * STONE_FRICTION);
		return Math.max(MIN_GRIP, Math.min(1, g));
	}

	static float round(float v) {
		return (float) (Math.round(v * KEY_SCALE) / KEY_SCALE);
	}

	public static @Nullable Material material(float friction, float speedFactor, float bounce) {
		float f = round(friction), s = round(speedFactor), b = round(bounce);
		double g = grip(f);
		if (g == 1 && s >= 1 && !(b > 0)) {
			return null;
		}
		float drag = s < 1 ? (float) (SLOW_DRAG * (1 - s)) : 0f;
		float ballDrag = s < 1 ? (float) BALL_ROUGH_DRAG : 0f;
		float ballRestitution = -1f, chassisRestitution = -1f, pad = 0f;
		if (b > 0) {
			ballRestitution = (float) Math.min(1, Math.max(BASE_BALL_RESTITUTION, BALL_BOUNCE * b));
			chassisRestitution = pad = (float) Math.min(1, PAD * b);
		}
		return new Material((float) Math.pow(g, LAT_EXP), (float) Math.pow(g, LONG_EXP), drag, (float) g,
				chassisRestitution, (float) g, ballRestitution, ballDrag, pad);
	}
}
