package dev.mcrl.rlassets;

public final class PcmOps {
	private PcmOps() {
	}

	public static Pcm toMono(Pcm in) {
		if (in.channels() == 1) {
			return in;
		}
		int n = in.frames(), c = in.channels();
		short[] out = new short[n];
		for (int i = 0; i < n; i++) {
			int sum = 0;
			for (int k = 0; k < c; k++) {
				sum += in.samples()[i * c + k];
			}
			out[i] = (short) Math.floorDiv(sum, c);
		}
		return new Pcm(in.rate(), 1, out);
	}

	// Wwise channel order: L, R, C, LFE, rest.
	public static Pcm toStereo(Pcm in) {
		int c = in.channels();
		if (c <= 2) {
			return in;
		}
		double[] l = new double[c], r = new double[c];
		l[0] = 1;
		r[1] = 1;
		int next = 2;
		if (c == 3 || c >= 5) {
			l[2] = r[2] = Math.sqrt(0.5);
			next = 3;
		}
		if (c >= 6) {
			next = 4; // skip the LFE
		}
		for (int k = next, side = 0; k < c; k++, side ^= 1) {
			(side == 0 ? l : r)[k] = 1;
		}
		double lw = 0, rw = 0;
		for (int k = 0; k < c; k++) {
			lw += l[k];
			rw += r[k];
		}
		int n = in.frames();
		short[] out = new short[n * 2];
		for (int i = 0; i < n; i++) {
			double a = 0, b = 0;
			for (int k = 0; k < c; k++) {
				short x = in.samples()[i * c + k];
				a += l[k] * x;
				b += r[k] * x;
			}
			out[2 * i] = (short) Math.round(a / lw);
			out[2 * i + 1] = (short) Math.round(b / rw);
		}
		return new Pcm(in.rate(), 2, out);
	}

	public static Pcm loopCrossfade(Pcm in, int ms) {
		short[] x = in.samples();
		int c = in.channels(), n = in.frames();
		int l = (int) ((long) in.rate() * ms / 1000);
		if (l <= 0 || 2 * l > n) {
			return in;
		}
		short[] out = new short[(n - l) * c];
		System.arraycopy(x, l * c, out, 0, (n - l) * c);
		for (int i = 0; i < l; i++) {
			double w = (double) i / l;
			for (int k = 0; k < c; k++) {
				out[(n - 2 * l + i) * c + k] = clamp(Math.round(x[(n - l + i) * c + k] * (1 - w) + x[i * c + k] * w));
			}
		}
		return new Pcm(in.rate(), c, out);
	}

	private static short clamp(long v) {
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
	}
}
