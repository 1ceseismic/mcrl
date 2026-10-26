package dev.mcrl.rlassets;

// RL's Octane visuals from its packages; UU, Unreal frame.
public final class OctaneVisual {
	private OctaneVisual() {
	}

	public static final String BODY_PACKAGE = "Body_Octane_SF.upk";
	public static final String BODY_MESH = "Body_Octane_PremiumSkin_SK";
	public static final String DISC_JOINT_SUFFIX = "_Disc_jnt";
	public static final String PIVOT_JOINT_SUFFIX = "_Pivot_jnt";

	public static final double WHEEL_PRODUCT_MESH_RADIUS = 16.0;
	public static final double WHEEL_PRODUCT_MESH_WIDTH = 15.0;

	public static final double FRONT_WHEEL_MESH_RADIUS = 12.5;
	public static final double FRONT_WHEEL_WIDTH = 11.5;
	public static final double FRONT_WHEEL_MESH_OFFSET_SIDE = 0.25;
	public static final double FRONT_WHEEL_RADIUS = 12.5;
	public static final double BACK_WHEEL_MESH_RADIUS = 15.0;
	public static final double BACK_WHEEL_WIDTH = 13.0;
	public static final double BACK_WHEEL_MESH_OFFSET_SIDE = 0.25;
	public static final double BACK_WHEEL_RADIUS = 15.0;

	public static final double SUSPENSION_TRAVEL_MIN = 0.0;
	public static final double SUSPENSION_TRAVEL_MAX = 8.0;
	public static final double HANDLING_PRESET_X_OFFSET = 0.0;
	public static final double HANDLING_PRESET_Z_OFFSET = 0.0;

	public static final double[] PRESET_FRONT_REST = {51.25, 25.9, -6.0};
	public static final double[] PRESET_BACK_REST = {-33.75, 29.5, -4.3};
	public static final double PRESET_FRONT_WHEEL_RADIUS = 12.5;
	public static final double PRESET_BACK_WHEEL_RADIUS = 15.0;

	public static final double WHEEL_BLEND_RATE = 10.0;

	public static double radialScale(boolean front) {
		return (front ? FRONT_WHEEL_MESH_RADIUS : BACK_WHEEL_MESH_RADIUS) / WHEEL_PRODUCT_MESH_RADIUS;
	}

	public static double axialScale(boolean front) {
		return (front ? FRONT_WHEEL_WIDTH : BACK_WHEEL_WIDTH) / WHEEL_PRODUCT_MESH_WIDTH;
	}

	public static double meshOffsetSide(boolean front) {
		return front ? FRONT_WHEEL_MESH_OFFSET_SIDE : BACK_WHEEL_MESH_OFFSET_SIDE;
	}

	public static double[] presetRest(boolean front, boolean left) {
		double[] p = (front ? PRESET_FRONT_REST : PRESET_BACK_REST).clone();
		if (left) {
			p[1] = -p[1];
		}
		return p;
	}

	public static double presetSuspensionOffsetZ(double frontRightDiscZ, double backRightDiscZ) {
		double f = PRESET_FRONT_REST[2] - frontRightDiscZ + 2 * (FRONT_WHEEL_RADIUS - PRESET_FRONT_WHEEL_RADIUS);
		double b = PRESET_BACK_REST[2] - backRightDiscZ + 2 * (BACK_WHEEL_RADIUS - PRESET_BACK_WHEEL_RADIUS);
		return Math.max(f, b) + HANDLING_PRESET_Z_OFFSET;
	}
}
