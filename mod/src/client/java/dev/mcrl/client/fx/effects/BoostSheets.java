package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.particle.FxModules;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;

public final class BoostSheets {
	public static final String SHEET_MESH = "boost_sheet", AMB_MESH = "amb_boost";
	public static final String[] SHEETS = {"BoostConeMesh", "BoostConeMesh02"};
	public static final String AMB = "AmbientBoostMesh";

	private BoostSheets() {
	}

	public static Matrix4f sheet(JsonObject component) {
		float scale = number(component, "Scale", 1);
		JsonObject s3 = object(component, "Scale3D"), r = object(component, "Rotation"), t = object(component,
				"Translation");
		float[] s = {scale * number(s3, "X", 1), scale * number(s3, "Y", 1), scale * number(s3, "Z", 1)};
		float pitch = number(r, "Pitch", 0) / 65536f, yaw = number(r, "Yaw", 0) / 65536f,
				roll = number(r, "Roll", 0) / 65536f;
		Matrix4f m = new Matrix4f();
		Vector3f col = new Vector3f();
		for (int k = 0; k < 3; k++) {
			col.set(k == 0 ? s[0] : 0, k == 1 ? s[1] : 0, k == 2 ? s[2] : 0);
			FxModules.rotate(roll, pitch, yaw, col);
			m.setColumn(k, new org.joml.Vector4f(col, 0));
		}
		m.setTranslation(number(t, "X", 0), number(t, "Y", 0), number(t, "Z", 0));
		return m;
	}

	public static Matrix4f carLocal(Vector3fc chassis, JsonObject component, Matrix4f out) {
		Matrix4f u = sheet(component);
		out.set(u.m00(), u.m02(), u.m01(), 0,
				u.m20(), u.m22(), u.m21(), 0,
				u.m10(), u.m12(), u.m11(), 0,
				chassis.x() + u.m30() / 50, chassis.y() + u.m32() / 50, chassis.z() + u.m31() / 50, 1);
		return out;
	}

	public static @Nullable JsonObject override(JsonObject boostMesh, String name, String carTypePath) {
		JsonElement params = boostMesh.get("MaterialParams");
		if (params == null || !params.isJsonArray()) {
			return null;
		}
		for (JsonElement p : (JsonArray) params) {
			JsonObject o = p.getAsJsonObject();
			if (!name.equals(string(o, "MeshAttachmentName"))) {
				continue;
			}
			JsonElement overrides = o.get("MeshOverrides");
			if (overrides != null && overrides.isJsonArray()) {
				for (JsonElement ov : (JsonArray) overrides) {
					JsonObject v = ov.getAsJsonObject();
					if (carTypePath.equals(string(v, "CarTypePath")) && v.get("Mesh") != null && v.get("Mesh").isJsonObject()) {
						return v.getAsJsonObject("Mesh");
					}
				}
			}
		}
		return null;
	}

	public static String carTypePath(BodyLook look) {
		String p = look.fxActorPath();
		return p == null ? FxConstants.OCTANE_BODY_FX : p.substring(p.indexOf(':') + 1);
	}

	public static @Nullable JsonObject sheetOverride(@Nullable JsonObject boostMesh, String name, BodyLook look) {
		return boostMesh == null ? null : override(boostMesh, name, carTypePath(look));
	}

	public record Draw(RlCache.Mesh mesh, Matrix4f local, Pose pose, float[] color, String material) {
		public Matrix4f cameraRelative(Vec3 camera, Matrix4f out) {
			out.rotation(new Quaternionf(pose.qx(), pose.qy(), pose.qz(), pose.qw())).mul(local);
			out.m30((float) (out.m30() + pose.x() - camera.x)).m31((float) (out.m31() + pose.y() - camera.y))
					.m32((float) (out.m32() + pose.z() - camera.z));
			return out;
		}
	}

	public static @Nullable Draw resolve(@Nullable RlCache rl, BodyLook look, FxDrawList.Static s) {
		if (rl == null) {
			return null;
		}
		String name = s.def().name();
		Matrix4f local;
		RlCache.Mesh mesh;
		float[] color;
		String material;
		if (name.equals(SHEETS[0]) || name.equals(SHEETS[1])) {
			JsonObject c = sheetOverride(rl.fxObjects().get("boostMesh"), name, look);
			mesh = rl.mesh(SHEET_MESH);
			if (c == null || mesh == null) {
				return null;
			}
			local = carLocal(s.offset(), c, new Matrix4f());
			color = FxConstants.BOOST_SHEET_COLOR;
			material = FxConstants.BOOST_SHEET_MATERIAL;
		} else if (name.equals(AMB)) {
			mesh = rl.mesh(AMB_MESH);
			if (mesh == null) {
				return null;
			}
			local = new Matrix4f().translation(s.offset()).rotate(s.rot());
			color = FxConstants.AMB_BOOST_COLOR;
			material = FxConstants.AMB_BOOST_MATERIAL;
		} else {
			return null;
		}
		return new Draw(mesh, local, s.pose(), color, material);
	}

	private static JsonObject object(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	private static float number(JsonObject o, String key, float fallback) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : fallback;
	}

	private static @Nullable String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
