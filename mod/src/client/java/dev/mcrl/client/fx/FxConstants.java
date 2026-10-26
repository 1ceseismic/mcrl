package dev.mcrl.client.fx;

import java.util.Map;

public final class FxConstants {
	public static final Map<String, float[]> OCTANE_SOCKETS_UU = Map.of(
			"RocketBoost", new float[] {-48.5055f, 0, 9},
			"BoostEmitterLeft", new float[] {-48, -9, 9},
			"BoostEmitterRight", new float[] {-48, 9, 9},
			"AmbBoost_L", new float[] {-46, -14.6f, 10.5f},
			"AmbBoost_R", new float[] {-46, 14.6f, 10.5f},
			"FrontSocket", new float[] {78.0077f, 0, -1.5f},
			"AntennaSocket", new float[] {-25.8564f, -18.1061f, 23},
			"HatSocket", new float[] {19.8932f, 0, 29.5543f});
	public static final String CHASSIS_JNT = "chassis_jnt";
	public static final String[] BOOST_EMITTER_SOCKETS = {"BoostEmitterLeft", "BoostEmitterRight"};
	public static final String BOOST_PARTICLE_TRAIT = "FXTrait_BoostParticle_TA";
	public static final float[][] TEAM_FX_COLOR = {{0.098f, 0.451f, 1.0f}, {0.7647f, 0.3922f, 0.098f}};

	public static final float[] BOOST_SHEET_COLOR = {1, 0.20867f, 0.0697254f};
	public static final float[] AMB_BOOST_COLOR = {0.003162f, 0.3872f, 1};
	public static final String BOOST_SHEET_MATERIAL = "MasterBoost_Standard_MIC",
			AMB_BOOST_MATERIAL = "BoostCone_Flame_MIC";
	public static final String OCTANE_BODY_FX = "Body_Octane.FXActor";
	public static final float LENS_FLARE_WIDTH_UU = 1.3333f * 256, LENS_FLARE_HEIGHT_UU = 0.6667f * 256;
	public static final float[] LENS_FLARE_COLOR = {2, 0.238528f, 0};
	public static final float LENS_FLARE_ALPHA = 0.5f, LENS_FLARE_DIST_ALPHA = 1.008f, LENS_FLARE_DIST_UU = 4000;
	public static final float LENS_FLARE_INNER_DEG = 40, LENS_FLARE_OUTER_DEG = 170;
	public static final String LENS_FLARE_MATERIAL = "DustyFlare_Flicker_MIC";

	public static final String WHEEL_EFFECTS_MAP = "wheelEffectsMap";
	public static final String SPEED_PARAM = "Speed", SIDE_SPEED_PARAM = "SideSpeed";
	public static final double WHEEL_PARTICLE_UU = 40;
	public static final double DIRT_RATE = 20, DIRT_SIDE_SPEED_MIN = 80, DIRT_SIDE_SPEED_RANGE = 20;
	public static final double[] DIRT_VELOCITY_MIN = {-16, -16, 64}, DIRT_VELOCITY_MAX = {16, 16, 128};
	public static final double DIRT_INHERIT = 1.125;

	public static final String FLIP_RESET = "flipReset";
	public static final String CAR_IMPACT_EFFECTS_MAP = "carImpactEffectsMap";
	public static final String WORLD_IMPACT_MATERIAL = "World_Default", BALL_IMPACT_MATERIAL = "Physmat_Ball";
	public static final String IMPACT_VEL_PARAM = "ImpactVel";
	public static final double MIN_IMPACT_MOMENTUM = 50, MIN_IMPACT_DELAY = 0.15;

	public static final double BALL_FAST_UU = 2000, TRAIL_OPACITY_RATE = 3;
	public static final double RETICLE_SIZE_UU = 192, RETICLE_NEAR_UU = -50, RETICLE_FAR_UU = 3096;
	public static final float RETICLE_DEFAULT_ALTITUDE = 8192;
	public static final String RETICLE_MATERIAL = "Ball_GroundReticle_DMat";
	public static final String TRAIL_OPACITY_PARAM = "TrailOpacity", SPAWN_RATE_PARAM = "SpawnRate",
			COLOR_PARAM = "Color";

	private FxConstants() {
	}
}
