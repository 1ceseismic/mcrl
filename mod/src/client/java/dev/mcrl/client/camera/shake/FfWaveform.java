package dev.mcrl.client.camera.shake;

import java.util.List;

public record FfWaveform(List<Sample> samples) {
	public enum Fn {
		CONSTANT, LINEAR_DECREASING, SIN_0_TO_180
	}

	public record Sample(double left, double right, Fn fn, double duration) {
	}

	public FfWaveform {
		samples = List.copyOf(samples);
	}

	public double duration() {
		double d = 0;
		for (Sample s : samples) {
			d += s.duration();
		}
		return d;
	}

	public double[] at(double t) {
		double total = duration();
		if (t < 0 || total <= 0 || t >= total) {
			return new double[] { 0, 0 };
		}
		for (Sample s : samples) {
			if (t < s.duration()) {
				double x = t / s.duration();
				return new double[] { value(s.fn(), s.left(), x), value(s.fn(), s.right(), x) };
			}
			t -= s.duration();
		}
		return new double[] { 0, 0 };
	}

	private static double value(Fn f, double a, double x) {
		return switch (f) {
			case CONSTANT -> a;
			case LINEAR_DECREASING -> a * (1 - x);
			case SIN_0_TO_180 -> a * Math.sin(x * Math.PI);
		};
	}
}
