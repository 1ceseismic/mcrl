package dev.mcrl.client.camera;

public final class RlCamConstants {
	private RlCamConstants() {
	}

	// RL camera values: UU, rotator units (65536 = 360°)
	public static final double UNITS_PER_DEG = 182.0444;
	public static final double TO_GROUND_RATE = 2.0;
	public static final double TO_AIR_RATE = 4.0;
	public static final double FOCUS_OFFSET_RATE = 2.03;
	public static final double FOCUS_RATE = 6.32;
	public static final double FOCUS_MAX_DISTANCE = 100;
	public static final double GROUND_NORMAL_RATE = 10.46;
	public static final double PITCH_BLEND_RANGE = 48000;
	public static final double STIFFNESS_RATE_SCALE = 3.427;
	public static final double GROUND_ROT_RATE = 13.39;
	public static final double WALL_ROT_RATE = 2.03;
	public static final double AIR_VELOCITY_YAW_SPEED = 500;
	public static final double AIR_RATE_SLOW = 35;
	public static final double AIR_RATE_FAST = 10;
	public static final double MAX_LINEAR_SPEED = 2300;
	public static final double DISTANCE_SPEED_SCALE = 0.05;
	public static final double DISTANCE_MIN_ADD = -50;
	public static final double DISTANCE_RATE = 4.14;
	public static final double ROLL_SCALE = -0.1;

	public static final double MAX_SPEED_FOV = 5;
	public static final double FOV_RATE = 25;
	public static final double SUPERSONIC_FOV = 10;
	public static final double SUPERSONIC_FOV_RATE = 40;

	public static final double BALL_PITCH_LIFT = 0.005;
	public static final double BALL_PITCH_LOW = 4000;
	public static final double BALL_PITCH_HIGH = 8000;
	public static final double BALL_PITCH_STRENGTH = 0.8;
	public static final double BALL_ROT_RATE = 8.59;

	public static final double BLEND_TIME_MAX = 0.5;
	public static final double BLEND_EXPONENT = 2;

	public static final double SWIVEL_SPEED_REF = 2500;
	public static final double SWIVEL_PITCH_MIN = -8900;
	public static final double SWIVEL_PITCH_MAX = 5500;
	public static final double SWIVEL_YAW_SLOW = 22500;
	public static final double SWIVEL_YAW_FAST = 18000;
	public static final double SWIVEL_DIE_RATE = 2;

	public static final double ASPECT_16_9 = 0.5625;

	// mcrl's own values, not RL's
	public static final double MOUSE_DEG_PER_UNIT = 0.15;
	public static final double MOUSE_HOLD = 0.5;
	public static final double TELEPORT_UU = 500;
	public static final double MAX_DT = 0.25;
	public static final double PIVOT_BACKOFF = 0.2;
	// over the 0.1 vanilla getMaxZoom offsets its rays by
	public static final double FACE_CLEARANCE = 0.15;
	public static final double ZOOM_OUT_RATE = FOCUS_RATE;
}
