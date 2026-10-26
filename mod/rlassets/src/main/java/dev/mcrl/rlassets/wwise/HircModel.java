package dev.mcrl.rlassets.wwise;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record HircModel(Map<Long, Event> events, Map<Long, Node> nodes, Map<Long, Bus> buses,
		Map<Long, Attenuation> attenuations, Map<Long, Modulator> modulators,
		Map<Long, GameParameter> gameParameters, Map<Long, SwitchMap> switchFromRtpc, Map<Long, Media> media) {
	public enum Kind { SOUND, RAN_SEQ, SWITCH, LAYER, ACTOR_MIXER }

	public enum ActionType { PLAY, STOP, OTHER }

	public enum Source { GAME_PARAMETER, MODULATOR, OTHER }

	public enum Accum { NONE, EXCLUSIVE, ADDITIVE, MULTIPLY, BOOLEAN, MAXIMUM, FILTER }

	public record Event(long id, List<Action> actions) {
	}

	public record Action(ActionType type, long target, double delayMs, double fadeMs, WwiseCurve.Shape fadeCurve) {
	}

	public record Rtpc(long source, Source sourceType, String param, boolean db, Accum accum,
			List<WwiseCurve.Point> points) {
	}

	public record StateGroup(long group, Map<Long, Map<String, Double>> states) {
	}

	public record Positioning(int spatialization, boolean attenuation) {
	}

	public record Limit(int max, boolean killNewest, boolean ignoreParent) {
	}

	public record RanSeq(boolean sequence, boolean shuffle, int avoidRepeat, int loopCount, boolean continuous,
			String transition, double transitionMs, List<Long> playlist, List<Integer> weights) {
	}

	public record Switch(long group, boolean stateGroup, long defaultValue, Map<Long, List<Long>> cases) {
	}

	public record Layer(long crossfadeRtpc, Map<Long, List<WwiseCurve.Point>> assoc, List<Rtpc> rtpcs) {
	}

	public record Node(long id, Kind kind, long parent, long bus, Map<String, Double> props, Map<String, double[]> random,
			List<Rtpc> rtpcs, List<StateGroup> states, @Nullable Positioning positioning, Limit limit,
			@Nullable Boolean hdrEnvelope, long media, @Nullable RanSeq ranSeq, @Nullable Switch sw,
			List<Layer> layers, List<Long> children) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Node n && id == n.id && kind == n.kind && parent == n.parent && bus == n.bus
					&& props.equals(n.props) && sameArrays(random, n.random) && rtpcs.equals(n.rtpcs)
					&& states.equals(n.states) && Objects.equals(positioning, n.positioning) && limit.equals(n.limit)
					&& Objects.equals(hdrEnvelope, n.hdrEnvelope) && media == n.media && Objects.equals(ranSeq, n.ranSeq)
					&& Objects.equals(sw, n.sw) && layers.equals(n.layers) && children.equals(n.children);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, kind, parent, bus, props, arraysHash(random), rtpcs, states, positioning, limit,
					hdrEnvelope, media, ranSeq, sw, layers, children);
		}
	}

	public record Meter(long gameParameter, double attack, double release, double hold, double min, double max,
			boolean rms, boolean applyDownstream) {
	}

	public record Hdr(double threshold, double ratio, double release, boolean exponential) {
	}

	public record Bus(long id, long parent, Map<String, Double> props, List<Rtpc> rtpcs, List<StateGroup> states,
			@Nullable Hdr hdr, List<Meter> meters) {
	}

	public record Curve(boolean db, List<WwiseCurve.Point> points) {
	}

	public record Cone(double insideDeg, double outsideDeg, double outsideVolumeDb) {
	}

	public record Attenuation(long id, @Nullable Curve volumeDry, @Nullable Curve lpf, @Nullable Curve hpf,
			@Nullable Cone cone, List<Rtpc> rtpcs) {
	}

	public record Modulator(long id, boolean lfo, Map<String, Double> props, List<Rtpc> rtpcs) {
	}

	public record GameParameter(long id, double defaultValue, String ramp, double up, double down, String builtIn) {
	}

	public record SwitchMap(long group, long rtpc, List<double[]> points) {
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof SwitchMap s) || group != s.group || rtpc != s.rtpc || points.size() != s.points.size()) {
				return false;
			}
			for (int i = 0; i < points.size(); i++) {
				if (!Arrays.equals(points.get(i), s.points.get(i))) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			int h = Objects.hash(group, rtpc);
			for (double[] p : points) {
				h = 31 * h + Arrays.hashCode(p);
			}
			return h;
		}
	}

	public record Media(long id, String file, int channels, int rate, long frames, double peakDb, float[] env10ms) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Media m && id == m.id && file.equals(m.file) && channels == m.channels && rate == m.rate
					&& frames == m.frames && Double.compare(peakDb, m.peakDb) == 0 && Arrays.equals(env10ms, m.env10ms);
		}

		@Override
		public int hashCode() {
			return Objects.hash(id, file, channels, rate, frames, peakDb, Arrays.hashCode(env10ms));
		}
	}

	private static boolean sameArrays(Map<String, double[]> a, Map<String, double[]> b) {
		if (a.size() != b.size()) {
			return false;
		}
		for (Map.Entry<String, double[]> e : a.entrySet()) {
			if (!Arrays.equals(e.getValue(), b.get(e.getKey()))) {
				return false;
			}
		}
		return true;
	}

	private static int arraysHash(Map<String, double[]> m) {
		int h = 0;
		for (Map.Entry<String, double[]> e : m.entrySet()) {
			h += e.getKey().hashCode() ^ Arrays.hashCode(e.getValue());
		}
		return h;
	}
}
