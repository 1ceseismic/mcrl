package dev.mcrl.client.render.look;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

import com.mojang.blaze3d.buffers.Std140Builder;

public final class RlUbos {
	public static final int SCENE_SIZE = 128;
	public static final int DRAW_SIZE = 288;
	static final int BODY_SIZE = 224, CHASSIS_SIZE = 64, WHEEL_SIZE = 80, BALL_SIZE = 64;

	// Centre of a LightFalloffArray curve's first row.
	private static final float ROW = 0.003908f;
	private static final float[] BODY_CUSTOM_COLOR = {0, 0.53576f, 1, 1};
	private static final float[] PAINT_COLOR = {1, 0.5988f, 0, 1};
	private static final float[] TRIM_COLOR = {0.12f, 0.12f, 0.12f, 1};
	private static final float[] TERT_CONTROL_A = {0.75f, 0.15f, 3, 0.03125f};
	private static final float[] TERT_CONTROL_B = {6, 0.5f, 32, 0.15f};
	private static final float[] TERT_COLOR = {0.06575f, 0.06575f, 0.06575f, 1};
	private static final float TERT_TYPE = 2, TERT_TILING = 64;
	private static final float[] HEADLIGHT_COLOR = {3, 2.90175f, 2.74727f, 1};
	private static final float HEADLIGHT_BRIGHTNESS = 3;
	private static final float[] TAIL_LIGHT_COLOR = {3, 0.09069f, 0, 1};
	private static final float TAIL_LIGHT_BRIGHTNESS = 5;
	private static final float BOOST_GLOW_BRIGHTNESS = 5;
	private static final float[] WHEEL_CUSTOM_COLOR = {0.14198f, 0.14198f, 0.14198f, 1};
	private static final float[] RIM_COLOR = {0.75f, 0.75f, 0.75f, 1};
	private static final float SPEC_POWER = 40, SPEC_INTENSITY = 10, REFLECTION_BRIGHTNESS = 0.36f, NORMAL_POWER = 1;
	private static final float[] GOAL_PLANE_LOCATION = {0, 0, -1024, 0};
	private static final float[] GOAL_PLANE_NORMAL = {0, 0, 1, 0};

	private RlUbos() {
	}

	public static void scene(Std140Builder b, SceneValues v) {
		vec4(b, v.sunDir(), 0);
		vec4(b, v.sunColor(), 0);
		vec4(b, v.upper(), 0);
		vec4(b, v.lower(), v.lowerIsGround() ? 1 : 0);
		vec4(b, v.ambient(), v.skyFactor());
		vec4(b, v.cubeSky(), 0);
		b.putVec4(v.timeFrac(), v.timeFrac8(), 0, 0);
		b.putVec4(ArenaLight.EXPOSURE, 0, 0, 0);
	}

	public static void draw(Std140Builder b, Matrix4fc model, Matrix4fc probeMat, float[] probes16, float[] dyn4,
			float[] ground3) {
		b.putMat4f(model);
		b.putMat4f(probeMat);
		for (int i = 0; i < 8; i++) {
			b.putVec4(probes16[2 * i], probes16[2 * i + 1], 0, 0);
		}
		b.putVec4(dyn4[0], dyn4[1], dyn4[2], dyn4[3]);
		b.putVec4(ground3[0], ground3[1], ground3[2], 0);
	}

