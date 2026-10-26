package dev.mcrl.rlassets.wwise;

import dev.mcrl.rlassets.Pcm;

public final class MediaEnvelope {
	public static final double FULL_SCALE = 32768;

	private MediaEnvelope() {
	}

	public record Envelope(double peakDb, float[] env10ms) {
	}

	public static Envelope of(Pcm pcm) {
		int frames = pcm.frames(), ch = pcm.channels(), block = Math.max(1, pcm.rate() / 100);
		short[] s = pcm.samples();
		float[] env = new float[(frames + block - 1) / block];
		int peak = 0;
		for (int b = 0; b < env.length; b++) {
			int max = 0;
			for (int i = b * block * ch, end = Math.min(frames, (b + 1) * block) * ch; i < end; i++) {
				max = Math.max(max, Math.abs((int) s[i]));
			}
			env[b] = (float) db(max);
			peak = Math.max(peak, max);
		}
		return new Envelope(db(peak), env);
	}

	private static double db(int peak) {
		if (peak == 0) {
			return WwiseCurve.MIN_DB;
		}
		double db = Math.max(WwiseCurve.MIN_DB, 20 * Math.log10(peak / FULL_SCALE));
		return Math.round(db * 10) / 10.0;
	}
}
