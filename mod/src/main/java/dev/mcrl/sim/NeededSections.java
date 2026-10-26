package dev.mcrl.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.mcrl.sim.SimHost.SectionKey;

final class NeededSections {
	static final double MAX_SPEED = 120;
	static final double MIN_REACH_SPEED = 1;

	record Body(double x, double y, double z, double vx, double vy, double vz, double lookaheadS) {
	}

	static final NeededSections NONE = new NeededSections(List.of(), Set.of());

	private final List<int[]> boxes;
	private final Set<SectionKey> ordered;

	private NeededSections(List<int[]> boxes, Set<SectionKey> ordered) {
		this.boxes = boxes;
		this.ordered = ordered;
	}

	static NeededSections of(List<Body> bodies, double margin) {
		List<int[]> boxes = new ArrayList<>(2 * bodies.size());
		List<double[]> reach = new ArrayList<>(bodies.size());
		for (Body b : bodies) {
			if (!Double.isFinite(b.x()) || !Double.isFinite(b.y()) || !Double.isFinite(b.z())) {
				continue;
			}
			double vx = b.vx(), vy = b.vy(), vz = b.vz();
			double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
			if (!Double.isFinite(speed)) {
				vx = vy = vz = speed = 0;
			} else if (speed > MAX_SPEED) {
				double k = MAX_SPEED / speed;
				vx *= k;
				vy *= k;
				vz *= k;
				speed = MAX_SPEED;
			}
			double t = b.lookaheadS();
			int[] hull = new int[6];
			axis(hull, 0, b.x(), b.x() + vx * t, margin);
			axis(hull, 1, b.y(), b.y() + vy * t, margin);
			axis(hull, 2, b.z(), b.z() + vz * t, margin);
			int sx = SectionKey.sec(b.x()), sy = SectionKey.sec(b.y()), sz = SectionKey.sec(b.z());
			boxes.add(hull);
			boxes.add(new int[] {sx - 1, sy - 1, sz - 1, sx + 1, sy + 1, sz + 1});
			reach.add(new double[] {b.x(), b.y(), b.z(), Math.max(speed, MIN_REACH_SPEED), sx, sy, sz});
		}
		if (boxes.isEmpty()) {
			return NONE;
		}
		Map<SectionKey, double[]> keys = new HashMap<>();
		for (int[] box : boxes) {
			for (int y = box[1]; y <= box[4]; y++) {
				for (int z = box[2]; z <= box[5]; z++) {
					for (int x = box[0]; x <= box[3]; x++) {
						SectionKey k = new SectionKey(x, y, z);
						if (!keys.containsKey(k)) {
							keys.put(k, priority(k, reach));
						}
					}
				}
			}
		}
		List<Map.Entry<SectionKey, double[]>> l = new ArrayList<>(keys.entrySet());
		l.sort(Comparator.<Map.Entry<SectionKey, double[]>>comparingDouble(e -> e.getValue()[0])
				.thenComparingDouble(e -> e.getValue()[1])
				.thenComparingInt(e -> e.getKey().y())
				.thenComparingInt(e -> e.getKey().z())
				.thenComparingInt(e -> e.getKey().x()));
		LinkedHashSet<SectionKey> s = new LinkedHashSet<>(l.size() * 2);
		for (Map.Entry<SectionKey, double[]> e : l) {
			s.add(e.getKey());
		}
		return new NeededSections(List.copyOf(boxes), Collections.unmodifiableSet(s));
	}

	private static void axis(int[] box, int i, double a, double e, double margin) {
		box[i] = SectionKey.sec(Math.min(a, e) - margin);
		box[i + 3] = SectionKey.sec(Math.max(a, e) + margin);
	}

	private static double[] priority(SectionKey k, List<double[]> reach) {
		double t = Double.POSITIVE_INFINITY, cheb = Double.POSITIVE_INFINITY;
		for (double[] r : reach) {
			double dx = gap(r[0], k.x()), dy = gap(r[1], k.y()), dz = gap(r[2], k.z());
			t = Math.min(t, Math.sqrt(dx * dx + dy * dy + dz * dz) / r[3]);
			double c = Math.max(Math.abs(k.x() - r[4]), Math.max(Math.abs(k.y() - r[5]), Math.abs(k.z() - r[6])));
			cheb = Math.min(cheb, c);
		}
		return new double[] {t, cheb};
	}

	private static double gap(double p, int s) {
		double lo = 16.0 * s;
		return Math.max(0, Math.max(lo - p, p - (lo + 16)));
	}

	Set<SectionKey> sections() {
		return ordered;
	}

	boolean near(SectionKey k, int r) {
		for (int[] b : boxes) {
			if (k.x() >= b[0] - r && k.x() <= b[3] + r && k.y() >= b[1] - r && k.y() <= b[4] + r && k.z() >= b[2] - r
					&& k.z() <= b[5] + r) {
				return true;
			}
		}
		return false;
	}
}
