package dev.mcrl.client.sound;

import dev.mcrl.client.fx.RlSurface;
import dev.mcrl.rlassets.wwise.Fnv;

final class GameParams {
	static final long SPEED = Fnv.hash("Speed");
	static final long RPM = Fnv.hash("RPM");
	static final long THROTTLE_INPUT = Fnv.hash("Throttle_Input");
	static final long IS_LOCAL = Fnv.hash("IsLocal");
	static final long WHEEL_SPEED = Fnv.hash("WheelSpeed");
	static final long WHEEL_FORWARD_SPEED = Fnv.hash("WheelForwardSpeed");
	static final long WHEEL_SIDE_SPEED = Fnv.hash("WheelSideSpeed");
	static final long WHEELS_ON_GROUND = Fnv.hash("WheelsOnGround");
	static final long CAR_PITCH = Fnv.hash("Car_Pitch");
	static final long CAR_YAW = Fnv.hash("Car_Yaw");
	static final long CAR_ROLL = Fnv.hash("Car_Roll");
	static final long ALTITUDE = Fnv.hash("Altitude");
	static final long DOPPLER_ANGLE = Fnv.hash("DopplerAngle");
	static final long SPAM_CONTROL_BOOST = Fnv.hash("SpamControl_Boost");
	static final long IMPACT_INTENSITY = Fnv.hash("ImpactIntensity");
	static final long CAR_SLIDE_ANGLE = Fnv.hash("Car_SlideAngle");
	static final long DISTANCE_TO_PLAYER = Fnv.hash("DistanceToPlayer");
	static final long MATERIAL_TYPE = Fnv.hash("MaterialType");
	static final long GAME_MODE = Fnv.hash("GameMode"), TRAINING = Fnv.hash("Training");
	static final long PLAYER_COUNT = Fnv.hash("PlayerCount"), ONES = Fnv.hash("Ones");

	private static final long[] SURFACES = new long[RlSurface.values().length];

	static {
		for (RlSurface s : RlSurface.values()) {
			SURFACES[s.ordinal()] = Fnv.hash(s.soundSwitch());
		}
	}

	private GameParams() {
	}

	static long surface(RlSurface s) {
		return SURFACES[s.ordinal()];
	}
}
