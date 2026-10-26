package dev.mcrl.client.sound;

public final class EngineAudioProfile {
	public static final double RPM_ACCEL_CLUTCHED = 5500;
	public static final double RPM_MAX_CLUTCHED = 8000;
	public static final double LIMITER_MARGIN = 250;
	public static final double LIMITER_DECEL = 5000;
	public static final double DECEL_CLUTCHED = 1000;
	public static final double AIR_MAX_THROTTLE_TIME = 1.0;
	public static final double[][] GEARS = {{1000, 250, 7000, 250}, {4000, 250, 7000, 250}, {5000, 500, 7500, 500},
		{5000, 500, 7500, 500}};
	public static final double SHIFT_TIME = 0.5;
	public static final double SHIFT_UP_BOOST = 1500;
	public static final double COAST_RATE = 1.0;
	public static final double ACCEL_LOW = 2000;
	public static final double ACCEL_HIGH = 100;
	public static final double WHEEL_ACCEL = 35;
	public static final double BOOST_LOW = 6000;
	public static final double BOOST_HIGH = 500;
	public static final double MAX_WHEEL_SPEED = 150;
	public static final double BLOWOFF_THROTTLE_TIME = 0.25;

	private EngineAudioProfile() {
	}
}
