package dev.mcrl.client.sound.ak;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

import dev.mcrl.rlassets.wwise.Fnv;
import dev.mcrl.rlassets.wwise.HircModel;
import dev.mcrl.rlassets.wwise.HircModel.Bus;
import dev.mcrl.rlassets.wwise.HircModel.Meter;
import dev.mcrl.rlassets.wwise.HircModel.Rtpc;
import dev.mcrl.rlassets.wwise.HircModel.StateGroup;
import dev.mcrl.rlassets.wwise.WwiseCurve;

public final class Mix {
	// The loudest RL leaf plays at +6.33 dB.
	private static final double HEADROOM_OFF_DB = 6.4;

	public enum Group { ENGINE, BOOST, TIRES, CAR, BALL }

	public record Settings(boolean dynamics, double master, EnumMap<Group, Double> levels) {
		public static final Settings DEFAULT = new Settings(true, 1, new EnumMap<>(Group.class));

		public double level(Group g) {
			Double v = levels.get(g);
			return v == null ? 1 : v;
		}
	}

	private static final long CAR = Fnv.hash("Car"), BALL = Fnv.hash("Ball"), BOOST = Fnv.hash("Boost"),
			TIRES = Fnv.hash("Tires");
	private static final String ENGINE_EVENT = "Play_OctaneMK2_Car_Engine";

	private final SoundModel m;
	private final Globals g;
	private final @Nullable Bus hdr;
	private final double threshold, ratio, release, below;
	private final List<MeterState> meters = new ArrayList<>();
	private final Long2DoubleOpenHashMap busOwnDb = new Long2DoubleOpenHashMap();
	private final Map<Long, Group> groups = new HashMap<>();
	private double window, reduction;

	private static final class MeterState {
		final long bus;
		final Meter meter;
		double value, held;

		MeterState(long bus, Meter meter) {
			this.bus = bus;
			this.meter = meter;
			this.value = meter.min();
		}
	}

	public Mix(SoundModel m, Globals g) {
		this.m = m;
		this.g = g;
		this.hdr = m.hdrBus();
		busOwnDb.defaultReturnValue(Double.NaN);
		HircModel.Hdr h = hdr == null ? null : hdr.hdr();
		threshold = h == null ? 0 : h.threshold();
		ratio = h == null ? 1 : h.ratio();
		release = h == null ? 0 : h.release();
		below = m.belowHdrDb();
		window = threshold;
		for (Bus b : m.raw().buses().values()) {
			for (Meter mt : b.meters()) {
				meters.add(new MeterState(b.id(), mt));
			}
		}
	}

	private static Group groupOf(long bus, SoundModel m) {
		long engine = engineBus(m);
		for (Bus b : m.busChain(bus)) {
			long id = b.id();
			if (id == engine && engine != 0) {
				return Group.ENGINE;
			}
			if (id == BOOST) {
				return Group.BOOST;
			}
			if (id == TIRES) {
				return Group.TIRES;
			}
			if (id == BALL) {
				return Group.BALL;
			}
			if (id == CAR) {
				return Group.CAR;
			}
		}
		return Group.CAR;
	}

	private static long engineBus(SoundModel m) {
		HircModel.Event e = m.event(Fnv.hash(ENGINE_EVENT));
		if (e != null) {
			for (HircModel.Action a : e.actions()) {
				if (a.type() == HircModel.ActionType.PLAY) {
					return m.outputBus(a.target());
				}
			}
		}
		return 0;
	}

	private Group group(Voice v) {
		return groups.computeIfAbsent(m.outputBus(v.leaf()), b -> groupOf(b, m));
	}

	private double ownDb(Bus b) {
		double cached = busOwnDb.get(b.id());
		if (!Double.isNaN(cached)) {
			return cached;
		}
		Map<String, Double> p = b.props();
		double db = VoiceEval.prop(p, "Volume") + VoiceEval.prop(p, "BusVolume") + VoiceEval.prop(p, "MakeUpGain")
				+ VoiceEval.prop(p, "OutputBusVolume");
		for (Rtpc r : b.rtpcs()) {
			switch (r.param()) {
				case "Volume", "BusVolume", "MakeUpGain", "OutputBusVolume" -> {
					double y = WwiseCurve.eval(r.points(), g.rtpc(r.source()));
					db += r.db() ? WwiseCurve.dbOfY(y) : y;
				}
				default -> {
				}
			}
		}
		for (StateGroup sg : b.states()) {
			Map<String, Double> sp = sg.states().get(g.state(sg.group()));
			if (sp != null) {
				db += VoiceEval.prop(sp, "Volume") + VoiceEval.prop(sp, "BusVolume") + VoiceEval.prop(sp, "MakeUpGain")
						+ VoiceEval.prop(sp, "OutputBusVolume");
			}
		}
		busOwnDb.put(b.id(), db);
		return db;
	}

