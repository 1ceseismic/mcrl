package dev.mcrl.client.camera;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.BallSignals;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.WheelSignal;
import dev.mcrl.math.Pose;

public final class CamSpace {
	public record Angles(float yRot, float xRot, float roll) {
	}

	public record View(Vec3 car, Vec3 focus, Angles base, double distance, double shakeFwd, double shakeUp,
			double shakeRight, Angles shaken, float vFov) {
	}

	private static final double DEG_PER_UNIT = 360.0 / 65536;

	private CamSpace() {
	}

	public static Vec3 rlPos(double x, double y, double z) {
		return new Vec3(x * UU_PER_BLOCK, z * UU_PER_BLOCK, y * UU_PER_BLOCK);
	}

	public static Vec3 mcPos(Vec3 rl) {
		return new Vec3(rl.x / UU_PER_BLOCK, rl.z / UU_PER_BLOCK, rl.y / UU_PER_BLOCK);
	}

	public static Vec3 rlDir(Vec3 mc) {
		return new Vec3(mc.x, mc.z, mc.y);
	}

	public static float mcYRot(Rot r) {
		return (float) (r.yaw() * DEG_PER_UNIT - 90);
	}

	public static float mcXRot(Rot r) {
		return (float) (-r.pitch() * DEG_PER_UNIT);
	}

	public static float rollDeg(Rot r) {
		return (float) (r.roll() * DEG_PER_UNIT);
	}

	public static Quaternionf cameraRotation(float yRot, float xRot, float rollDeg) {
		float d = (float) (Math.PI / 180);
		return new Quaternionf().rotationYXZ((float) Math.PI - yRot * d, -xRot * d, -rollDeg * d);
	}

	public static float verticalFov(double hFovDeg) {
		return (float) Math.toDegrees(2 * Math.atan(RlCamConstants.ASPECT_16_9 * Math.tan(Math.toRadians(hFovDeg) / 2)));
	}

	public static CamInput input(CarSignals car, @Nullable BallSignals ball) {
		Vec3 sum = Vec3.ZERO;
		int n = 0;
		for (int i = 0; i < 4; i++) {
			WheelSignal w = car.wheel(i);
			if (w.contact()) {
				sum = sum.add(w.normal());
				n++;
			}
		}
		Vec3 normal = n == 0 ? null : sum;
		Vec3 b = ball == null ? null : new Vec3(ball.pose().x(), ball.pose().y(), ball.pose().z());
		return input(car.pose(), car.velocity(), car.onGround(), car.fx().jumping(), car.supersonic(), normal, b);
	}

	public static CamInput input(Pose drawn, Vec3 mcVelocityUu, boolean onGround, boolean jumping, boolean supersonic,
			@Nullable Vec3 mcGroundNormal, @Nullable Vec3 mcBall) {
		Vec3 normal = mcGroundNormal == null ? null : UeMath.normal(rlDir(mcGroundNormal));
		if (normal != null && normal.lengthSqr() == 0) {
			normal = null;
		}
		return new CamInput(rlPos(drawn.x(), drawn.y(), drawn.z()), rlDir(drawn.forward()), rlDir(drawn.right()),
				rlDir(drawn.up()), rlDir(mcVelocityUu), onGround, jumping, supersonic, normal,
				mcBall == null ? null : rlPos(mcBall.x, mcBall.y, mcBall.z));
	}

	public static View view(RlCamera.Frame f, Vec3 carMc) {
		Pov p = f.pov();
		Vec3 shake = f.shakeLoc();
		return new View(carMc, mcPos(p.focus()), angles(p.rot()), p.distance() / UU_PER_BLOCK, shake.x / UU_PER_BLOCK,
				shake.z / UU_PER_BLOCK, shake.y / UU_PER_BLOCK, angles(p.rot().plus(f.shakeRot())), verticalFov(f.fov()));
	}

	public static Vec3 pivot(Vec3 car, Vec3 focus, @Nullable Vec3 hit, @Nullable Vec3 normal) {
		if (hit == null || normal == null) {
			return focus;
		}
		Vec3 seg = focus.subtract(car);
		double len = seg.length();
		if (len == 0) {
			return focus;
		}
		Vec3 dir = seg.scale(1 / len);
		double along = Math.max(0, Math.min(len, hit.distanceTo(car) - RlCamConstants.PIVOT_BACKOFF));
		Vec3 p = car.add(dir.scale(along));
		double off = p.subtract(hit).dot(normal);
		return off < RlCamConstants.FACE_CLEARANCE ? p.add(normal.scale(RlCamConstants.FACE_CLEARANCE - off)) : p;
	}

	public static Vec3 clipEnd(Vec3 car, Vec3 focus) {
		Vec3 seg = focus.subtract(car);
		double len = seg.length();
		return len == 0 ? focus : focus.add(seg.scale(RlCamConstants.PIVOT_BACKOFF / len));
	}

	private static Angles angles(Rot r) {
		return new Angles(mcYRot(r), mcXRot(r), rollDeg(r));
	}

	private static Vec3 rlDir(Vector3d mc) {
		return new Vec3(mc.x, mc.z, mc.y);
	}
}
