package dev.mcrl.client.fx.effects;

import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.particle.FxModules;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;

public final class Sockets {
	private Sockets() {
	}

	public static Vector3f carLocal(@Nullable RlCache rl, @Nullable String socket) {
		return rl == null ? carLocal(n -> null, 0, socket) : carLocal(rl.bodies().octane(), socket);
	}

	public static Vector3f carLocal(BodyLook look, @Nullable String socket) {
		return carLocal(look::socket, look.wheels().tz(), socket);
	}

	public static Vector3f carLocal(Function<String, @Nullable Vector3f> sockets, double tzUu, @Nullable String socket) {
		if (socket == null || socket.equals("None")) {
			return new Vector3f();
		}
		Vector3f s = sockets.apply(socket);
		if (s != null) {
			return new Vector3f(s);
		}
		Vector3f chassis = chassis(sockets, tzUu);
		if (socket.equals(FxConstants.CHASSIS_JNT)) {
			return chassis;
		}
		float[] uu = FxConstants.OCTANE_SOCKETS_UU.get(socket);
		return uu == null ? new Vector3f() : chassis.add(mc(uu[0], uu[1], uu[2]));
	}

	private static Vector3f chassis(Function<String, @Nullable Vector3f> sockets, double tzUu) {
		Vector3f rocket = sockets.apply("RocketBoost");
		if (rocket == null) {
			return new Vector3f(0, (float) (tzUu / 50), 0);
		}
		float[] rb = FxConstants.OCTANE_SOCKETS_UU.get("RocketBoost");
		return new Vector3f(rocket).sub(mc(rb[0], rb[1], rb[2]));
	}

	public static Vector3f mc(float x, float y, float z) {
		return new Vector3f(x / 50, z / 50, y / 50);
	}

	public static Vector3f translation(JsonObject component) {
		JsonElement t = component.get("Translation");
		return t == null || !t.isJsonObject() ? new Vector3f() : vector(t.getAsJsonObject());
	}

	public static Vector3f vector(JsonObject o) {
		return mc(f(o, "X"), f(o, "Y"), f(o, "Z"));
	}

	public static Quaternionf rotation(JsonObject component) {
		JsonElement r = component.get("Rotation");
		return r == null || !r.isJsonObject() ? new Quaternionf() : rotator(r.getAsJsonObject());
	}

	public static Quaternionf rotator(JsonObject o) {
		return rotation(f(o, "Roll") / 65536f, f(o, "Pitch") / 65536f, f(o, "Yaw") / 65536f);
	}

	public static Quaternionf alongNormal(Vec3 normal) {
		double x = normal.x, y = normal.z, z = normal.y;
		double yaw = Math.atan2(y, x), pitch = Math.atan2(z, Math.sqrt(x * x + y * y));
		return rotation(0, (float) (pitch / (2 * Math.PI)), (float) (yaw / (2 * Math.PI)));
	}

	public static Quaternionf rotation(float roll, float pitch, float yaw) {
		Matrix3f m = new Matrix3f();
		Vector3f v = new Vector3f();
		for (int k = 0; k < 3; k++) {
			v.set(k == 0 ? 1 : 0, k == 2 ? 1 : 0, k == 1 ? 1 : 0);
			FxModules.rotate(roll, pitch, yaw, v);
			m.setColumn(k, v.x, v.z, v.y);
		}
		return m.getNormalizedRotation(new Quaternionf());
	}

	private static float f(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsFloat() : 0;
	}
}
