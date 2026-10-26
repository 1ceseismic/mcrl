package dev.mcrl.client.sound;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.CarFxStates;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.fx.FxEvent;
import dev.mcrl.client.fx.FxState;
import dev.mcrl.client.fx.RlSurface;
import dev.mcrl.client.fx.WheelSignal;
import dev.mcrl.client.sound.ak.AkEngine;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.client.sound.ak.Listener;
import dev.mcrl.client.sound.ak.Poster;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.model.SoundCue;
import dev.mcrl.rlassets.wwise.Fnv;
import dev.mcrl.sim.CarFx;
import dev.mcrl.sim.SimClock;

public final class CarAudio {
	private static final double TELEPORT_SPEED = 100;
	private static final double TELEPORT_MIN_DT = 0.05;
	private static final double SURFACE_HOLD = 0.1;
	private static final Vec3 BOOST_SOCKET = new Vec3(-48.5 / UU_PER_BLOCK, 9 / UU_PER_BLOCK, 0);
	private static final double JUMP_GRACE = 3.0 / SimClock.TICK_RATE;
	private static long pooled = -1;

	public record Input(CarSignals s, boolean driven, int jumpPresses, Level level, double now, double simNow,
			double dt, Listener listener) {
	}

	private final Poster p;
	private final GameObject car, exhaust, boost, slide;
	private final SlideRule slideRule = new SlideRule();
	private int lastJumpPresses = -1;
	private double pendingJumpFail = Double.NaN;
	private final CarRtpcs rtpcs = new CarRtpcs();
	private final EngineRpmModel rpm;
	private final BlowoffRule blowoff = new BlowoffRule();
	private boolean engineOn, supersonic, seen;
	private @Nullable String tyres;
	private @Nullable String candidate;
	private double candidateTime;
	private @Nullable Pose lastPose;
	private final Map<String, FxActorDef> fxDefs;
	private final Map<String, SoundCue> cues;
	private @Nullable FxActor carFx, boostFx;
	private @Nullable Boolean fxLocal;

	public CarAudio(int carId, Poster p, Map<String, FxActorDef> fxDefs, Map<String, SoundCue> cues,
			DoubleSupplier uniform) {
		this.p = p;
		this.fxDefs = fxDefs;
		this.cues = cues;
		this.car = new GameObject(carId * 8L, null);
		this.exhaust = new GameObject(carId * 8L + 1, car);
		this.boost = new GameObject(carId * 8L + 2, car);
		this.slide = new GameObject(carId * 8L + 3, car);
		this.rpm = new EngineRpmModel(uniform);
	}

	public static @Nullable RlSurface contactSurface(List<WheelSignal> w) {
		RlSurface best = null;
		int bestCount = 0;
		for (WheelSignal a : w) {
			if (!a.contact() || a.surface() == null) {
				continue;
			}
			int count = 0;
			for (WheelSignal b : w) {
				count += b.contact() && b.surface() == a.surface() ? 1 : 0;
			}
			if (count > bestCount) {
				best = a.surface();
				bestCount = count;
			}
		}
		return best;
	}

