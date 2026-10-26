package dev.mcrl.client.render.look;

import dev.mcrl.client.config.McrlConfig.Team;

public final class RlLook {
	public static final float[] TEAM_BLUE_SRGB = {0.0495f, 0.53543f, 0.99f};
	public static final float[] TEAM_ORANGE_SRGB = {0.99f, 0.4884f, 0.0495f};

	public static final float FINISH_TYPE = 0f;
	public static final float[] FINISH_CONTROL_A = {0.25f, 0.25f, 0f, 0f};
	public static final float[] FINISH_CONTROL_B = {2.0f, 0.25f, 0f, 0f};
	public static final String FINISH_DETAIL_NORMAL = "Blank_N";

	public static final float[] BOOST_GLOW_COLOR = {3f, 0.119744f, 0f};
	public static final double BOOST_GLOW_RATE = 2.0;
	public static final float[] BALL_TINT = {1f, 0.968664f, 0.906081f};
	public static final float MAX_DROOP_UU = 8f;

	// RocketSim limits.
	public static final double MAX_LINEAR_SPEED_UU = 2300.0;
	public static final double THROTTLE_DEADZONE = 0.001;
	public static final double STOPPING_FORWARD_VEL_UU = 25.0;
	public static final float MAX_STEER = 0.53356f;

	public static final String LIGHT_FALLOFF = "LightFalloffArray";
	public static final String ENV_PACK = "ENVPack";
	public static final String BRUSHED_METAL = "BrushedMetal_Normal";
	public static final String SWIRLS = "Swirls_D";
	public static final String MATTE_N = "Matte_N";

	public static final String BODY = "MIC_Body_Octane";
	public static final String CHASSIS = "OctaneChassis_MIC";
	public static final String WHEEL = "Wheel_OEM_MIC";
	public static final String BALL = "MAT_Ball_V3";

	private RlLook() {
	}

	public static float[] teamColor(Team t) {
		float[] srgb = t == Team.BLUE ? TEAM_BLUE_SRGB : TEAM_ORANGE_SRGB;
		float[] c = new float[3];
		for (int i = 0; i < 3; i++) {
			c[i] = (float) Math.pow(srgb[i], 2.2);
		}
		return c;
	}
}
