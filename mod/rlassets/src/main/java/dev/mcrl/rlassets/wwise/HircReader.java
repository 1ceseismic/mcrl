package dev.mcrl.rlassets.wwise;

import dev.mcrl.rlassets.wwise.HircModel.Accum;
import dev.mcrl.rlassets.wwise.HircModel.Action;
import dev.mcrl.rlassets.wwise.HircModel.ActionType;
import dev.mcrl.rlassets.wwise.HircModel.Attenuation;
import dev.mcrl.rlassets.wwise.HircModel.Bus;
import dev.mcrl.rlassets.wwise.HircModel.Cone;
import dev.mcrl.rlassets.wwise.HircModel.Curve;
import dev.mcrl.rlassets.wwise.HircModel.Event;
import dev.mcrl.rlassets.wwise.HircModel.GameParameter;
import dev.mcrl.rlassets.wwise.HircModel.Hdr;
import dev.mcrl.rlassets.wwise.HircModel.Kind;
import dev.mcrl.rlassets.wwise.HircModel.Layer;
import dev.mcrl.rlassets.wwise.HircModel.Limit;
import dev.mcrl.rlassets.wwise.HircModel.Meter;
import dev.mcrl.rlassets.wwise.HircModel.Modulator;
import dev.mcrl.rlassets.wwise.HircModel.Node;
import dev.mcrl.rlassets.wwise.HircModel.Positioning;
import dev.mcrl.rlassets.wwise.HircModel.RanSeq;
import dev.mcrl.rlassets.wwise.HircModel.Rtpc;
import dev.mcrl.rlassets.wwise.HircModel.Source;
import dev.mcrl.rlassets.wwise.HircModel.StateGroup;
import dev.mcrl.rlassets.wwise.HircModel.Switch;
import dev.mcrl.rlassets.wwise.HircModel.SwitchMap;
import dev.mcrl.rlassets.wwise.WwiseCurve.Point;
import dev.mcrl.rlassets.wwise.WwiseCurve.Shape;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class HircReader {
	public static final int VERSION = 150; // Wwise 2023.1, RL's banks

	// Wwise's AkPropID order.
	private static final String[] PROPS = {"Volume", "Pitch", "LPF", "HPF", "BusVolume", "MakeUpGain", "Priority",
			"MuteRatio", "UserAuxSendVolume0", "UserAuxSendVolume1", "UserAuxSendVolume2", "UserAuxSendVolume3",
			"GameAuxSendVolume", "OutputBusVolume", "OutputBusHPF", "OutputBusLPF", "UserAuxSendLPF0", "UserAuxSendLPF1",
			"UserAuxSendLPF2", "UserAuxSendLPF3", "UserAuxSendHPF0", "UserAuxSendHPF1", "UserAuxSendHPF2",
			"UserAuxSendHPF3", "GameAuxSendLPF", "GameAuxSendHPF", "ReflectionBusVolume", "HDRBusThreshold",
			"HDRBusRatio", "HDRBusReleaseTime", "HDRActiveRange", "MidiTransposition", "MidiVelocityOffset",
			"PlaybackSpeed", "InitialDelay", "Pan_X_2D", "Pan_Y_2D", "Pan_Z_2D", "Pan_X_3D", "Pan_Y_3D", "Pan_Z_3D",
			"CenterPercent", "TypeBlend", "EnableAttenuation", "Cone_AttenuationOnOff", "Cone_Attenuation", "Cone_LPF",
			"Cone_HPF", "BypassFX", "BypassAllFX", "Available_0", "Available_1", "Available_2", "MaxNumInstances",
			"BypassAllMetadata", "PlayMechanismSpecialTransitionsValue", "PriorityDistanceOffset", "DelayTime",
			"TransitionTime", "Probability", "DialogueMode", "HDRBusGameParam", "HDRBusGameParamMin",
			"HDRBusGameParamMax", "LoopStart", "LoopEnd", "TrimInTime", "TrimOutTime", "FadeInTime", "FadeOutTime",
			"FadeInCurve", "FadeOutCurve", "LoopCrossfadeDuration", "CrossfadeUpCurve", "CrossfadeDownCurve",
			"MidiTrackingRootNote", "MidiPlayOnNoteType", "MidiKeyRangeMin", "MidiKeyRangeMax", "MidiVelocityRangeMin",
			"MidiVelocityRangeMax", "MidiChannelMask", "MidiTempoSource", "MidiTargetNode", "Loop", "AttenuationID"};
	private static final int HDR_BUS_GAME_PARAM = 0x3D, MIDI_TARGET_NODE = 0x53, ATTENUATION_ID = 0x55;
	private static final String[] MODULATOR_PROPS = {"Scope", "Envelope_StopPlayback", "Lfo_Depth", "Lfo_Attack",
			"Lfo_Frequency", "Lfo_Waveform", "Lfo_Smoothing", "Lfo_PWM", "Lfo_InitialPhase", "Lfo_Retrigger",
			"Envelope_AttackTime", "Envelope_AttackCurve", "Envelope_DecayTime", "Envelope_SustainLevel",
			"Envelope_SustainTime", "Envelope_ReleaseTime", "Envelope_TriggerOn", "Time_Duration", "Time_Loops",
			"Time_PlaybackRate", "Time_InitialDelay"};
	private static final String[] MODULATOR_PARAMS = {"ModulatorLfoDepth", "ModulatorLfoAttack",
			"ModulatorLfoFrequency", "ModulatorLfoWaveform", "ModulatorLfoSmoothing", "ModulatorLfoPWM",
			"ModulatorLfoInitialPhase", "ModulatorLfoRetrigger", "ModulatorEnvelopeAttackTime",
			"ModulatorEnvelopeAttackCurve", "ModulatorEnvelopeDecayTime", "ModulatorEnvelopeSustainLevel",
			"ModulatorEnvelopeSustainTime", "ModulatorEnvelopeReleaseTime", "ModulatorTimePlaybackSpeed",
			"ModulatorTimeInitialDelay"};
	private static final String[] RAMPS = {"None", "SlewRate", "FilteringOverTime"};
	private static final String[] BUILT_INS = {"None", "Distance", "Azimuth", "Elevation", "EmitterCone", "Obstruction",
			"Occlusion", "ListenerCone", "Diffraction", "TransmissionLoss"};
	private static final String[] TRANSITIONS = {"Disabled", "CrossFadeAmp", "CrossFadePower", "Delay",
			"SampleAccurate", "TriggerRate"};

	private static final int SOUND = 0x02, ACTION = 0x03, EVENT = 0x04, RAN_SEQ = 0x05, SWITCH = 0x06,
			ACTOR_MIXER = 0x07, BUS = 0x08, LAYER = 0x09, ATTENUATION = 0x0E, FX_SHARE_SET = 0x10, FX_CUSTOM = 0x11,
			AUX_BUS = 0x12, LFO = 0x13, ENVELOPE = 0x14;
	private static final long METER_PLUGIN = 0x00810003L;
	private static final int BKHD = tag("BKHD"), HIRC = tag("HIRC"), DIDX = tag("DIDX"), STMG = tag("STMG");

	private HircReader() {
	}

	public record Bank(long id, int version, List<Object> objects, @Nullable Stmg stmg, Map<Long, Integer> didx) {
	}

	public record Stmg(Map<Long, GameParameter> gameParameters, Map<Long, SwitchMap> switchFromRtpc) {
	}

	public record FxMeter(long id, Meter meter) {
	}

	public record ActionRef(long id, Action action) {
	}

	public record EventRef(long id, List<Long> actionIds) {
	}

	public record BusRef(Bus bus, List<Long> fxIds) {
	}

	public static final class HircFormatException extends IOException {
		private final long objectId;
		private final int type;
		private final long expected;
		private final long consumed;

		public HircFormatException(long objectId, int type, long expected, long consumed) {
			this("", objectId, type, expected, consumed, null);
		}

		HircFormatException(String source, long objectId, int type, long expected, long consumed,
				@Nullable String detail) {
			super((source.isEmpty() ? "" : source + ": ") + "HIRC object " + objectId + " (type 0x"
					+ String.format("%02X", type) + ")" + (detail == null ? "" : ": " + detail)
					+ (expected < 0 ? "" : ": declared " + expected + " bytes, parsed " + consumed));
			this.objectId = objectId;
			this.type = type;
			this.expected = expected;
			this.consumed = consumed;
		}

		public long objectId() {
			return objectId;
		}

		public int type() {
			return type;
		}

		public long expected() {
			return expected;
		}

		public long consumed() {
			return consumed;
		}
	}

	public static String propName(int id) {
		return name(PROPS, id);
	}

	public static HircModel merge(List<Bank> banks) {
		Map<Long, Action> actions = new LinkedHashMap<>();
		Map<Long, EventRef> eventRefs = new LinkedHashMap<>();
		Map<Long, Node> nodes = new LinkedHashMap<>();
		Map<Long, BusRef> busRefs = new LinkedHashMap<>();
		Map<Long, Attenuation> attenuations = new LinkedHashMap<>();
		Map<Long, Modulator> modulators = new LinkedHashMap<>();
		Map<Long, Meter> meters = new LinkedHashMap<>();
		Map<Long, GameParameter> gameParameters = new LinkedHashMap<>();
		Map<Long, SwitchMap> switchFromRtpc = new LinkedHashMap<>();
		for (Bank bank : banks) {
			for (Object o : bank.objects()) {
				switch (o) {
					case ActionRef a -> actions.putIfAbsent(a.id(), a.action());
					case EventRef r -> eventRefs.putIfAbsent(r.id(), r);
					case Node n -> nodes.putIfAbsent(n.id(), n);
					case BusRef r -> busRefs.putIfAbsent(r.bus().id(), r);
					case Attenuation a -> attenuations.putIfAbsent(a.id(), a);
					case Modulator m -> modulators.putIfAbsent(m.id(), m);
					case FxMeter f -> meters.putIfAbsent(f.id(), f.meter());
					default -> throw new IllegalArgumentException("not a bank object: " + o);
				}
			}
			if (bank.stmg() != null) {
				bank.stmg().gameParameters().forEach(gameParameters::putIfAbsent);
				bank.stmg().switchFromRtpc().forEach(switchFromRtpc::putIfAbsent);
			}
		}
		Map<Long, Event> events = new LinkedHashMap<>();
		for (EventRef r : eventRefs.values()) {
			List<Action> list = new ArrayList<>();
			for (long a : r.actionIds()) {
				if (actions.containsKey(a)) {
					list.add(actions.get(a));
				}
			}
			events.put(r.id(), new Event(r.id(), List.copyOf(list)));
		}
		Map<Long, Bus> buses = new LinkedHashMap<>();
		for (BusRef r : busRefs.values()) {
			List<Meter> list = new ArrayList<>();
			for (long fx : r.fxIds()) {
				if (meters.containsKey(fx)) {
					list.add(meters.get(fx));
				}
			}
			Bus b = r.bus();
			buses.put(b.id(), new Bus(b.id(), b.parent(), b.props(), b.rtpcs(), b.states(), b.hdr(), List.copyOf(list)));
		}
		return new HircModel(frozen(events), frozen(nodes), frozen(buses), frozen(attenuations), frozen(modulators),
				frozen(gameParameters), frozen(switchFromRtpc), Map.of());
	}

	public static Bank read(Path bnk) throws IOException {
		String src = bnk.getFileName().toString();
		try (FileChannel ch = FileChannel.open(bnk, StandardOpenOption.READ)) {
			long len = ch.size(), pos = 0, bankId = -1;
			List<Object> objects = new ArrayList<>();
			Map<Long, Integer> didx = new LinkedHashMap<>();
			Stmg stmg = null;
			while (pos < len) {
				if (len - pos < 8) {
					throw new IOException(src + ": truncated chunk header at " + pos);
				}
				ByteBuffer head = readAt(ch, pos, 8);
				int tag = head.getInt(0);
				long size = head.getInt(4) & 0xFFFFFFFFL;
				if (size > len - pos - 8 || size > Integer.MAX_VALUE) {
					throw new IOException(src + ": chunk " + tagName(tag) + " at " + pos + " runs past the end");
				}
				if (bankId < 0 && tag != BKHD) {
					throw new IOException(src + ": not a Wwise bank (no BKHD first)");
				}
				if (tag == BKHD || tag == HIRC || tag == DIDX || tag == STMG) {
					BankBytes b = BankBytes.chunk(readAt(ch, pos + 8, (int) size), src, tagName(tag));
					if (tag == BKHD) {
						if (bankId < 0) {
							bankId = header(b, src);
						}
					} else if (tag == HIRC) {
						hirc(b, objects);
					} else if (tag == DIDX) {
						mediaIndex(b, didx);
					} else if (stmg == null) {
						stmg = globalSettings(b);
					}
				}
				pos += 8 + size;
			}
			if (bankId < 0) {
				throw new IOException(src + ": empty, not a Wwise bank");
			}
			return new Bank(bankId, VERSION, Collections.unmodifiableList(objects), stmg,
					Collections.unmodifiableMap(didx));
		}
	}

	private static ByteBuffer readAt(FileChannel ch, long pos, int n) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
		while (buf.hasRemaining()) {
			if (ch.read(buf, pos + buf.position()) < 0) {
				throw new IOException("unexpected end of file at " + (pos + buf.position()));
			}
		}
		return buf.flip();
	}

	private static long header(BankBytes b, String src) throws IOException {
		long version = b.u32();
		if (version != VERSION) {
			throw new IOException(src + ": Wwise bank version " + version + ", mcrl reads " + VERSION);
		}
		return b.u32();
	}

	private static void mediaIndex(BankBytes b, Map<Long, Integer> didx) throws IOException {
		if (b.remaining() % 12 != 0) {
			throw b.error(b.remaining() + " bytes, not 12 per media");
		}
		for (int i = 1; b.remaining() > 0; i++) {
			long id = b.u32();
			b.skip(8);
			didx.putIfAbsent(id, i);
		}
	}

	private static void hirc(BankBytes b, List<Object> objects) throws IOException {
		long count = b.u32();
		for (long i = 0; i < count; i++) {
			int type = b.u8();
			long size = b.u32();
			if (size > b.remaining()) {
				throw b.error("object " + i + " (type 0x" + Integer.toHexString(type) + ", " + size
						+ " bytes) runs past the chunk");
			}
			Object parsed = object(b.object((int) size, type), type);
			if (parsed != null) {
				objects.add(parsed);
			}
		}
		b.end();
	}

	private static Stmg globalSettings(BankBytes b) throws IOException {
		b.u16();
		b.f32();
		b.u16();
		b.u16();
		long stateGroups = b.u32();
		for (long i = 0; i < stateGroups; i++) {
			b.u32();
			b.u32();
			b.skip(12 * b.u32());
		}
		long switchGroups = b.u32();
		Map<Long, SwitchMap> switches = new LinkedHashMap<>();
		for (long i = 0; i < switchGroups; i++) {
			long group = b.u32(), rtpc = b.u32();
			b.u8();
			long n = b.u32();
			List<double[]> points = new ArrayList<>();
			for (long k = 0; k < n; k++) {
				double x = b.f32();
				long value = b.u32();
				b.u32();
				points.add(new double[] {x, value});
			}
			switches.putIfAbsent(group, new SwitchMap(group, rtpc, List.copyOf(points)));
		}
		long params = b.u32();
		Map<Long, GameParameter> gameParameters = new LinkedHashMap<>();
		for (long i = 0; i < params; i++) {
			long id = b.u32();
			double value = b.f32();
			String ramp = label(RAMPS, b.u32());
			double up = b.f32(), down = b.f32();
			String builtIn = label(BUILT_INS, b.u8());
			gameParameters.putIfAbsent(id, new GameParameter(id, value, ramp, up, down, builtIn));
		}
		b.skip(28 * b.u32());
		b.end();
		return new Stmg(frozen(gameParameters), frozen(switches));
	}

	private static @Nullable Object object(BankBytes b, int type) throws IOException {
		Object o;
		switch (type) {
			case SOUND -> o = sound(b);
			case ACTION -> o = new ActionRef(b.id(), action(b));
			case EVENT -> o = event(b);
			case RAN_SEQ, SWITCH, ACTOR_MIXER, LAYER -> o = container(b, type);
			case BUS, AUX_BUS -> o = bus(b);
			case ATTENUATION -> o = attenuation(b);
			case FX_SHARE_SET, FX_CUSTOM -> o = fx(b);
			case LFO, ENVELOPE -> o = modulator(b, type == LFO);
			default -> {
				return null;
			}
		}
		b.end();
		return o;
	}

	private static Node sound(BankBytes b) throws IOException {
		long id = b.u32();
		long plugin = b.u32();
		b.u8();
		long media = b.u32();
		b.u32();
		b.u8();
		if ((plugin & 0x0F) == 2) {
			b.skip(b.u32());
		}
		return nodeBase(b).node(id, Kind.SOUND, media, null, null, List.of(), List.of());
	}

	private static Node container(BankBytes b, int type) throws IOException {
		long id = b.u32();
		Base base = nodeBase(b);
		return switch (type) {
			case RAN_SEQ -> {
				int loopCount = b.u16();
				b.u16();
				b.u16();
				double transitionMs = b.f32();
				b.f32();
				b.f32();
				int avoidRepeat = b.u16();
				String transition = label(TRANSITIONS, b.u8());
				boolean shuffle = b.u8() == 1;
				boolean sequence = b.u8() == 1;
				boolean continuous = (b.u8() & 0x08) != 0;
				List<Long> children = children(b);
				int n = b.u16();
				List<Long> playlist = new ArrayList<>();
				List<Integer> weights = new ArrayList<>();
				for (int i = 0; i < n; i++) {
					playlist.add(b.u32());
					weights.add(b.s32());
				}
				RanSeq r = new RanSeq(sequence, shuffle, avoidRepeat, loopCount, continuous, transition, transitionMs,
						List.copyOf(playlist), List.copyOf(weights));
				yield base.node(id, Kind.RAN_SEQ, 0, r, null, List.of(), children);
			}
			case SWITCH -> {
				boolean stateGroup = b.u8() == 1;
				long group = b.u32(), defaultValue = b.u32();
				b.u8();
				List<Long> children = children(b);
				long n = b.u32();
				Map<Long, List<Long>> cases = new LinkedHashMap<>();
				for (long i = 0; i < n; i++) {
					long value = b.u32();
					cases.put(value, ids(b, b.u32()));
				}
				long params = b.u32();
				for (long i = 0; i < params; i++) {
					b.u32();
					b.u8();
					b.u8();
					b.s32();
					b.s32();
				}
				Switch sw = new Switch(group, stateGroup, defaultValue, frozen(cases));
				yield base.node(id, Kind.SWITCH, 0, null, sw, List.of(), children);
			}
			case LAYER -> {
				List<Long> children = children(b);
				long n = b.u32();
				List<Layer> layers = new ArrayList<>();
				for (long i = 0; i < n; i++) {
					b.u32();
					List<Rtpc> rtpcs = rtpcs(b, false);
					long crossfade = b.u32();
					b.u8();
					long assocs = b.u32();
					Map<Long, List<Point>> assoc = new LinkedHashMap<>();
					for (long k = 0; k < assocs; k++) {
						long child = b.u32();
						assoc.put(child, points(b, b.u32()));
					}
					layers.add(new Layer(crossfade, frozen(assoc), rtpcs));
				}
				b.u8();
				yield base.node(id, Kind.LAYER, 0, null, null, List.copyOf(layers), children);
			}
			default -> base.node(id, Kind.ACTOR_MIXER, 0, null, null, List.of(), children(b));
		};
	}

	private record Base(long bus, long parent, Map<String, Double> props, Map<String, double[]> random,
			@Nullable Positioning positioning, Limit limit, @Nullable Boolean hdrEnvelope, List<StateGroup> states,
			List<Rtpc> rtpcs) {
		Node node(long id, Kind kind, long media, @Nullable RanSeq ranSeq, @Nullable Switch sw, List<Layer> layers,
				List<Long> children) {
			return new Node(id, kind, parent, bus, props, random, rtpcs, states, positioning, limit, hdrEnvelope, media,
					ranSeq, sw, layers, children);
		}
	}

	private static Base nodeBase(BankBytes b) throws IOException {
		b.u8();
		effects(b);
		b.u8();
		metadata(b);
		long bus = b.u32(), parent = b.u32();
		b.u8();
		Map<String, Double> props = props(b, false);
		Map<String, double[]> random = ranged(b, false);
		Positioning positioning = positioning(b);
		aux(b);
		int limitBits = b.u8();
		b.u8();
		int max = b.u16();
		b.u8();
		int hdrBits = b.u8();
		Limit limit = new Limit(max, (limitBits & 0x01) != 0, (limitBits & 0x08) != 0);
		Boolean hdrEnvelope = (hdrBits & 0x01) != 0 ? Boolean.valueOf((hdrBits & 0x08) != 0) : null;
		List<StateGroup> states = states(b);
		List<Rtpc> rtpcs = rtpcs(b, false);
		return new Base(bus, parent, props, random, positioning, limit, hdrEnvelope, states, rtpcs);
	}

	private static List<Long> effects(BankBytes b) throws IOException {
		int n = b.u8();
		if (n == 0) {
			return List.of();
		}
		b.u8();
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			b.u8();
			ids.add(b.u32());
			b.u8();
		}
		return List.copyOf(ids);
	}

	private static void metadata(BankBytes b) throws IOException {
		b.skip(6L * b.u8());
	}

	private static void aux(BankBytes b) throws IOException {
		if ((b.u8() & 0x08) != 0) {
			b.skip(16);
		}
		b.u32();
	}

	private static @Nullable Positioning positioning(BankBytes b) throws IOException {
		int bits = b.u8();
		if ((bits & 0x01) == 0) {
			return null;
		}
		if ((bits & 0x02) == 0) {
			return new Positioning(0, false);
		}
		int bits3d = b.u8();
		if ((bits >> 5 & 0x03) != 0) {
			b.u8();
			b.s32();
			b.skip(16 * b.u32());
			long items = b.u32();
			b.skip(8 * items);
			b.skip(12 * items);
		}
		return new Positioning(bits3d & 0x03, (bits3d & 0x08) != 0);
	}

	private static List<StateGroup> states(BankBytes b) throws IOException {
		long props = b.var();
		for (long i = 0; i < props; i++) {
			b.var();
			b.u8();
			b.u8();
		}
		long groups = b.var();
		List<StateGroup> out = new ArrayList<>();
		for (long i = 0; i < groups; i++) {
			long group = b.u32();
			b.u8();
			long n = b.var();
			Map<Long, Map<String, Double>> states = new LinkedHashMap<>();
			for (long k = 0; k < n; k++) {
				long state = b.u32();
				int count = b.u16();
				int[] ids = new int[count];
				for (int j = 0; j < count; j++) {
					ids[j] = b.u16();
				}
				Map<String, Double> values = new LinkedHashMap<>();
				for (int j = 0; j < count; j++) {
					values.put(propName(ids[j]), (double) b.f32());
				}
				states.put(state, frozen(values));
			}
			out.add(new StateGroup(group, frozen(states)));
		}
		return List.copyOf(out);
	}

	private static List<Rtpc> rtpcs(BankBytes b, boolean modulator) throws IOException {
		int n = b.u16();
		List<Rtpc> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			long source = b.u32();
			int type = b.u8();
			int accum = b.u8();
			long param = b.var();
			b.u32();
			int scaling = b.u8();
			List<Point> points = points(b, b.u16());
			if (accum >= Accum.values().length) {
				throw b.error("RTPC accumulation " + accum);
			}
			Source sourceType = type == 0 ? Source.GAME_PARAMETER : type == 4 ? Source.MODULATOR : Source.OTHER;
			String name = name(modulator ? MODULATOR_PARAMS : PROPS, param);
			out.add(new Rtpc(source, sourceType, name, scaling == 2, Accum.values()[accum], points));
		}
		return List.copyOf(out);
	}

	private static List<Point> points(BankBytes b, long n) throws IOException {
		List<Point> out = new ArrayList<>();
		for (long i = 0; i < n; i++) {
			double x = b.f32(), y = b.f32();
			out.add(new Point(x, y, shape(b, b.u32())));
		}
		return List.copyOf(out);
	}

	private static Shape shape(BankBytes b, long value) throws IOException {
		if (value > 9) {
			throw b.error("curve shape " + value);
		}
		return Shape.of((int) value);
	}

	private static Map<String, Double> props(BankBytes b, boolean modulator) throws IOException {
		int[] ids = propIds(b);
		Map<String, Double> out = new LinkedHashMap<>();
		for (int id : ids) {
			boolean isId = !modulator && (id == HDR_BUS_GAME_PARAM || id == MIDI_TARGET_NODE || id == ATTENUATION_ID);
			out.put(name(modulator ? MODULATOR_PROPS : PROPS, id), union(b, isId));
		}
		return frozen(out);
	}

	private static Map<String, double[]> ranged(BankBytes b, boolean modulator) throws IOException {
		int[] ids = propIds(b);
		Map<String, double[]> out = new LinkedHashMap<>();
		for (int id : ids) {
			double min = union(b, false), max = union(b, false);
			out.put(name(modulator ? MODULATOR_PROPS : PROPS, id), new double[] {min, max});
		}
		return frozen(out);
	}

	private static int[] propIds(BankBytes b) throws IOException {
		int[] ids = new int[b.u8()];
		for (int i = 0; i < ids.length; i++) {
			ids[i] = b.u8();
		}
		return ids;
	}

	private static double union(BankBytes b, boolean id) throws IOException {
		long raw = b.u32();
		return id || raw <= 0x10000000L ? raw : Float.intBitsToFloat((int) raw);
	}

	private static List<Long> children(BankBytes b) throws IOException {
		return ids(b, b.u32());
	}

	private static List<Long> ids(BankBytes b, long n) throws IOException {
		List<Long> out = new ArrayList<>();
		for (long i = 0; i < n; i++) {
			out.add(b.u32());
		}
		return List.copyOf(out);
	}

	private static Action action(BankBytes b) throws IOException {
		b.u32();
		int actionType = b.u16();
		long target = b.u32();
		b.u8();
		Map<String, Double> props = props(b, false);
		ranged(b, false);
		Shape fade = actionParams(b, actionType);
		ActionType type = switch (actionType & 0xFF00) {
			case 0x0400 -> ActionType.PLAY;
			case 0x0100 -> ActionType.STOP;
			default -> ActionType.OTHER;
		};
		return new Action(type, target, props.getOrDefault("DelayTime", 0.0), props.getOrDefault("TransitionTime", 0.0),
				fade);
	}

	private static Shape actionParams(BankBytes b, int actionType) throws IOException {
		switch (actionType & 0xFF00) {
			case 0x0400, 0x0500, 0x2300 -> {
				Shape fade = fadeCurve(b);
				b.u32();
				b.u32();
				return fade;
			}
			case 0x0100, 0x0200, 0x0300 -> {
				Shape fade = fadeCurve(b);
				b.u8();
				exceptions(b);
				return fade;
			}
			case 0x0600, 0x0700, 0x2200 -> {
				Shape fade = fadeCurve(b);
				exceptions(b);
				return fade;
			}
			case 0x0800, 0x0900, 0x0A00, 0x0B00, 0x0C00, 0x0D00, 0x0E00, 0x0F00, 0x2000, 0x3000 -> {
				Shape fade = fadeCurve(b);
				b.u8();
				b.skip(12);
				exceptions(b);
				return fade;
			}
			case 0x1300, 0x1400 -> {
				Shape fade = fadeCurve(b);
				b.u8();
				b.u8();
				b.skip(12);
				exceptions(b);
				return fade;
			}
			case 0x1200, 0x1900 -> b.skip(8);
			case 0x1E00 -> {
				b.u8();
				b.skip(12);
				b.u8();
				exceptions(b);
			}
			case 0x3100, 0x3200 -> {
				b.u8();
				b.u8();
				b.u32();
				b.u8();
				exceptions(b);
			}
			case 0x3300, 0x3400, 0x3500, 0x3600, 0x3700 -> {
				b.u8();
				b.u8();
				exceptions(b);
			}
			case 0x1000, 0x1100, 0x1500, 0x1600, 0x1700, 0x1A00, 0x1B00, 0x1C00, 0x1D00, 0x1F00, 0x2100 -> {
			}
			default -> throw b.error(String.format("unknown action type 0x%04X", actionType));
		}
		return Shape.LINEAR;
	}

	private static Shape fadeCurve(BankBytes b) throws IOException {
		return shape(b, b.u8() & 0x1F);
	}

	private static void exceptions(BankBytes b) throws IOException {
		b.skip(5 * b.var());
	}

	private static EventRef event(BankBytes b) throws IOException {
		long id = b.u32();
		return new EventRef(id, ids(b, b.var()));
	}

	private static BusRef bus(BankBytes b) throws IOException {
		long id = b.u32();
		long parent = b.u32();
		if (parent == 0) {
			b.u32();
		}
		Map<String, Double> props = props(b, false);
		positioning(b);
		aux(b);
		b.u8();
		b.u16();
		b.u32();
		int hdrBits = b.u8();
		b.s32();
		b.f32();
		b.skip(18 * b.u32());
		List<Long> fx = effects(b);
		metadata(b);
		List<Rtpc> rtpcs = rtpcs(b, false);
		List<StateGroup> states = states(b);
		Hdr hdr = (hdrBits & 0x01) == 0 ? null : new Hdr(props.getOrDefault("HDRBusThreshold", 0.0),
				props.getOrDefault("HDRBusRatio", 1.0), props.getOrDefault("HDRBusReleaseTime", 0.0),
				(hdrBits & 0x02) != 0);
		return new BusRef(new Bus(id, parent, props, rtpcs, states, hdr, List.of()), fx);
	}

	private static Attenuation attenuation(BankBytes b) throws IOException {
		long id = b.u32();
		b.u8();
		Cone cone = null;
		if ((b.u8() & 0x01) != 0) {
			double inside = b.f32(), outside = b.f32(), outsideVolume = b.f32();
			b.f32();
			b.f32();
			cone = new Cone(inside, outside, outsideVolume);
		}
		int[] slots = new int[19];
		for (int i = 0; i < slots.length; i++) {
			slots[i] = b.s8();
		}
		int n = b.u8();
		List<Curve> curves = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int scaling = b.u8();
			curves.add(new Curve(scaling == 2, points(b, b.u16())));
		}
		List<Rtpc> rtpcs = rtpcs(b, false);
		return new Attenuation(id, slot(b, curves, slots[0]), slot(b, curves, slots[3]), slot(b, curves, slots[4]),
				cone, rtpcs);
	}

	private static @Nullable Curve slot(BankBytes b, List<Curve> curves, int index) throws IOException {
		if (index < 0) {
			return null;
		}
		if (index >= curves.size()) {
			throw b.error("curve slot " + index + " of " + curves.size() + " curves");
		}
		return curves.get(index);
	}

	private static @Nullable FxMeter fx(BankBytes b) throws IOException {
		long id = b.u32();
		long plugin = b.u32();
		Meter meter = null;
		if (plugin != 0 && plugin != 0xFFFFFFFFL) {
			long size = b.u32();
			if (plugin == METER_PLUGIN && size > 0) {
				meter = meter(b.sub((int) Math.min(size, Integer.MAX_VALUE), "Meter params"));
			} else {
				b.skip(size);
			}
		}
		b.skip(5L * b.u8());
		rtpcs(b, false);
		states(b);
		int values = b.u16();
		for (int i = 0; i < values; i++) {
			b.var();
			b.u8();
			b.f32();
		}
		return meter == null ? null : new FxMeter(id, meter);
	}

	private static Meter meter(BankBytes p) throws IOException {
		int size = p.remaining();
		if (size < 0x19 || size > 0x1C) {
			throw p.error(size + " bytes, not a meter layout (0x19 to 0x1C)");
		}
		double attack = p.f32(), release = p.f32(), min = p.f32(), max = p.f32(), hold = p.f32();
		if (size >= 0x1C) {
			p.u8();
		}
		boolean rms = p.u8() == 1;
		if (size >= 0x1B) {
			p.u8();
		}
		boolean downstream = size >= 0x1A && p.u8() != 0;
		long gameParameter = p.u32();
		p.end();
		return new Meter(gameParameter, attack, release, hold, min, max, rms, downstream);
	}

	private static Modulator modulator(BankBytes b, boolean lfo) throws IOException {
		long id = b.u32();
		Map<String, Double> props = props(b, true);
		ranged(b, true);
		return new Modulator(id, lfo, props, rtpcs(b, true));
	}

	private static String name(String[] names, long id) {
		return id >= 0 && id < names.length ? names[(int) id] : "p" + Long.toHexString(id);
	}

	private static String label(String[] names, long value) {
		return value < names.length ? names[(int) value] : Long.toString(value);
	}

	private static <K, V> Map<K, V> frozen(Map<K, V> m) {
		return Collections.unmodifiableMap(m);
	}

	private static int tag(String s) {
		byte[] c = s.getBytes(StandardCharsets.US_ASCII);
		return c[0] & 0xFF | (c[1] & 0xFF) << 8 | (c[2] & 0xFF) << 16 | (c[3] & 0xFF) << 24;
	}

	private static String tagName(int tag) {
		byte[] c = {(byte) tag, (byte) (tag >> 8), (byte) (tag >> 16), (byte) (tag >> 24)};
		return new String(c, StandardCharsets.ISO_8859_1);
	}
}
