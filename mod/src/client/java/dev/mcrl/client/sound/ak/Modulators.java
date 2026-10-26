package dev.mcrl.client.sound.ak;

import java.util.Map;

public final class Modulators {
	private Modulators() {
	}

	public static double lfo(Map<String, Double> props, double t) {
		double f = props.getOrDefault("Lfo_Frequency", 1.0);
		double phase = f * Math.max(0, t) + props.getOrDefault("Lfo_InitialPhase", 0.0) / 360;
		phase -= Math.floor(phase);
		double w = switch ((int) Math.round(props.getOrDefault("Lfo_Waveform", 0.0))) {
			case 1 -> phase < 0.5 ? 4 * phase - 1 : 3 - 4 * phase;
			case 2 -> phase < props.getOrDefault("Lfo_PWM", 50.0) / 100 ? 1 : -1;
			case 3 -> 2 * phase - 1;
			case 4 -> 1 - 2 * phase;
			default -> Math.sin(2 * Math.PI * phase);
		};
		double d = props.getOrDefault("Lfo_Depth", 100.0) / 100;
		double attack = props.getOrDefault("Lfo_Attack", 0.0);
		if (attack > 0) {
			d *= Math.min(1, Math.max(0, t) / attack);
		}
		return 1 - d * (1 - w) / 2;
	}

	public static double envelope(Map<String, Double> props, double t, double releasedAt) {
		double a = props.getOrDefault("Envelope_AttackTime", 0.0), d = props.getOrDefault("Envelope_DecayTime", 0.0);
		double s = props.getOrDefault("Envelope_SustainLevel", 100.0) / 100;
		double r = props.getOrDefault("Envelope_ReleaseTime", 0.0);
		double hold = props.getOrDefault("Envelope_SustainTime", 0.0);
		double release = hold > 0 ? Math.min(releasedAt, a + d + hold) : releasedAt;
		if (t < release) {
			return adsr(t, a, d, s);
		}
		double from = adsr(release, a, d, s);
		return r <= 0 ? 0 : Math.max(0, from * (1 - (t - release) / r));
	}

	private static double adsr(double t, double a, double d, double s) {
		if (t < a) {
			return Math.max(0, t) / a;
		}
		if (t < a + d) {
			return 1 - (1 - s) * (t - a) / d;
		}
		return s;
	}
}
