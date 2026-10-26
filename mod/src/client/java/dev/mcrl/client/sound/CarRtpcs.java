package dev.mcrl.client.sound;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.WheelSignal;
import dev.mcrl.client.sound.ak.AkEngine;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.client.sound.ak.Listener;
import dev.mcrl.math.Pose;

public final class CarRtpcs {
	private static final double MAX_WHEEL_SPEED = 150;
	private static final double MAX_SPEED = 2300;
	private static final double MAX_ALTITUDE = 3200;
	private static final double SPAM_DEFAULT = 1, SPAM_MAX = 5, SPAM_DECAY = 0.2, SPAM_DECAY_PER = 0.56;

	private double forward, side, spam = SPAM_DEFAULT;
	private double @Nullable [] reference;

	public static double groundWheelSpeed(List<WheelSignal> w) {
		double sum = 0;
		for (WheelSignal s : w) {
			sum += Math.abs(s.spinRadPerSec());
		}
		return Math.min(w.isEmpty() ? 0 : sum / w.size(), MAX_WHEEL_SPEED);
	}

	public void reset(Pose pose) {
		reference = rotator(pose);
	}

	public void boostStarted() {
		spam = Math.min(SPAM_MAX, spam + 1);
	}

	public void update(CarSignals s, EngineRpmModel e, Level level, Listener l, double dt, GameObject car) {
		dt = Math.max(0, Math.min(dt, AkEngine.MAX_DT));
		Pose pose = s.pose();
		Vec3 pos = new Vec3(pose.x(), pose.y(), pose.z());
		car.setRtpc(GameParams.SPEED, s.velocity().length());
		car.setRtpc(GameParams.RPM, e.rpm());
		car.setRtpc(GameParams.THROTTLE_INPUT, Math.abs(s.throttle()));
		car.setRtpc(GameParams.IS_LOCAL, s.local() ? 1 : 0);
		List<WheelSignal> wheels = List.of(s.wheels());
		car.setRtpc(GameParams.WHEEL_SPEED,
				s.onGround() ? groundWheelSpeed(wheels) : e.normalizedRpm() * MAX_WHEEL_SPEED);
		double f = 0, sd = 0;
		for (WheelSignal w : wheels) {
			f += w.forwardSpeed();
			sd += w.sideSpeed();
		}
		forward = smoothed(forward, Math.abs(f / wheels.size()), dt);
		side = smoothed(side, Math.abs(sd / wheels.size()), dt);
		car.setRtpc(GameParams.WHEEL_FORWARD_SPEED, forward);
		car.setRtpc(GameParams.WHEEL_SIDE_SPEED, side);
		car.setRtpc(GameParams.WHEELS_ON_GROUND, s.onGround() ? 1 : 0);
		double[] rot = rotator(pose);
		if (reference == null) {
			reference = rot;
		}
		if (s.local() && !s.onGround()) {
			car.setRtpc(GameParams.CAR_PITCH, Math.abs(wrap(rot[0] - reference[0])) / 16384);
			car.setRtpc(GameParams.CAR_YAW, 1 - Math.abs(1 - Math.abs(wrap(rot[1] - reference[1])) / 16384));
			car.setRtpc(GameParams.CAR_ROLL, 1 - Math.abs(1 - Math.abs(wrap(rot[2] - reference[2])) / 16384));
		}
		car.setRtpc(GameParams.ALTITUDE, altitude(level, pos));
		car.setRtpc(GameParams.DOPPLER_ANGLE, s.local() ? 0 : doppler(pos, s.velocity(), l));
		double c = SPAM_DECAY / SPAM_DECAY_PER;
		spam = Math.max(0, (spam + c) * Math.exp(-SPAM_DECAY_PER * dt) - c);
		car.setRtpc(GameParams.SPAM_CONTROL_BOOST, spam);
	}

	private static double smoothed(double cur, double target, double dt) {
		target = Math.min(target, MAX_SPEED);
		return target > cur ? cur + Math.min(5 * dt, 1) * (target - cur) : target;
	}

	static double[] rotator(Pose p) {
		double[] x = ue(rotate(p, 1, 0, 0)), y = ue(rotate(p, 0, 0, 1)), z = ue(rotate(p, 0, 1, 0));
		double pitch = Math.atan2(x[2], Math.hypot(x[0], x[1]));
		double yaw = Math.atan2(x[1], x[0]);
		double[] sy = {-Math.sin(yaw), Math.cos(yaw), 0};
		double roll = Math.atan2(dot(z, sy), dot(y, sy));
		// Unreal rotator units.
		double k = 32768 / Math.PI;
		return new double[] {pitch * k, yaw * k, roll * k};
	}

	static double wrap(double units) {
		double w = (units + 32768) % 65536;
		return (w < 0 ? w + 65536 : w) - 32768;
	}

	private static double[] rotate(Pose p, double vx, double vy, double vz) {
		double w = p.qw(), x = p.qx(), y = p.qy(), z = p.qz();
		double tx = 2 * (y * vz - z * vy), ty = 2 * (z * vx - x * vz), tz = 2 * (x * vy - y * vx);
		return new double[] {vx + w * tx + (y * tz - z * ty), vy + w * ty + (z * tx - x * tz),
			vz + w * tz + (x * ty - y * tx)};
	}

	private static double[] ue(double[] mc) {
		return new double[] {mc[0], mc[2], mc[1]};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static double doppler(Vec3 pos, Vec3 velocity, Listener l) {
		Vec3 to = l.pos().subtract(pos);
		if (to.lengthSqr() < 1e-12 || velocity.lengthSqr() < 1e-12) {
			return 0;
		}
		return to.normalize().dot(velocity.normalize());
	}

	static double altitude(Level level, Vec3 pos) {
		int maxBlocks = (int) Math.ceil(MAX_ALTITUDE / 50) + 1;
		BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
		for (int i = 0, y = (int) Math.floor(pos.y); i <= maxBlocks; i++, y--) {
			bp.set(Math.floor(pos.x), y, Math.floor(pos.z));
			VoxelShape shape = level.getBlockState(bp).getCollisionShape(EmptyBlockGetter.INSTANCE, bp);
			if (!shape.isEmpty()) {
				double top = y + shape.max(Direction.Axis.Y);
				if (top <= pos.y) {
					return Math.min(MAX_ALTITUDE, (pos.y - top) * 50);
				}
			}
		}
		return MAX_ALTITUDE;
	}
}
