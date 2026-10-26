package dev.mcrl.client.render.look;

public final class RlDisplay {
	private RlDisplay() {
	}

	public static double srgbDecode(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	public static double srgbEncode(double c) {
		return c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
	}
}
