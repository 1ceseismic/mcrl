package dev.mcrl.client.camera;

public record CameraSettings(float fov, float distance, float height, float angle, float stiffness,
		float swivelSpeed, float transitionSpeed, boolean invertSwivel, BallCamMode ballCamMode) {

	public enum BallCamMode {
		TOGGLE, HOLD
	}

	public static final float FOV_MIN = 60, FOV_MAX = 110;
	public static final float DISTANCE_MIN = 100, DISTANCE_MAX = 400;
	public static final float HEIGHT_MIN = 40, HEIGHT_MAX = 200;
	public static final float ANGLE_MIN = -15, ANGLE_MAX = 0;
	public static final float STIFFNESS_MIN = 0, STIFFNESS_MAX = 1;
	public static final float SWIVEL_MIN = 1, SWIVEL_MAX = 10;
	public static final float TRANSITION_MIN = 1, TRANSITION_MAX = 2;

	public static final CameraSettings RL_DEFAULTS =
			new CameraSettings(90, 270, 100, -3, 0.5f, 2.5f, 1.0f, false, BallCamMode.TOGGLE);

	public CameraSettings clamp() {
		CameraSettings d = RL_DEFAULTS;
		return new CameraSettings(c(fov, FOV_MIN, FOV_MAX, d.fov), c(distance, DISTANCE_MIN, DISTANCE_MAX, d.distance),
				c(height, HEIGHT_MIN, HEIGHT_MAX, d.height), c(angle, ANGLE_MIN, ANGLE_MAX, d.angle),
				c(stiffness, STIFFNESS_MIN, STIFFNESS_MAX, d.stiffness), c(swivelSpeed, SWIVEL_MIN, SWIVEL_MAX, d.swivelSpeed),
				c(transitionSpeed, TRANSITION_MIN, TRANSITION_MAX, d.transitionSpeed), invertSwivel,
				ballCamMode == null ? d.ballCamMode : ballCamMode);
	}

	private static float c(float v, float lo, float hi, float fallback) {
		return Float.isNaN(v) ? fallback : Math.max(lo, Math.min(hi, v));
	}
}
