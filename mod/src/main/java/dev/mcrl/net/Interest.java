package dev.mcrl.net;

import java.util.Arrays;
import java.util.List;

import dev.mcrl.math.Pose;
import dev.mcrl.net.SnapshotSender.Recipient;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;

public final class Interest {
	public record Selection(int[] carIds, boolean ball) {
	}

	private Interest() {
	}

	public static Selection select(Recipient r, SimFrame f, long tick, SimHost.Kind kind) {
		return select(r.x(), r.y(), r.z(), r.ownCarId(), f, tick, kind != SimHost.Kind.TERRAIN);
	}

	static Selection select(double x, double y, double z, int ownCarId, SimFrame f, long tick, boolean everyBody) {
		List<SimFrame.CarSnap> cars = f.cars();
		int[] ids = new int[cars.size()];
		int n = 0;
		boolean thin = cars.size() >= NetProtocol.FAR_MIN_CARS && tick % NetProtocol.FAR_INTERVAL_TICKS != 0;
		double range2 = sq(NetProtocol.CAR_RANGE), far2 = sq(NetProtocol.FAR_BLOCKS);
		for (SimFrame.CarSnap c : cars) {
			if (!everyBody && c.carId() != ownCarId) {
				double d2 = dist2(c.cur(), x, y, z);
				if (d2 > range2 || thin && d2 > far2) {
					continue;
				}
			}
			ids[n++] = c.carId();
		}
		SimFrame.BallSnap b = f.ball();
		boolean ball = b != null && (everyBody || dist2(b.cur(), x, y, z) <= sq(NetProtocol.BALL_RANGE));
		return new Selection(Arrays.copyOf(ids, n), ball);
	}

	private static double dist2(Pose p, double x, double y, double z) {
		double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private static double sq(double v) {
		return v * v;
	}
}