	private double busDb(Voice v) {
		double db = 0;
		for (Bus b : m.busChain(m.outputBus(v.leaf()))) {
			db += ownDb(b);
		}
		return db;
	}

	private double level(Voice v, VoiceEval.Result r) {
		return r.db() + busDb(v);
	}

	public void frame(double dt, List<Voice> voices, Map<Voice, VoiceEval.Result> eval, Settings s) {
		double step = Math.max(0, Math.min(dt, AkEngine.MAX_DT));
		busOwnDb.clear();
		if (!s.dynamics()) {
			for (MeterState ms : meters) {
				ms.value = ms.meter.min();
				ms.held = 0;
				g.clearRtpc(ms.meter.gameParameter());
			}
			window = threshold;
			reduction = 0;
			return;
		}
		for (MeterState ms : meters) {
			double target = Double.NEGATIVE_INFINITY;
			for (Voice v : voices) {
				VoiceEval.Result r = eval.get(v);
				if (r == null) {
					continue;
				}
				List<Bus> chain = m.busChain(m.outputBus(v.leaf()));
				int at = indexOf(chain, ms.bus);
				if (at < 0) {
					continue;
				}
				double in = r.db();
				for (int i = 0; i < (ms.meter.applyDownstream() ? chain.size() : at); i++) {
					in += ownDb(chain.get(i));
				}
				target = Math.max(target, in + envelope(v));
			}
			if (target == Double.NEGATIVE_INFINITY) {
				target = ms.meter.min();
			}
			ballistics(ms, target, step);
			g.setRtpc(ms.meter.gameParameter(), ms.value);
		}
		busOwnDb.clear();
		if (hdr == null) {
			reduction = 0;
			return;
		}
		double top = threshold;
		for (Voice v : voices) {
			VoiceEval.Result r = eval.get(v);
			if (r == null || !underHdr(v)) {
				continue;
			}
			double rel = v.envelopeTracking() && !v.loop() ? envelope(v) - v.media().peakDb() : 0;
			if (rel < -v.hdrActiveRange()) {
				continue;
			}
			top = Math.max(top, level(v, r) - below + rel);
		}
		window = top >= window ? top : top + (window - top) * (release > 0 ? Math.exp(-step / release) : 0);
		reduction = Math.max(0, window - threshold) * (1 - 1 / ratio);
	}

	private static void ballistics(MeterState ms, double target, double step) {
		Meter mt = ms.meter;
		if (target >= ms.value) {
			ms.held = 0;
			ms.value += (target - ms.value) * k(step, mt.attack());
		} else {
			double before = ms.held;
			ms.held += step;
			double falling = ms.held - Math.max(before, mt.hold());
			if (falling > 0) {
				ms.value += (target - ms.value) * k(falling, mt.release());
			}
		}
		ms.value = Math.max(mt.min(), Math.min(mt.max(), ms.value));
	}

	private static double k(double dt, double tau) {
		return tau <= 0 ? 1 : 1 - Math.exp(-dt / tau);
	}

	private static double envelope(Voice v) {
		HircModel.Media md = v.media();
		float[] env = md.env10ms();
		if (v.loop() || env.length == 0) {
			return md.peakDb();
		}
		int i = (int) Math.floor(v.playPosition() / 0.01);
		return env[Math.max(0, Math.min(env.length - 1, i))];
	}

	private boolean underHdr(Voice v) {
		return hdr != null && indexOf(m.busChain(m.outputBus(v.leaf())), hdr.id()) >= 0;
	}

	private static int indexOf(List<Bus> chain, long bus) {
		for (int i = 0; i < chain.size(); i++) {
			if (chain.get(i).id() == bus) {
				return i;
			}
		}
		return -1;
	}

	public float gain(Voice v, VoiceEval.Result r, Settings s) {
		boolean hdrVoice = s.dynamics() && underHdr(v);
		double ref = hdrVoice ? threshold + below : HEADROOM_OFF_DB;
		double db = level(v, r) - (hdrVoice ? reduction : 0) - ref;
		return (float) (Math.min(1, WwiseCurve.gainOfDb(db)) * s.level(group(v)) * s.master());
	}

	public float pitch(VoiceEval.Result r) {
		return (float) Math.pow(2, r.cents() / 1200);
	}
}
