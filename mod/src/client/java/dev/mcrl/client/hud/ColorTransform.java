package dev.mcrl.client.hud;

record ColorTransform(double mr, double mg, double mb, double ma, double or, double og, double ob, double oa) {
	static final ColorTransform IDENTITY = new ColorTransform(1, 1, 1, 1, 0, 0, 0, 0);

	static ColorTransform add(int rgb) {
		return new ColorTransform(1, 1, 1, 1, rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, 0);
	}

	ColorTransform withAlpha(double a) {
		return new ColorTransform(mr, mg, mb, a, or, og, ob, oa);
	}

	int apply(int argb) {
		return ch(argb >>> 24, ma, oa) << 24 | ch(argb >> 16 & 0xFF, mr, or) << 16 | ch(argb >> 8 & 0xFF, mg, og) << 8
				| ch(argb & 0xFF, mb, ob);
	}

	static ColorTransform lerp(ColorTransform a, ColorTransform b, double t) {
		return new ColorTransform(l(a.mr, b.mr, t), l(a.mg, b.mg, t), l(a.mb, b.mb, t), l(a.ma, b.ma, t),
				l(a.or, b.or, t), l(a.og, b.og, t), l(a.ob, b.ob, t), l(a.oa, b.oa, t));
	}

	private static int ch(int c, double m, double o) {
		return (int) Math.max(0, Math.min(255, Math.floor(c * m + o + 0.5)));
	}

	private static double l(double a, double b, double t) {
		return a + (b - a) * t;
	}
}
