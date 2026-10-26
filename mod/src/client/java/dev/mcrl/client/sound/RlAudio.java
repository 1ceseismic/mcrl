package dev.mcrl.client.sound;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.BallSignals;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxEvent;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.link.LocalSimLink;
import dev.mcrl.client.match.MatchClientState;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.sound.ak.AkEngine;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.client.sound.ak.Listener;
import dev.mcrl.client.sound.ak.Mix;
import dev.mcrl.client.sound.ak.SoundModel;
import dev.mcrl.client.sound.ak.Voice;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.extract.SoundCueExtractor;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.model.SoundCue;
import dev.mcrl.rlassets.wwise.Fnv;
import dev.mcrl.rlassets.wwise.HircModel;

public final class RlAudio {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final String NO_SOUNDS = "Rocket League sounds unavailable: ";
	private static final String CONVERT_NOW = "then press Convert now in mcrl settings (M) > Setup";

	public record Frame(double now, boolean paused, Mix.Settings settings, Map<Integer, CarSignals> cars,
			@Nullable BallSignals ball, double simNow, IntPredicate driven, int jumpPresses, @Nullable Level level,
			Listener listener, @Nullable Vec3 viewTarget) {
	}

	private static volatile @Nullable RlAudio game;

	private final SoundSink sink = new SoundSink();
	private final RandomGenerator rng = new SplittableRandom();
	private @Nullable AkEngine engine;
	private Map<String, FxActorDef> fxDefs = Map.of();
	private Map<String, SoundCue> cues = Map.of();
	private final Map<Integer, CarAudio> cars = new LinkedHashMap<>();
	private @Nullable BallAudio ball;
	private double clock, lastNow = Double.NaN;
	private boolean wasPaused;
	private final Map<Integer, List<FxEvent>> heldCar = new HashMap<>();
	private final List<FxEvent> heldBall = new ArrayList<>();
	private volatile FreeActorSounds freeSounds = new FreeActorSounds((event, obj) -> { }, Map.of());
	private static long oneShotIds = Long.MIN_VALUE / 2;
	private final List<GameObject> atListener = new ArrayList<>();
	private Listener listener = new Listener(Vec3.ZERO, new Vec3(0, 0, 1), new Vec3(0, 1, 0));
	private boolean silent = true;

	private RlAudio() {
	}

	public static @Nullable RlAudio game() {
		return game;
	}

	public void bind(@Nullable SoundModel model, Map<String, FxActorDef> fxDefs, Map<String, SoundCue> cues) {
		stopAll();
		AkEngine e = model == null ? null : new AkEngine(model, sink, rng);
		if (e != null) {
			e.advanceTo(clock);
		}
		engine = e;
		this.fxDefs = fxDefs;
		this.cues = cues;
		freeSounds = new FreeActorSounds(this::post, cues);
	}

	public void stopAll() {
		AkEngine e = engine;
		if (e != null) {
			e.stopAll();
		}
		cars.clear();
		ball = null;
		heldCar.clear();
		heldBall.clear();
		atListener.clear();
	}

	public double normalizedRpm(int carId) {
		CarAudio a = cars.get(carId);
		return a == null ? Double.NaN : a.rpm().normalizedRpm();
	}

	private AkEngine.Posted post(String event, GameObject obj) {
		AkEngine e = engine;
		if (e == null) {
			return AkEngine.Posted.NONE;
		}
		return e.postNamed(event, obj);
	}

	private static boolean bankTwoD(Voice v) {
		for (HircModel.Node n : v.chain()) {
			if (n.positioning() != null) {
				return n.positioning().spatialization() == 0;
			}
		}
		return true;
	}

	public boolean postOneShot(String event, @Nullable Vec3 at) {
		AkEngine e = engine;
		if (e == null || silent || e.model().event(Fnv.hash(event)) == null) {
			return false;
		}
		GameObject obj = new GameObject(oneShotIds++, null);
		Listener l = listener;
		obj.setTransform(at != null ? at : l.pos(), at != null ? new Vec3(1, 0, 0) : l.forward());
		AkEngine.Posted p = post(event, obj);
		if (at != null && !p.started().isEmpty() && p.started().stream().allMatch(RlAudio::bankTwoD)) {
			obj.setTransform(l.pos(), l.forward());
		}
		return true;
	}

