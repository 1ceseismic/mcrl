package dev.mcrl.client.camera.shake;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.camera.Rot;

public final class ShakePlayer {
	public record Sample(Rot rot, Vec3 loc, double fov) {
		public static final Sample ZERO = new Sample(Rot.ZERO, Vec3.ZERO, 0);
	}

	private static final class Live {
		final int id;
		final ShakeDef def;
		final @Nullable FfWaveform ff;
		final double scale, start;
		final double[] phase = new double[7];
		double stopTime = Double.NaN;

		Live(int id, ShakeDef def, @Nullable FfWaveform ff, double scale, double start) {
			this.id = id;
			this.def = def;
			this.ff = ff;
			this.scale = scale;
			this.start = start;
		}
	}

	private final Random rng = new Random();
	private final List<Live> live = new ArrayList<>();
	private int nextId;

	public int play(ShakeDef d, double scale, double now) {
		return play(d, null, scale, now);
	}

	public int play(ShakeDef d, @Nullable FfWaveform ff, double scale, double now) {
		prune(now);
		Live s = new Live(nextId++, d, ff, scale, now);
		Osc[] ch = channels(d);
		for (int i = 0; i < ch.length; i++) {
			s.phase[i] = ch[i].randomOffset() ? rng.nextDouble() : 0;
		}
		live.add(s);
		return s.id;
	}

	public void stop(int id, double now) {
		for (Live s : live) {
			if (s.id == id && Double.isNaN(s.stopTime)) {
				s.stopTime = now;
			}
		}
	}

	public Sample sample(double now) {
		prune(now);
		if (live.isEmpty()) {
			return Sample.ZERO;
		}
		double pitch = 0, yaw = 0, roll = 0, x = 0, y = 0, z = 0, fov = 0;
		for (Live s : live) {
			if (!shaking(s, now)) {
				continue;
			}
			double t = Math.max(0, now - s.start), w = weight(s, now);
			ShakeDef d = s.def;
			// RL truncates rotation shakes to whole rotator units
			pitch += (int) channel(d.pitch(), w, s, t, 0);
			yaw += (int) channel(d.yaw(), w, s, t, 1);
			roll += (int) channel(d.roll(), w, s, t, 2);
			x += channel(d.x(), w, s, t, 3);
			y += channel(d.y(), w, s, t, 4);
			z += channel(d.z(), w, s, t, 5);
			fov += channel(d.fov(), w, s, t, 6);
		}
		return new Sample(new Rot(pitch, yaw, roll), new Vec3(x, y, z), fov);
	}

	public int[] motors(double now) {
		double left = 0, right = 0;
		for (Live s : live) {
			if (s.ff != null) {
				double[] a = s.ff.at(now - s.start);
				left = Math.max(left, a[0] / 100 * s.scale);
				right = Math.max(right, a[1] / 100 * s.scale);
			}
		}
		return new int[] { motor(left), motor(right) };
	}

	public void clear() {
		live.clear();
	}

	private void prune(double now) {
		live.removeIf(s -> !shaking(s, now) && (s.ff == null || now - s.start >= s.ff.duration()));
	}

	private static boolean shaking(Live s, double now) {
		return s.def.duration() > 0 && now - s.start < s.def.duration() && stopFade(s, now) > 0;
	}

	private static int motor(double v) {
		return (int) Math.round(65535 * Math.max(0, Math.min(1, v)));
	}

	private static double weight(Live s, double now) {
		ShakeDef d = s.def;
		double t = Math.max(0, now - s.start), remaining = Math.max(d.duration() - t, 0);
		double in = d.blendIn() > 0 ? Math.min(t / d.blendIn(), 1) : 1;
		double out = d.duration() > 0 && d.blendOut() > 0 && d.blendOut() > remaining ? remaining / d.blendOut() : 1;
		return Math.min(Math.min(in, out), stopFade(s, now));
	}

	private static double stopFade(Live s, double now) {
		return Double.isNaN(s.stopTime) ? 1 : 1 - Math.min(Math.max(0, now - s.stopTime) / RlShakes.STOP_BLEND_OUT, 1);
	}

	private static double channel(Osc o, double w, Live s, double t, int i) {
		return o.amp() == 0 ? 0 : w * o.amp() * s.scale * Math.sin((t + s.phase[i]) * o.freq());
	}

	private static Osc[] channels(ShakeDef d) {
		return new Osc[] { d.pitch(), d.yaw(), d.roll(), d.x(), d.y(), d.z(), d.fov() };
	}
}