	public void frame(Input in) {
		CarSignals s = in.s();
		double dt = Math.max(0, Math.min(in.dt(), AkEngine.MAX_DT));
		Pose pose = s.pose();
		place(pose);
		if (lastPose != null && moved(lastPose, pose) > TELEPORT_SPEED * Math.max(dt, TELEPORT_MIN_DT)) {
			rtpcs.reset(pose);
			candidate = null;
		}
		lastPose = pose;
		car.setRtpc(GameParams.IS_LOCAL, s.local() ? 1 : 0);
		List<WheelSignal> wheels = List.of(s.wheels());
		rpm.step(dt, in.driven(), s.onGround(), s.throttle(), s.boosting(), CarRtpcs.groundWheelSpeed(wheels));

		if (in.driven() != engineOn || !seen) {
			if (in.driven() && !engineOn) {
				p.post("Play_OctaneMK2_Car_Engine", car);
				p.post("Play_OctaneMK2_Car_Exhaust", exhaust);
			} else if (!in.driven() && engineOn) {
				stopEngine();
			}
			engineOn = in.driven();
			seen = true;
		}
		if (blowoff.step(dt, s.throttle())) {
			p.post("Play_OctaneMK2_BlowOffValve", car);
		}
		tyres(contactSurface(wheels), dt);
		boolean jumped = false;
		for (FxEvent e : s.events()) {
			switch (e) {
				case FxEvent.Landed l -> {
					RlSurface w = s.wheel(l.wheel()).surface();
					car.setSwitch(GameParams.MATERIAL_TYPE, Fnv.hash((w != null ? w : RlSurface.ROCK).soundSwitch()));
					car.setRtpc(GameParams.IMPACT_INTENSITY, l.normalSpeed());
					p.post("Play_Impact_Wheel", car);
				}
				case FxEvent.WorldHit h -> {
					GameObject o = new GameObject(pooled--, null);
					o.setTransform(h.point(), h.normal());
					o.setRtpc(GameParams.IS_LOCAL, s.local() ? 1 : 0);
					o.setSwitch(GameParams.MATERIAL_TYPE, Fnv.hash(h.surface().soundSwitch()));
					o.setRtpc(GameParams.IMPACT_INTENSITY, h.speed());
					p.post("Play_Impact_Body", o);
				}
				case FxEvent.FlipReset x -> p.post("Play_Car_Movement_FlipReset", car);
				case FxEvent.Jumped x -> jumped = true;
				case FxEvent.DoubleJumped x -> jumped = true;
				case FxEvent.Dodged x -> jumped = true;
				case FxEvent.SupersonicStart x -> p.post("Play_Vehicle_SuperSonic", car);
				case FxEvent.BoostStart x -> rtpcs.boostStarted();
				default -> {
				}
			}
		}
		slide(s, pose, dt);
		jumpFail(in, pose, jumped);
		if (s.supersonic() != supersonic) {
			p.post(s.supersonic() ? "Play_Vehicle_SuperSonic_Loop" : "Stop_Vehicle_SuperSonic_Loop", car);
			supersonic = s.supersonic();
		}
		fx(s, in.simNow());
		rtpcs.update(s, rpm, in.level(), in.listener(), dt, car);
	}

	private void fx(CarSignals s, double now) {
		Boolean made = fxLocal;
		if (made == null || made != s.local()) {
			releaseFx();
			fxLocal = s.local();
			if (!cues.isEmpty()) {
				FxActorDef cd = fxDefs.get(FxActors.CAR), bd = fxDefs.get(FxActors.BOOST);
				FxActor c = cd == null ? null : spawn(cd, car, s.local(), now);
				FxActor b = bd == null ? null : spawn(bd, boost, s.local(), now);
				if (c != null && b != null) {
					b.bindTo(c);
				}
				carFx = c;
				boostFx = b;
			}
		}
		FxActor c = carFx, b = boostFx;
		if (c != null) {
			CarFxStates.apply(c, s, McrlConfig.Team.BLUE, now);
			c.update(now);
		}
		if (b != null) {
			b.setActive(s.boosting());
			b.update(now);
		}
	}

	private FxActor spawn(FxActorDef def, GameObject o, boolean local, double now) {
		FxActor a = new FxActor(def, local, new FxSoundSink(p, o, local, cues));
		a.setState(FxState.SPAWNED, true, now);
		return a;
	}

	private void releaseFx() {
		for (FxActor a : new FxActor[] {carFx, boostFx}) {
			if (a != null) {
				a.release();
			}
		}
		carFx = null;
		boostFx = null;
	}

