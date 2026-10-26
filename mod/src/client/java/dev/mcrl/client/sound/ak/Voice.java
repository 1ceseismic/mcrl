package dev.mcrl.client.sound.ak;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.mcrl.client.sound.RlSoundInstance;
import dev.mcrl.rlassets.wwise.HircModel;
import dev.mcrl.rlassets.wwise.HircModel.Node;
import dev.mcrl.rlassets.wwise.WwiseCurve;

public final class Voice {
	private final Node leaf;
	private final GameObject owner;
	private final List<Long> path;
	private final List<Node> chain;
	private final HircModel.Media media;
	private final boolean loop;
	private final double startAt;
	private final Map<Long, Map<String, Double>> draws;
	private final double fadeInAt, fadeInSec;
	private final WwiseCurve.Shape fadeInShape;
	private double fadeOutAt = Double.POSITIVE_INFINITY, fadeOutSec;
	private WwiseCurve.Shape fadeOutShape = WwiseCurve.Shape.LINEAR;
	private double releasedAt = Double.POSITIVE_INFINITY;
	private @Nullable RlSoundInstance handle;
	private boolean started;
	private double mediaTime, clock, pitchRatio = 1;
	private boolean twoD;
	private double restartAt = Double.NEGATIVE_INFINITY, lastRestart = Double.NEGATIVE_INFINITY;

	Voice(Node leaf, GameObject owner, List<Long> path, List<Node> chain, HircModel.Media media, boolean loop,
			double startAt, Map<Long, Map<String, Double>> draws, double fadeInSec, WwiseCurve.Shape fadeInShape) {
		this.leaf = leaf;
		this.owner = owner;
		this.path = path;
		this.chain = chain;
		this.media = media;
		this.loop = loop;
		this.startAt = startAt;
		this.draws = draws;
		this.fadeInAt = startAt;
		this.fadeInSec = fadeInSec;
		this.fadeInShape = fadeInShape;
	}

	public long leaf() {
		return leaf.id();
	}

	public GameObject owner() {
		return owner;
	}

	public double startAt() {
		return startAt;
	}

	public List<Long> path() {
		return path;
	}

	public List<Node> chain() {
		return chain;
	}

	public boolean under(long nodeId) {
		for (Node n : chain) {
			if (n.id() == nodeId) {
				return true;
			}
		}
		return false;
	}

	public double draw(long nodeId, String prop) {
		Map<String, Double> d = draws.get(nodeId);
		return d == null ? 0 : VoiceEval.prop(d, prop);
	}

	public Map<Long, Map<String, Double>> draws() {
		return draws;
	}

	public HircModel.Media media() {
		return media;
	}

	public boolean loop() {
		return loop;
	}

	public double length() {
		return (double) media.frames() / media.rate();
	}

	public @Nullable RlSoundInstance handle() {
		return handle;
	}

	public boolean started() {
		return started;
	}

	public double releasedAt() {
		return releasedAt;
	}

	public double fade(double now) {
		double in = 1;
		if (fadeInSec > 0) {
			in = WwiseCurve.interp(clamp01((now - fadeInAt) / fadeInSec), 0, 1, fadeInShape);
		}
		double out = 1;
		if (now >= fadeOutAt) {
			out = fadeOutSec <= 0 ? 0 : WwiseCurve.interp(clamp01((now - fadeOutAt) / fadeOutSec), 1, 0, fadeOutShape);
		}
		return Math.max(0, Math.min(1, in * out));
	}

	private static double clamp01(double t) {
		return t < 0 ? 0 : t > 1 ? 1 : t;
	}

	void fadeOut(double at, double seconds, WwiseCurve.Shape shape, boolean release) {
		if (at + seconds < fadeOutAt + fadeOutSec) {
			fadeOutAt = at;
			fadeOutSec = seconds;
			fadeOutShape = shape;
		}
		if (release && at < releasedAt) {
			releasedAt = at;
		}
	}

	boolean fadedOut(double now) {
		return now >= fadeOutAt + fadeOutSec;
	}

	public double playPosition() {
		return started ? wrap(mediaTime) : 0;
	}

	private double wrap(double t) {
		if (loop) {
			double len = length();
			return len > 0 ? t % len : 0;
		}
		return t;
	}

	public boolean envelopeTracking() {
		for (Node n : chain) {
			if (n.hdrEnvelope() != null) {
				return n.hdrEnvelope();
			}
		}
		return false;
	}

	public double hdrActiveRange() {
		for (Node n : chain) {
			Double r = n.props().get("HDRActiveRange");
			if (r != null) {
				return r;
			}
		}
		return Double.POSITIVE_INFINITY;
	}

	void start(double now, @Nullable RlSoundInstance h, double ratio, boolean twoD) {
		handle = h;
		started = true;
		clock = now;
		mediaTime = 0;
		pitchRatio = ratio;
		this.twoD = twoD;
	}

	public boolean twoD() {
		return twoD;
	}

	void dropped(double now, double retry) {
		restartAt = Math.max(now, lastRestart + retry);
	}

	double restartAt() {
		return restartAt;
	}

	void restarted(double now) {
		lastRestart = now;
	}

	void advance(double now) {
		mediaTime += Math.max(0, now - clock) * pitchRatio;
		clock = now;
	}

	void ratio(double ratio) {
		pitchRatio = ratio;
	}

	void handle(@Nullable RlSoundInstance h) {
		handle = h;
	}

	boolean playedOut() {
		return !loop && mediaTime >= length();
	}
}