	static void body(Std140Builder b, RlMaterial m, float[] teamColor, float[] srgb5) {
		b.putVec4(teamColor[0], teamColor[1], teamColor[2], 1);
		vec4(b, m.vector("CustomColor", BODY_CUSTOM_COLOR));
		vec4(b, m.vector("PaintColor", PAINT_COLOR));
		vec4(b, m.vector("TrimColor", TRIM_COLOR));
		vec4(b, m.vector("F1ControlA", RlLook.FINISH_CONTROL_A));
		vec4(b, m.vector("F2ControlA", RlLook.FINISH_CONTROL_A));
		vec4(b, m.vector("F1ControlB", RlLook.FINISH_CONTROL_B));
		vec4(b, m.vector("F2ControlB", RlLook.FINISH_CONTROL_B));
		b.putVec4(m.scalar("F1Type", RlLook.FINISH_TYPE) / 32 + ROW, m.scalar("F2Type", RlLook.FINISH_TYPE) / 32 + ROW,
				m.scalar("TertiaryMaterial_Type", TERT_TYPE), m.scalar("TertiaryNormalTiling", TERT_TILING));
		vec4(b, m.vector("TertiaryMaterial_ControlA", TERT_CONTROL_A));
		vec4(b, m.vector("TertiaryMaterial_ControlB", TERT_CONTROL_B));
		vec4(b, m.vector("TertiaryMaterial_Color", TERT_COLOR));
		b.putVec4(srgb5[0], srgb5[1], srgb5[2], srgb5[3]);
		b.putVec4(srgb5[4], 0, 0, 0);
	}

	static void chassis(Std140Builder b, RlMaterial chassis, float[] srgb2) {
		float[] head = chassis.vector("HeadlightColor", HEADLIGHT_COLOR);
		float[] tail = chassis.vector("TailLightColor", TAIL_LIGHT_COLOR);
		float[] glow = RlLook.BOOST_GLOW_COLOR;
		b.putVec4(head[0], head[1], head[2], chassis.scalar("HeadlightBrightness", HEADLIGHT_BRIGHTNESS));
		b.putVec4(tail[0], tail[1], tail[2], chassis.scalar("TailLightBrightness", TAIL_LIGHT_BRIGHTNESS));
		b.putVec4(glow[0], glow[1], glow[2], chassis.scalar("BoostGlowBrightness", BOOST_GLOW_BRIGHTNESS));
		b.putVec4(srgb2[0], srgb2[1], 0, 0);
	}

	static void wheel(Std140Builder b, RlMaterial wheel, float[] srgb3) {
		float[] custom = wheel.vector("CustomColor", WHEEL_CUSTOM_COLOR);
		float[] rim = wheel.vector("RimColor", RIM_COLOR);
		b.putVec4(custom[0], custom[1], custom[2], custom.length > 3 ? custom[3] : 1);
		b.putVec4(rim[0], rim[1], rim[2], rim.length > 3 ? rim[3] : 1);
		b.putVec4(wheel.scalar("SpecPower", SPEC_POWER), wheel.scalar("SpecIntensity", SPEC_INTENSITY),
				wheel.scalar("ReflectionBrightness", REFLECTION_BRIGHTNESS), 0);
		b.putVec4(wheel.scalar("Rim_AdditionalNormal_Power", NORMAL_POWER),
				wheel.scalar("Tire_AdditionalNormal_Power", NORMAL_POWER), 0, 0);
		b.putVec4(srgb3[0], srgb3[1], srgb3[2], 0);
	}

	static void ball(Std140Builder b, RlMaterial ball, float[] srgb2) {
		float[] loc = ball.vector("GoalPlaneLocation", GOAL_PLANE_LOCATION);
		float[] normal = ball.vector("GoalPlaneNormal", GOAL_PLANE_NORMAL);
		b.putVec4(loc[0], loc[1], loc[2], 0);
		b.putVec4(normal[0], normal[1], normal[2], 0);
		b.putVec4(RlLook.BALL_TINT[0], RlLook.BALL_TINT[1], RlLook.BALL_TINT[2], 0);
		b.putVec4(srgb2[0], srgb2[1], 0, 0);
	}

	private static void vec4(Std140Builder b, Vector3f v, float w) {
		b.putVec4(v.x, v.y, v.z, w);
	}

	private static void vec4(Std140Builder b, float[] v) {
		b.putVec4(v[0], v[1], v[2], v.length > 3 ? v[3] : 1);
	}
}
