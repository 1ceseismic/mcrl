package dev.mcrl.client.sound.ak;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;

import dev.mcrl.client.sound.RlSoundInstance;
import dev.mcrl.client.sound.SoundSink;
import dev.mcrl.rlassets.wwise.Fnv;
import dev.mcrl.rlassets.wwise.HircModel;
import dev.mcrl.rlassets.wwise.HircModel.Action;
import dev.mcrl.rlassets.wwise.HircModel.Node;
import dev.mcrl.rlassets.wwise.HircModel.RanSeq;
import dev.mcrl.rlassets.wwise.HircModel.SwitchMap;
import dev.mcrl.rlassets.wwise.WwiseCurve;

public final class AkEngine implements Poster {
	public static final double MAX_DT = 0.25;
	static final double VIRTUAL_BELOW = 1e-3, REAL_ABOVE = 0.002;

	public record Posted(List<Voice> started) {
		public static final Posted NONE = new Posted(List.of());
	}

	private final SoundModel model;
	private final SoundSink sink;
	private final RandomGenerator rng;
	private final Globals globals;
	private final List<Voice> voices = new ArrayList<>();
	private final List<Chain> chains = new ArrayList<>();
	private final Map<Long, Picker> pickers = new HashMap<>();
	private final Mix mix;
	private final List<Voice> snapshot = new ArrayList<>(), due = new ArrayList<>();
	private final Map<Voice, VoiceEval.Result> eval = new HashMap<>();
	private Mix.Settings settings = Mix.Settings.DEFAULT;
	private double now;
	private double last = Double.NaN;

	public AkEngine(SoundModel model, SoundSink sink, RandomGenerator rng) {
		this.model = model;
		this.sink = sink;
		this.rng = rng;
		this.globals = new Globals(model);
		this.mix = new Mix(model, globals);
	}

	public SoundModel model() {
		return model;
	}

	public Globals globals() {
		return globals;
	}

	public void setSettings(Mix.Settings s) {
		settings = s;
	}

	public void advanceTo(double now) {
		this.now = now;
		last = now;
	}

	@Override
	public void post(String event, GameObject obj) {
		postNamed(event, obj);
	}

	public Posted postNamed(String event, GameObject obj) {
		obj.bind(globals);
		HircModel.Event e = model.event(Fnv.hash(event));
		if (e == null) {
			return Posted.NONE;
		}
		List<Voice> started = new ArrayList<>();
		for (Action a : e.actions()) {
			switch (a.type()) {
				case PLAY -> {
					Node target = model.node(a.target());
					if (target != null) {
						Play p = new Play(obj, now + a.delayMs() / 1000, a.fadeMs() / 1000, a.fadeCurve());
						build(target, p, new ArrayList<>(), started);
					}
				}
				case STOP -> stop(obj, a.target(), a.fadeMs() / 1000, a.fadeCurve());
				case OTHER -> {
				}
			}
		}
		return new Posted(List.copyOf(started));
	}

	private record Play(GameObject obj, double at, double fadeSec, WwiseCurve.Shape fade) {
	}

	private void build(Node n, Play p, List<Long> path, List<Voice> out) {
		if (path.contains(n.id())) {
			return;
		}
		List<Long> here = new ArrayList<>(path);
		here.add(n.id());
		switch (n.kind()) {
			case SOUND -> {
				Voice v = voice(n, p, here);
				if (v != null) {
					out.add(v);
				}
			}
			case SWITCH -> {
				HircModel.Switch sw = n.sw();
				if (sw != null) {
					List<Long> kids = sw.cases().get(switchValue(sw, p.obj()));
					if (kids == null) {
						kids = sw.cases().getOrDefault(sw.defaultValue(), List.of());
					}
					children(kids, p, here, out);
				}
			}
			case RAN_SEQ -> {
				RanSeq r = n.ranSeq();
				if (r == null || r.playlist().isEmpty()) {
					return;
				}
				if (r.continuous()) {
					Chain c = new Chain(n, r, p, here);
					chains.add(c);
					c.playItem(p, out);
				} else {
					Node child = model.node(r.playlist().get(pickers.computeIfAbsent(n.id(), k -> new Picker()).pick(r, rng)));
					if (child != null) {
						build(child, p, here, out);
					}
				}
			}
			case LAYER, ACTOR_MIXER -> children(n.children(), p, here, out);
		}
	}

	private void children(List<Long> ids, Play p, List<Long> path, List<Voice> out) {
		for (long c : ids) {
			Node child = model.node(c);
			if (child != null) {
				build(child, p, path, out);
			}
		}
	}

