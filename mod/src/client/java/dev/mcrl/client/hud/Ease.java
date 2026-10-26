package dev.mcrl.client.hud;

final class Ease {
	private Ease() {
	}

	static double quadOut(double x) {
		double r = 1 - clamp01(x);
		return 1 - r * r;
	}

	static double strongOut(double x) {
		double r = 1 - clamp01(x);
		return 1 - r * r * r * r * r;
	}

	private static double clamp01(double x) {
		return x <= 0 ? 0 : x >= 1 ? 1 : x;
	}
}