	public @Nullable GameObject postAtListener(String event) {
		AkEngine e = engine;
		if (e == null || silent || e.model().event(Fnv.hash(event)) == null) {
			return null;
		}
		GameObject obj = new GameObject(oneShotIds++, null);
		obj.setTransform(listener.pos(), listener.forward());
		post(event, obj);
		atListener.add(obj);
		return obj;
	}

	public void stop(GameObject obj) {
		atListener.remove(obj);
		AkEngine e = engine;
		if (e != null) {
			e.stopAll(obj);
		}
	}

	public boolean atListener(GameObject obj) {
		return atListener.contains(obj);
	}

	public void frame(Frame f) {
		if (f.paused()) {
			for (CarSignals s : f.cars().values()) {
				heldCar.computeIfAbsent(s.carId(), id -> new ArrayList<>()).addAll(s.events());
			}
			BallSignals b = f.ball();
			if (b != null) {
				heldBall.addAll(b.events());
			}
			wasPaused = true;
			return;
		}
		double dt = wasPaused || Double.isNaN(lastNow) ? 0 : Math.max(0, f.now() - lastNow);
		lastNow = f.now();
		wasPaused = false;
		clock += dt;
		AkEngine e = engine;
		listener = f.listener();
		silent = e == null || f.settings().master() <= 0;
		if (e == null || f.settings().master() <= 0) {
			stopAll();
			if (e != null) {
				e.update(clock, f.listener());
			}
			return;
		}
		e.globals().setState(GameParams.GAME_MODE, GameParams.TRAINING);
		e.globals().setState(GameParams.PLAYER_COUNT, GameParams.ONES);
		e.setSettings(f.settings());
		Level level = f.level();
		Map<Integer, CarSignals> frameCars = level == null ? Map.of() : f.cars();
		for (Iterator<Map.Entry<Integer, CarAudio>> it = cars.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Integer, CarAudio> c = it.next();
			CarSignals present = frameCars.get(c.getKey());
			if (present == null || present.demoed()) {
				for (GameObject o : c.getValue().objects()) {
					e.stopAll(o);
				}
				it.remove();
			}
		}
		List<FxEvent> ballHits = new ArrayList<>();
		for (CarSignals s : frameCars.values()) {
			if (s.demoed()) {
				continue;
			}
			s = withHeld(s);
			CarAudio a = cars.computeIfAbsent(s.carId(),
					id -> new CarAudio(id, this::post, fxDefs, cues, rng::nextDouble));
			a.frame(new CarAudio.Input(s, f.driven().test(s.carId()), f.jumpPresses(), level, clock, f.simNow(), dt,
					f.listener()));
			for (FxEvent ev : s.events()) {
				if (ev instanceof FxEvent.BallHit) {
					ballHits.add(ev);
				}
			}
		}
		heldCar.clear();
		BallSignals b = f.ball();
		BallAudio ba = ball;
		if (b == null || level == null) {
			if (ba != null) {
				e.stopAll(ba.ball());
				ball = null;
			}
		} else {
			if (ba == null) {
				ba = new BallAudio(this::post, fxDefs, cues);
				ball = ba;
			}
			if (!heldBall.isEmpty()) {
				List<FxEvent> events = new ArrayList<>(heldBall);
				events.addAll(b.events());
				b = new BallSignals(b.pose(), b.velocity(), b.worldContact(), b.lastTouchTeam(), events);
			}
			ba.frame(b, ballHits, level, f.viewTarget(), clock, f.simNow(), dt, f.listener());
		}
		heldBall.clear();
		for (GameObject o : atListener) {
			o.setTransform(f.listener().pos(), f.listener().forward());
		}
		e.update(clock, f.listener());
	}

	private CarSignals withHeld(CarSignals s) {
		List<FxEvent> held = heldCar.get(s.carId());
		if (held == null || held.isEmpty()) {
			return s;
		}
		List<FxEvent> events = new ArrayList<>(held);
		events.addAll(s.events());
		return new CarSignals(s.carId(), s.pose(), s.velocity(), s.angularVelocity(), s.wheels(), s.onGround(),
				s.supersonic(), s.boosting(), s.forwardSpeed(), s.throttle(), s.local(), s.bodySurface(), events, s.fx(),
				s.demoed());
	}