	private long switchValue(HircModel.Switch sw, GameObject obj) {
		long v = sw.stateGroup() ? globals.state(sw.group()) : obj.switchValue(sw.group());
		if (v == 0 && !sw.stateGroup()) {
			SwitchMap map = model.raw().switchFromRtpc().get(sw.group());
			if (map != null) {
				v = model.switchFromRtpc(sw.group(), obj.rtpc(map.rtpc()));
			}
		}
		return v == 0 ? sw.defaultValue() : v;
	}

	private @Nullable Voice voice(Node leaf, Play p, List<Long> path) {
		HircModel.Media media = model.raw().media().get(leaf.media());
		if (media == null) {
			return null;
		}
		List<Node> chain = model.chain(leaf.id());
		for (Node n : chain) {
			if (n.limit().max() > 0 && !makeRoom(n, p.obj())) {
				return null;
			}
		}
		Map<Long, Map<String, Double>> draws = new LinkedHashMap<>();
		double delay = 0;
		for (Node n : chain) {
			if (!n.random().isEmpty()) {
				Map<String, Double> d = new LinkedHashMap<>();
				n.random().forEach((prop, range) -> d.put(prop, range[0] + (range[1] - range[0]) * rng.nextDouble()));
				draws.put(n.id(), d);
			}
			delay += n.props().getOrDefault("InitialDelay", 0.0);
		}
		Double loopProp = leaf.props().get("Loop");
		Voice v = new Voice(leaf, p.obj(), List.copyOf(path), chain, media, loopProp != null && loopProp == 0,
				p.at() + delay, draws, p.fadeSec(), p.fade());
		voices.add(v);
		return v;
	}

	private boolean makeRoom(Node n, GameObject obj) {
		List<Voice> under = new ArrayList<>();
		for (Voice v : voices) {
			if (v.owner() == obj && v.under(n.id())) {
				under.add(v);
			}
		}
		if (under.size() < n.limit().max()) {
			return true;
		}
		if (n.limit().killNewest()) {
			return false;
		}
		for (int i = 0; i <= under.size() - n.limit().max(); i++) {
			end(under.get(i));
		}
		return true;
	}

	private void stop(GameObject obj, long target, double fadeSec, WwiseCurve.Shape shape) {
		for (Chain c : chains) {
			if (c.owner() == obj && model.chain(c.container().id()).stream().anyMatch(n -> n.id() == target)) {
				c.stopped = true;
			}
		}
		for (Iterator<Voice> it = voices.iterator(); it.hasNext();) {
			Voice v = it.next();
			if (v.owner() != obj || !v.under(target)) {
				continue;
			}
			if (!v.started()) {
				it.remove();
			} else {
				v.fadeOut(now, fadeSec, shape, true);
			}
		}
	}

	public void stopAll() {
		for (Voice v : List.copyOf(voices)) {
			end(v);
		}
		chains.clear();
	}

	public void stopAll(GameObject obj) {
		for (Voice v : List.copyOf(voices)) {
			if (v.owner() == obj) {
				end(v);
			}
		}
		chains.removeIf(c -> c.owner() == obj);
	}

	private void end(Voice v) {
		RlSoundInstance h = v.handle();
		if (h != null) {
			sink.stop(h);
		}
		voices.remove(v);
	}

	public void update(double now, Listener listener) {
		double elapsed = Double.isNaN(last) ? 0 : Math.max(0, now - last);
		double dt = Math.min(elapsed, MAX_DT);
		this.now = now;
		last = now;
		globals.ramping.removeIf(o -> !o.tick(dt));
		for (Iterator<Chain> it = chains.iterator(); it.hasNext();) {
			if (it.next().advance(now)) {
				it.remove();
			}
		}
		due.clear();
		snapshot.clear();
		snapshot.addAll(voices);
		for (Voice v : snapshot) {
			if (now < v.startAt()) {
				continue;
			}
			if (v.started()) {
				v.advance(now);
				RlSoundInstance h = v.handle();
				boolean dropped = h != null && !sink.active(h);
				if (v.playedOut() || v.fadedOut(now) || dropped && !v.loop()) {
					end(v);
					continue;
				}
				if (dropped) {
					v.handle(null);
					v.dropped(now, SoundSink.RETRY);
				}
			}
			due.add(v);
		}
		eval.clear();
		for (Voice v : due) {
			eval.put(v, VoiceEval.eval(v, model, globals, listener, now));
		}
		mix.frame(dt, due, eval, settings);
		for (Voice v : due) {
			VoiceEval.Result r = eval.get(v);
			float gain = mix.gain(v, r, settings), pitch = mix.pitch(r);
			RlSoundInstance h = v.handle();
			if (!v.started()) {
				v.start(now, v.loop() && gain < VIRTUAL_BELOW ? null : play(v, r.twoD(), gain, pitch), pitch, r.twoD());
			} else if (h != null) {
				v.ratio(pitch);
				if (v.loop() && gain < VIRTUAL_BELOW) {
					sink.stop(h);
					v.handle(null);
				} else {
					sink.set(h, gain, pitch);
				}
			} else {
				v.ratio(pitch);
				if (gain > REAL_ABOVE && now >= v.restartAt()) {
					v.restarted(now);
					v.handle(play(v, v.twoD(), gain, pitch));
				}
			}
		}
	}

