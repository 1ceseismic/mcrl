package dev.mcrl.entity;

public final class HitTuning {
	private HitTuning() {
	}

	public static final double DIRECTION_SPEED_MIN = 2.0;
	public static final int COOLDOWN_TICKS = 10;

	public static final double HORIZONTAL_GAIN = 1.2;
	public static final double HORIZONTAL_CAP = 60.0;
	public static final double POP_BASE = 6.0;
	public static final double POP_GAIN = 0.2;
	public static final double POP_CAP = 20.0;

	public static final double SEPARATION_BELOW = DIRECTION_SPEED_MIN;
	public static final double SEPARATION_SPEED = 1.0;

	public static final double DAMAGE_NONE_BELOW = 10.0;
	public static final double DAMAGE_BASE = 2.0;
	public static final double DAMAGE_SPAN = 18.0;
	// RL's demolition speed, 2200 UU/s.
	public static final double LETHAL_AT = 44.0;

	public static boolean separationOnly(double closing) {
		return closing < SEPARATION_BELOW;
	}

	public static double horizontalSpeed(double closing) {
		return separationOnly(closing) ? SEPARATION_SPEED : Math.min(HORIZONTAL_GAIN * closing, HORIZONTAL_CAP);
	}

	public static double popSpeed(double closing) {
		return separationOnly(closing) ? 0.0 : Math.min(POP_BASE + POP_GAIN * closing, POP_CAP);
	}

	public static boolean coolingDown(long lastHit, long now) {
		return now - lastHit < COOLDOWN_TICKS;
	}

	public static float damage(double closing) {
		if (closing < DAMAGE_NONE_BELOW) {
			return 0f;
		}
		if (closing >= LETHAL_AT) {
			return Float.MAX_VALUE;
		}
		return (float) (DAMAGE_BASE + DAMAGE_SPAN * (closing - DAMAGE_NONE_BELOW) / (LETHAL_AT - DAMAGE_NONE_BELOW));
	}

	// Hitboxes ride above the ground, items lie below them.
	public static final double LOOSE_REACH = 0.4;
	public static final double OBJECT_CARRY = 0.9;
	public static final double OBJECT_POP = 0.2;
	public static final double OBJECT_RESTITUTION = 0.7;
	public static final double OBJECT_PUSH_CAP = 20;
	public static final double OBJECT_BREAK_AT = 30;
	// VehicleEntity breaks above 40, at 10 per HP.
	public static final float OBJECT_BREAK_DAMAGE = 5f;
}
