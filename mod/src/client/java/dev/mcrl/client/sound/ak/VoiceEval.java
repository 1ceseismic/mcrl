package dev.mcrl.client.sound.ak;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.rlassets.wwise.HircModel;
import dev.mcrl.rlassets.wwise.HircModel.Accum;
import dev.mcrl.rlassets.wwise.HircModel.Attenuation;
import dev.mcrl.rlassets.wwise.HircModel.Cone;
import dev.mcrl.rlassets.wwise.HircModel.GameParameter;
import dev.mcrl.rlassets.wwise.HircModel.Layer;
import dev.mcrl.rlassets.wwise.HircModel.Node;
import dev.mcrl.rlassets.wwise.HircModel.Positioning;
import dev.mcrl.rlassets.wwise.HircModel.Rtpc;
import dev.mcrl.rlassets.wwise.HircModel.StateGroup;
import dev.mcrl.rlassets.wwise.WwiseCurve;

public final class VoiceEval {

	private VoiceEval() {
	}

	public record Result(double db, double cents, boolean twoD, double distanceUu, double azimuthDeg, double lpf,
			double hpf) {
	}

	public static Result eval(Voice v, SoundModel m, Globals g, Listener l, double now) {
		GameObject o = v.owner();
		Vec3 rel = o.pos().subtract(l.pos());
		double distanceUu = rel.length() * UU_PER_BLOCK, azimuth = azimuth(rel, l);
		Sources src = new Sources(v, m, g, o, distanceUu, azimuth, now);
		Sum s = new Sum();
		double typeBlend = 100;
		boolean typeBlendSet = false;
		Positioning pos = null;
		long attenuation = 0;
		for (Node n : v.chain()) {
			Map<String, Double> p = n.props();
			s.db += prop(p, "Volume") + prop(p, "MakeUpGain") + v.draw(n.id(), "Volume")
					+ v.draw(n.id(), "MakeUpGain");
			s.cents += prop(p, "Pitch") + v.draw(n.id(), "Pitch");
			s.lpf += prop(p, "LPF");
			s.hpf += prop(p, "HPF");
			for (StateGroup sg : n.states()) {
				Map<String, Double> sp = sg.states().get(g.state(sg.group()));
				if (sp != null) {
					s.db += prop(sp, "Volume") + prop(sp, "MakeUpGain");
					s.cents += prop(sp, "Pitch");
					s.lpf += prop(sp, "LPF");
					s.hpf += prop(sp, "HPF");
				}
			}
			for (Rtpc r : n.rtpcs()) {
				double y = WwiseCurve.eval(r.points(), src.value(r.source(), r.sourceType()));
				if (r.param().equals("TypeBlend")) {
					if (r.accum() == Accum.EXCLUSIVE && !typeBlendSet) {
						typeBlend = y;
						typeBlendSet = true;
					}
				} else {
					s.add(r, y);
				}
			}
			if (pos == null && n.positioning() != null) {
				pos = n.positioning();
			}
			if (attenuation == 0 && p.containsKey("AttenuationID")) {
				attenuation = p.get("AttenuationID").longValue();
			}
		}
		List<Long> path = v.path();
		for (int i = 0; i + 1 < path.size(); i++) {
			Node n = m.node(path.get(i));
			if (n == null || n.kind() != HircModel.Kind.LAYER) {
				continue;
			}
			for (Layer layer : n.layers()) {
				List<WwiseCurve.Point> curve = layer.assoc().get(path.get(i + 1));
				if (curve == null) {
					continue;
				}
				double f = WwiseCurve.eval(curve, src.value(layer.crossfadeRtpc(), HircModel.Source.GAME_PARAMETER));
				s.db += f <= 0 ? WwiseCurve.MIN_DB : 20 * Math.log10(f);
				for (Rtpc r : layer.rtpcs()) {
					s.add(r, WwiseCurve.eval(r.points(), src.value(r.source(), r.sourceType())));
				}
			}
		}
		Attenuation a = attenuation == 0 ? null : m.raw().attenuations().get(attenuation);
		if (a != null && pos != null && pos.attenuation()) {
			if (a.volumeDry() != null) {
				double y = WwiseCurve.eval(a.volumeDry().points(), distanceUu);
				s.db += a.volumeDry().db() ? WwiseCurve.dbOfY(y) : y;
			}
			if (a.lpf() != null) {
				s.lpf += WwiseCurve.eval(a.lpf().points(), distanceUu);
			}
			if (a.hpf() != null) {
				s.hpf += WwiseCurve.eval(a.hpf().points(), distanceUu);
			}
			if (a.cone() != null) {
				s.db += cone(a.cone(), o, l);
			}
		}
		double fade = v.fade(now);
		s.db += fade <= 0 ? WwiseCurve.MIN_DB : 20 * Math.log10(fade);
		boolean twoD = pos == null || pos.spatialization() == 0 || typeBlend < 50;
		return new Result(s.db, s.cents, twoD, distanceUu, azimuth, clamp100(s.lpf), clamp100(s.hpf));
	}

	static double prop(Map<String, Double> props, String key) {
		Double v = props.get(key);
		return v == null ? 0 : v;
	}

	private static final class Sum {
		double db, cents, lpf, hpf;

		void add(Rtpc r, double y) {
			switch (r.param()) {
				case "Volume", "MakeUpGain", "OutputBusVolume" -> db += r.db() ? WwiseCurve.dbOfY(y) : y;
				case "Pitch" -> cents += y;
				case "LPF" -> lpf += y;
				case "HPF" -> hpf += y;
				default -> {
				}
			}
		}
	}

	private record Sources(Voice v, SoundModel m, Globals g, GameObject o, double distanceUu, double azimuth, double now) {
		double value(long source, HircModel.Source type) {
			if (type == HircModel.Source.MODULATOR) {
				HircModel.Modulator mod = m.raw().modulators().get(source);
				if (mod == null) {
					return 0;
				}
				double age = now - v.startAt();
				return mod.lfo() ? Modulators.lfo(mod.props(), age)
						: Modulators.envelope(mod.props(), age, v.releasedAt() - v.startAt());
			}
			GameParameter gp = g.parameter(source);
			if (gp != null) {
				switch (gp.builtIn()) {
					case "Distance" -> {
						return distanceUu;
					}
					case "Azimuth" -> {
						return azimuth;
					}
					default -> {
					}
				}
			}
			return o.rtpc(source);
		}
	}

	static double azimuth(Vec3 rel, Listener l) {
		Vec3 up = l.up().normalize();
		Vec3 flat = rel.subtract(up.scale(rel.dot(up)));
		if (flat.lengthSqr() < 1e-18) {
			return 0;
		}
		Vec3 right = l.forward().cross(up);
		double deg = Math.toDegrees(Math.atan2(flat.dot(right), flat.dot(l.forward())));
		return deg <= -180 ? 180 : deg;
	}

	static double cone(Cone c, GameObject o, Listener l) {
		Vec3 toListener = l.pos().subtract(o.pos());
		if (toListener.lengthSqr() < 1e-18) {
			return 0;
		}
		double cos = toListener.normalize().dot(o.forward().normalize());
		double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
		double in = c.insideDeg() / 2, out = c.outsideDeg() / 2;
		if (angle <= in) {
			return 0;
		}
		if (angle >= out || out <= in) {
			return c.outsideVolumeDb();
		}
		return c.outsideVolumeDb() * (angle - in) / (out - in);
	}

	private static double clamp100(double v) {
		return Math.max(0, Math.min(100, v));
	}
}
