package dev.mcrl.rlassets.bodies;

import java.util.Map;

import org.jspecify.annotations.Nullable;

public final class PresetMatch {
	public static final double TOLERANCE_UU = 0.05;
	// RocketSim's presets: right wheel rest, [front, back][x, |y|, z].
	public static final double[][][] REST_UU = {
		{{51.25, 25.90, -6.0}, {-33.75, 29.50, -4.3}}, // OCTANE
		{{50.30, 31.10, -6.2}, {-34.75, 33.00, -6.1}}, // DOMINUS
		{{49.97, 27.80, -7.83}, {-35.43, 20.28, -3.83}}, // PLANK
		{{51.50, 26.67, -5.95}, {-35.75, 35.00, -5.916}}, // BREAKOUT
		{{51.25, 25.90, -6.0}, {-34.00, 29.50, -4.3}}, // HYBRID
		{{51.25, 25.90, -6.0}, {-33.75, 29.50, -5.6}}, // MERC
		{{51.25, 5.00, -6.0}, {-33.75, 29.50, -4.3}}, // PSYCLOPS
	};
	public static final Map<String, Integer> BY_NAME = Map.of("Octane", 0, "MuscleCar", 1, "Plank", 2, "LongCar", 3,
			"Hybrid", 4, "Merc", 5, "Pixie", 6);

	private PresetMatch() {
	}

	public static int byRest(double[] front, double[] back) {
		for (int i = 0; i < REST_UU.length; i++) {
			if (near(front, REST_UU[i][0]) && near(back, REST_UU[i][1])) {
				return i;
			}
		}
		return -1;
	}

	public static int byName(@Nullable String handlingPreset) {
		if (handlingPreset == null) {
			return -1;
		}
		int u = handlingPreset.indexOf('_');
		Integer i = BY_NAME.get(u < 0 ? handlingPreset : handlingPreset.substring(0, u));
		return i == null ? -1 : i;
	}

	public static int resolve(double @Nullable [] front, double @Nullable [] back, @Nullable String handlingPreset) {
		int r = front != null && back != null ? byRest(front, back) : -1;
		if (r < 0) {
			r = byName(handlingPreset);
		}
		return r < 0 ? 0 : r;
	}

	private static boolean near(double[] v, double[] want) {
		return v.length == 3 && Math.abs(v[0] - want[0]) <= TOLERANCE_UU
				&& Math.abs(Math.abs(v[1]) - want[1]) <= TOLERANCE_UU && Math.abs(v[2] - want[2]) <= TOLERANCE_UU;
	}
}
