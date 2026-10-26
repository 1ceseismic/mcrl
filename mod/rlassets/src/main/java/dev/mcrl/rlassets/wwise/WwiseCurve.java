package dev.mcrl.rlassets.wwise;

import java.util.List;

public final class WwiseCurve {
	public static final double MIN_DB = -96.3; // Wwise's dB floor

	private WwiseCurve() {
	}

	public enum Shape {
		LOG3("Log3"), SINE("Sine"), LOG1("Log1"), INV_S_CURVE("InvSCurve"), LINEAR("Linear"), S_CURVE("SCurve"),
		EXP1("Exp1"), SINE_RECIP("SineRecip"), EXP3("Exp3"), CONSTANT("Constant");

		private static final Shape[] VALUES = values();
		private final String label;

		Shape(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public static Shape of(int value) {
			if (value < 0 || value >= VALUES.length) {
				throw new IllegalArgumentException("curve shape " + value + " is not 0..9");
			}
			return VALUES[value];
		}

		public static Shape parse(String name) {
			if (name.equals("Const")) {
				return CONSTANT;
			}
			for (Shape s : VALUES) {
				if (s.label.equals(name)) {
					return s;
				}
			}
			throw new IllegalArgumentException("unknown curve shape " + name);
		}
	}

	public record Point(double x, double y, Shape shape) {
	}

	public static double eval(List<Point> pts, double x) {
		int n = pts.size();
		if (n == 0) {
			return 0;
		}
		for (int k = 0; k < n; k++) {
			Point p1 = pts.get(k);
			if (p1.x() >= x || k + 1 == n) {
				return p1.y();
			}
			Point p2 = pts.get(k + 1);
			if (p2.x() > x) {
				return interp((x - p1.x()) / (p2.x() - p1.x()), p1.y(), p2.y(), p1.shape());
			}
		}
		return pts.get(n - 1).y();
	}

	// Wwise's curve shapes, with its polynomial constants.
	public static double interp(double t, double a, double b, Shape s) {
		return switch (s) {
			case LOG3 -> (1 - t) * (1 - t) * (1 - t) * (a - b) + b;
			case SINE -> {
				double v1 = (1.5707964 * t) * (1.5707964 * t);
				double v2 = (v1 * -0.00018363654 + 0.0083063254) * v1 + -0.16664828;
				double v3 = v2 * v1 + 0.9999966;
				yield v3 * (1.5707964 * t) * (b - a) + a;
			}
			case LOG1 -> (t - 3.0) * t * 0.5 * (a - b) + a;
			case INV_S_CURVE -> {
				if (t > 0.5) {
					double v1 = 3.1415927 - 3.1415927 * t;
					double v2 = (v1 * v1 * -0.00009181827 + 0.0041531627) * (v1 * v1) + -0.083324142;
					yield (1.0 - (v2 * (v1 * v1) + 0.4999983) * v1) * (b - a) + a;
				}
				double v1 = (3.1415927 * t) * (3.1415927 * t);
				double v2 = (v1 * -0.00009181827 + 0.0041531627) * v1 + -0.083324142;
				yield (v2 * v1 + 0.4999983) * (3.1415927 * t) * (b - a) + a;
			}
			case LINEAR -> (b - a) * t + a;
			case S_CURVE -> {
				double v1 = (3.1415927 * t) * (3.1415927 * t);
				double v2 = (v1 * 0.00048483399 + -0.01961384) * v1 + 0.24767479;
				double v3 = v2 * v1 + 0.00069670216;
				yield v3 * (b - a) + a;
			}
			case EXP1 -> (t + 1.0) * t * 0.5 * (b - a) + a;
			case SINE_RECIP -> {
				double v1 = (1.5707964 * t) * (1.5707964 * t);
				double v2 = (v1 * -0.0012712094 + 0.04148775) * v1 + -0.49991244;
				double v3 = v2 * v1 + 0.99999332;
				yield v3 * (a - b) + b;
			}
			case EXP3 -> t * t * t * (b - a) + a;
			case CONSTANT -> a;
		};
	}

	public static double dbOfY(double y) {
		double c = Math.max(-1, Math.min(1, y));
		return c <= -1 ? MIN_DB : 20 * Math.log10(1 + c);
	}

	public static double gainOfDb(double db) {
		return db <= MIN_DB ? 0 : Math.pow(10, db / 20);
	}
}