	private RlSoundInstance play(Voice v, boolean twoD, float gain, float pitch) {
		String stem = "w" + v.media().id() + (twoD ? "_2d" : "");
		return sink.play(new SoundSink.Request(stem, v.loop(), twoD, v.owner()::pos, gain, pitch));
	}

	private double staticCents(Voice v) {
		double cents = model.staticCents(v.leaf());
		for (Map<String, Double> d : v.draws().values()) {
			cents += d.getOrDefault("Pitch", 0.0);
		}
		return cents;
	}

	private final class Chain {
		private final Node container;
		private final RanSeq r;
		private final List<Long> path;
		private final Play play;
		private int index, loops;
		private double nextAt;
		private List<Voice> current = List.of();
		boolean stopped;

		Chain(Node container, RanSeq r, Play play, List<Long> path) {
			this.container = container;
			this.r = r;
			this.play = play;
			this.path = path;
		}

		Node container() {
			return container;
		}

		GameObject owner() {
			return play.obj();
		}

		void playItem(Play p, List<Voice> out) {
			Node child = model.node(r.playlist().get(index));
			List<Voice> made = new ArrayList<>();
			if (child != null) {
				build(child, p, path, made);
			}
			out.addAll(made);
			current = made;
			double longest = 0;
			for (Voice v : made) {
				longest = Math.max(longest, v.startAt() - p.at() + v.length() / Math.pow(2, staticCents(v) / 1200));
			}
			nextAt = p.at() + Math.max(longest - r.transitionMs() / 1000, 1e-3);
		}

		boolean advance(double now) {
			if (stopped) {
				return true;
			}
			if (now < nextAt) {
				return false;
			}
			if (++index >= r.playlist().size()) {
				index = 0;
				if (++loops >= r.loopCount() && r.loopCount() != 0) {
					return true;
				}
			}
			double t = r.transitionMs() / 1000;
			if (now - nextAt > Math.max(t, MAX_DT)) {
				nextAt = now;
			}
			for (Voice v : current) {
				v.fadeOut(nextAt, t, WwiseCurve.Shape.LINEAR, false);
			}
			playItem(new Play(play.obj(), nextAt, t, WwiseCurve.Shape.LINEAR), new ArrayList<>());
			return false;
		}
	}

	private static final class Picker {
		private final Deque<Integer> recent = new ArrayDeque<>();
		private final List<Integer> deck = new ArrayList<>();
		private int next;

		int pick(RanSeq r, RandomGenerator rng) {
			int n = r.playlist().size();
			if (r.sequence()) {
				return next++ % n;
			}
			int avoid = Math.min(r.avoidRepeat(), n - 1);
			int pick;
			if (r.shuffle()) {
				if (deck.isEmpty()) {
					for (int i = 0; i < n; i++) {
						deck.add(i);
					}
					Collections.shuffle(deck, rng);
					for (int i = 0; i < Math.min(avoid, deck.size()); i++) {
						if (recent.contains(deck.get(i))) {
							for (int j = avoid; j < deck.size(); j++) {
								if (!recent.contains(deck.get(j))) {
									Collections.swap(deck, i, j);
									break;
								}
							}
						}
					}
				}
				pick = deck.remove(0);
			} else {
				double total = 0;
				for (int i = 0; i < n; i++) {
					total += recent.contains(i) ? 0 : weight(r, i);
				}
				double x = rng.nextDouble() * total;
				pick = -1;
				for (int i = 0; i < n && pick < 0; i++) {
					if (!recent.contains(i)) {
						x -= weight(r, i);
						if (x < 0) {
							pick = i;
						}
					}
				}
				if (pick < 0) {
					for (int i = n - 1; i >= 0 && pick < 0; i--) {
						if (!recent.contains(i)) {
							pick = i;
						}
					}
				}
			}
			if (avoid > 0) {
				recent.addLast(pick);
				while (recent.size() > avoid) {
					recent.removeFirst();
				}
			}
			return pick;
		}

		private static double weight(RanSeq r, int i) {
			return r.weights().size() == r.playlist().size() ? Math.max(0, r.weights().get(i)) : 1;
		}
	}
}
