package dev.mcrl.client.camera.shake;

import java.util.List;

import dev.mcrl.client.camera.shake.FfWaveform.Fn;

public final class RlShakes {
	private RlShakes() {
	}

	// RL's CameraShakes and Car_FXActor values: rotator units, UU, rad/s
	public static final ShakeDef JUMP = new ShakeDef(0.15, 0.05, 0.05, Osc.NONE, Osc.NONE, Osc.NONE, Osc.NONE,
			Osc.NONE, new Osc(5, 30, false), Osc.NONE);
	public static final ShakeDef DODGE = new ShakeDef(0.1, 0.1, 0.05, Osc.NONE, Osc.NONE, Osc.NONE,
			new Osc(5, 30, true), Osc.NONE, Osc.NONE, Osc.NONE);
	public static final ShakeDef IMPACT_MEDIUM = new ShakeDef(0.15, 0.05, 0.05, new Osc(300, 900, false),
			new Osc(150, 600, false), Osc.NONE, new Osc(10, 32, false), Osc.NONE, Osc.NONE, Osc.NONE);
	public static final ShakeDef EXPLOSION_MEDIUM = new ShakeDef(0.25, 0.1, 0.1, new Osc(500, 50, true),
			new Osc(500, 50, true), new Osc(500, 50, true), new Osc(5, 50, true), new Osc(5, 50, true),
			new Osc(5, 50, false), Osc.NONE);
	public static final ShakeDef LAND = new ShakeDef(0, 0.05, 0.05, new Osc(400, 900, true), new Osc(200, 600, true),
			new Osc(400, 1200, true), Osc.NONE, Osc.NONE, new Osc(20, 60, false), Osc.NONE);
	public static final ShakeDef NONE = new ShakeDef(0, 0, 0, Osc.NONE, Osc.NONE, Osc.NONE, Osc.NONE, Osc.NONE,
			Osc.NONE, Osc.NONE);

	public static final FfWaveform JUMP_FF = ff(15, 15, Fn.LINEAR_DECREASING, 0.15);
	public static final FfWaveform DODGE_FF = ff(15, 15, Fn.LINEAR_DECREASING, 0.15);
	public static final FfWaveform LAND_FF = ff(50, 50, Fn.LINEAR_DECREASING, 0.1);
	public static final FfWaveform EXPLOSION_MEDIUM_FF = new FfWaveform(List.of(
			new FfWaveform.Sample(75, 75, Fn.LINEAR_DECREASING, 0.25),
			new FfWaveform.Sample(100, 100, Fn.LINEAR_DECREASING, 0.15)));
	public static final FfWaveform BOOST_ACTIVATED_FF = ff(70, 70, Fn.SIN_0_TO_180, 0.15);
	public static final FfWaveform FLIP_RESET_FF = new FfWaveform(List.of(
			new FfWaveform.Sample(30, 60, Fn.CONSTANT, 0.075), new FfWaveform.Sample(60, 30, Fn.CONSTANT, 0.075)));

	public static final ScaleCurve LAND_SCALE = new ScaleCurve(new double[] { 0, 199, 200, 2200 },
			new double[] { 0, 0, 0.1, 1 });
	public static final ScaleCurve IMPACT_SCALE = new ScaleCurve(new double[] { 0, 200, 201, 4000 },
			new double[] { 0, 0, 0.1, 1 });
	public static final double STOP_BLEND_OUT = 0.2;
	private static final double EXPLOSION_RADIUS = 4096;

	public static double explosionScale(double distanceUu) {
		return Math.max(0, Math.min(1, 1 - distanceUu / EXPLOSION_RADIUS));
	}

	private static FfWaveform ff(double left, double right, Fn fn, double duration) {
		return new FfWaveform(List.of(new FfWaveform.Sample(left, right, fn, duration)));
	}

	public record ScaleCurve(double[] in, double[] out) {
		public double at(double x) {
			if (x <= in[0]) {
				return out[0];
			}
			for (int i = 1; i < in.length; i++) {
				if (x <= in[i]) {
					return out[i - 1] + (out[i] - out[i - 1]) * (x - in[i - 1]) / (in[i] - in[i - 1]);
				}
			}
			return out[out.length - 1];
		}
	}
}