	private static String noSoundsLine(@Nullable String why, CacheWriter.@Nullable SoundsMissingKind kind, boolean predates) {
		if (predates) {
			return NO_SOUNDS + RlCache.PREDATES_SOUND_MODEL + "; it is being converted again";
		}
		String next;
		if (kind == CacheWriter.SoundsMissingKind.BANKS) {
			next = "; Rocket League's sound banks did not read (see the log), as after an RL update mcrl does not know";
		} else if (kind == CacheWriter.SoundsMissingKind.DECODE_FAILED) {
			next = "; vgmstream-cli could not decode them: rebuild it with scripts/setup-tools.sh (see the log), "
					+ CONVERT_NOW;
		} else {
			next = "; build vgmstream-cli with scripts/setup-tools.sh (or set its path in mcrl settings (M) > Setup), "
					+ CONVERT_NOW;
		}
		return NO_SOUNDS + (why == null ? "the RL cache has no sounds" : why) + next;
	}

	public static void register() {
		RlAudio a = new RlAudio();
		game = a;
		FrameSignals.setRpmSource(a::normalizedRpm);
		registerSinks(a);
		Hook h = new Hook(a);
		FrameEvents.register((mc, frame, frameNanos) -> h.frame(mc, frameNanos));
		FrameEvents.register((mc, frame, frameNanos) -> RlSoundFrames.update(mc));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(h::stop));
	}

	private static void registerSinks(RlAudio a) {
		FxSinks.registerActorSink(SoundCueExtractor.COMPONENT, new FxSinks.ActorSink() {
			@Override
			public void activate(FxActorRef r, AttachmentDef at) {
				a.freeSounds.activate(r, at);
			}

			@Override
			public boolean deactivate(FxActorRef r, AttachmentDef at) {
				return a.freeSounds.deactivate(r, at);
			}
		});
	}

	private static final class Hook {
		private final RlAudio audio;
		private @Nullable RlCache bound, told;
		private boolean failed;

		Hook(RlAudio audio) {
			this.audio = audio;
		}

		void frame(Minecraft mc, long frameNanos) {
			if (failed) {
				return;
			}
			try {
				RlCache c = RlCache.loaded();
				if (c != bound) {
					bound = c;
					SoundModel m = c == null ? null : c.soundModel();
					RlSoundLibrary.game().bind(c == null ? null : c.dir(), m == null ? Map.of() : m.raw().media());
					audio.bind(m, c == null ? Map.of() : c.fxActors(), c == null ? Map.of() : c.soundCues());
				}
				McrlConfig cfg = McrlConfig.get();
				if (c != null && c != told && c.soundModel() == null && cfg.soundVolume > 0f && mc.player != null) {
					told = c;
					String line = noSoundsLine(c.soundsMissing(), c.soundsMissingKind(), c.soundsPredateModel());
					LOG.info("mcrl: {}", line);
					mc.gui.hud.getChat().addClientSystemMessage(Component.literal("[mcrl] " + line));
				}
				audio.frame(frameOf(mc, frameNanos, cfg));
			} catch (RuntimeException e) {
				failed = true;
				LOG.error("mcrl: the RL sounds failed; they stay off until Minecraft restarts", e);
				stop();
			}
		}

		void stop() {
			try {
				audio.stopAll();
			} catch (RuntimeException e) {
				LOG.error("mcrl: stopping the RL sounds failed", e);
			}
		}

		private static Frame frameOf(Minecraft mc, long frameNanos, McrlConfig cfg) {
			ClientLevel level = mc.level;
			FrameSignals s = level == null ? FrameSignals.EMPTY : FrameSignals.current();
			Camera cam = mc.gameRenderer.mainCamera();
			Vector3fc fwd = cam.forwardVector(), up = cam.upVector();
			Listener listener = new Listener(cam.position(), new Vec3(fwd.x(), fwd.y(), fwd.z()), new Vec3(up.x(), up.y(),
					up.z()));
			Vec3 view = null;
			for (CarSignals c : s.cars().values()) {
				if (c.local()) {
					Pose p = c.pose();
					view = new Vec3(p.x(), p.y(), p.z());
				}
			}
			if (view == null && mc.player != null) {
				view = mc.player.getEyePosition();
			}
			BallSignals ball = MatchClientState.ballOutOfPlay() ? null : s.ball();
			return new Frame(frameNanos * 1e-9, LocalSimLink.pausing(mc), cfg.mixSettings(), s.cars(), ball,
					s.simSeconds(), id -> level != null && level.getEntity(id) instanceof CarEntity car
							&& car.getControllingPassenger() != null,
					LocalSimLink.jumpPresses(), level,
					listener, view);
		}
	}
}
