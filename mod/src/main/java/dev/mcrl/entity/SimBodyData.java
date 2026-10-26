package dev.mcrl.entity;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import dev.mcrl.math.Pose;

final class SimBodyData {
	static final String POSE = "mcrl_pose";
	static final String VEL = "mcrl_vel";
	static final String ANGVEL = "mcrl_angvel";
	private static final Codec<List<Double>> DOUBLES = Codec.DOUBLE.listOf();

	record Body(Pose pose, double vx, double vy, double vz, double wx, double wy, double wz) {
	}

	private SimBodyData() {
	}

	static void write(ValueOutput out, Body b) {
		Pose p = b.pose();
		out.store(POSE, DOUBLES,
				List.of(p.x(), p.y(), p.z(), (double) p.qw(), (double) p.qx(), (double) p.qy(), (double) p.qz()));
		out.store(VEL, DOUBLES, List.of(b.vx(), b.vy(), b.vz()));
		out.store(ANGVEL, DOUBLES, List.of(b.wx(), b.wy(), b.wz()));
	}

	static @Nullable Body read(ValueInput in) {
		double[] p = doubles(in.read(POSE, DOUBLES), 7);
		if (p == null) {
			return null;
		}
		double n = p[3] * p[3] + p[4] * p[4] + p[5] * p[5] + p[6] * p[6];
		if (!(n > 1e-12)) {
			return null;
		}
		double[] v = doubles(in.read(VEL, DOUBLES), 3);
		double[] w = doubles(in.read(ANGVEL, DOUBLES), 3);
		if (v == null) {
			v = new double[3];
		}
		if (w == null) {
			w = new double[3];
		}
		Pose pose = new Pose(p[0], p[1], p[2], (float) p[3], (float) p[4], (float) p[5], (float) p[6])
				.clampedToWorld();
		return new Body(pose, v[0], v[1], v[2], w[0], w[1], w[2]);
	}

	private static double @Nullable [] doubles(Optional<List<Double>> list, int n) {
		if (list.isEmpty() || list.get().size() != n) {
			return null;
		}
		double[] d = new double[n];
		for (int i = 0; i < n; i++) {
			Double x = list.get().get(i);
			if (x == null || !Double.isFinite(x)) {
				return null;
			}
			d[i] = x;
		}
		return d;
	}
}