	private void tyres(@Nullable RlSurface contact, double dt) {
		String now = contact == null ? null : contact.soundSwitch();
		if (now == null) {
			if (tyres != null) {
				p.post("Stop_Tires_Rolling_Loop", car);
				tyres = null;
			}
			candidate = null;
			return;
		}
		if (tyres == null) {
			car.setSwitch(GameParams.MATERIAL_TYPE, Fnv.hash(now));
			p.post("Play_Tires_Rolling_Loop", car);
			tyres = now;
			candidate = null;
			return;
		}
		if (now.equals(tyres)) {
			candidate = null;
			return;
		}
		if (!now.equals(candidate)) {
			candidate = now;
			candidateTime = 0;
		}
		candidateTime += dt;
		if (candidateTime >= SURFACE_HOLD - 1e-9) {
			car.setSwitch(GameParams.MATERIAL_TYPE, Fnv.hash(now));
			p.post("Stop_Tires_Rolling_Loop", car);
			p.post("Play_Tires_Rolling_Loop", car);
			tyres = now;
			candidate = null;
		}
	}

	private void slide(CarSignals s, Pose pose, double dt) {
		CarFx fx = s.fx();
		boolean sliding = SlideRule.sliding(fx.bodyContact(), fx.slideSpeed());
		if (fx.bodyContact() && (sliding || slideRule.playing())) {
			Vec3 n = new Vec3(fx.bnx(), fx.bny(), fx.bnz());
			slide.setTransform(new Vec3(fx.bpx(), fx.bpy(), fx.bpz()), n);
			if (s.bodySurface() != null) {
				slide.setSwitch(GameParams.MATERIAL_TYPE, GameParams.surface(s.bodySurface()));
			}
			slide.setRtpc(GameParams.CAR_SLIDE_ANGLE, Math.abs(local(pose, new Vec3(0, 1, 0)).dot(n)));
		}
		switch (slideRule.step(dt, sliding)) {
			case START -> p.post("Play_Body_Slide", slide);
			case STOP -> p.post("Stop_Body_Slide", slide);
			case NONE -> {
			}
		}
	}

	private void jumpFail(Input in, Pose pose, boolean jumped) {
		CarSignals s = in.s();
		int presses = in.jumpPresses();
		if (lastJumpPresses >= 0 && presses > lastJumpPresses && s.local() && touchesWorld(s)
				&& local(pose, new Vec3(0, 1, 0)).y > -0.5) {
			pendingJumpFail = in.simNow();
		}
		lastJumpPresses = presses;
		if (jumped) {
			pendingJumpFail = Double.NaN;
		}
		if (in.simNow() > pendingJumpFail + JUMP_GRACE + 1e-9) {
			p.post("Play_UI_JumpFail", car);
			pendingJumpFail = Double.NaN;
		}
	}

	private static boolean touchesWorld(CarSignals s) {
		for (WheelSignal w : s.wheels()) {
			if (w.contact() && w.surface() != RlSurface.BALL) {
				return true;
			}
		}
		return s.fx().bodyContact();
	}

	private void place(Pose pose) {
		Vec3 pos = new Vec3(pose.x(), pose.y(), pose.z());
		Vec3 forward = local(pose, new Vec3(1, 0, 0));
		car.setTransform(pos, forward);
		exhaust.setTransform(pos, forward.reverse());
		boost.setTransform(pos.add(local(pose, BOOST_SOCKET)), forward.reverse());
	}

	static Vec3 local(Pose p, Vec3 v) {
		double w = p.qw(), x = p.qx(), y = p.qy(), z = p.qz();
		double tx = 2 * (y * v.z - z * v.y), ty = 2 * (z * v.x - x * v.z), tz = 2 * (x * v.y - y * v.x);
		return new Vec3(v.x + w * tx + (y * tz - z * ty), v.y + w * ty + (z * tx - x * tz), v.z + w * tz + (x * ty - y * tx));
	}

	private static double moved(Pose a, Pose b) {
		double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private void stopEngine() {
		p.post("Stop_OctaneMK2_Car_Engine", car);
		p.post("Stop_OctaneMK2_Car_Exhaust", exhaust);
	}

	public EngineRpmModel rpm() {
		return rpm;
	}

	List<GameObject> objects() {
		return List.of(car, exhaust, boost, slide);
	}
}
