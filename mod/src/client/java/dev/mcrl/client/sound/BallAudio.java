package dev.mcrl.client.sound;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.BallFxStates;
import dev.mcrl.client.fx.BallSignals;
import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.fx.FxEvent;
import dev.mcrl.client.fx.FxState;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.client.sound.ak.Listener;
import dev.mcrl.client.sound.ak.Poster;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.model.SoundCue;
import dev.mcrl.rlassets.wwise.Fnv;

public final class BallAudio {
	private static final double HIT_MIN_SPEED = 50, HIT_MIN_DELAY = 0.15;
	private static long pooled = Long.MIN_VALUE / 2;

	private final Poster p;
	private final GameObject ball = new GameObject(Long.MAX_VALUE, null);
	private double lastHit = Double.NEGATIVE_INFINITY;
	private final @Nullable FxActorDef fxDef;
	private final Map<String, SoundCue> cues;
	private @Nullable FxActor fx;

	public BallAudio(Poster p, Map<String, FxActorDef> fxDefs, Map<String, SoundCue> cues) {
		this.p = p;
		this.fxDef = cues.isEmpty() ? null : fxDefs.get(FxActors.BALL);
		this.cues = cues;
	}

	public void frame(BallSignals b, List<FxEvent> ballHits, Level level, @Nullable Vec3 viewTarget, double now,
			double simNow, double dt, Listener l) {
		Pose pose = b.pose();
		ball.setTransform(new Vec3(pose.x(), pose.y(), pose.z()), new Vec3(1, 0, 0));
		for (FxEvent e : ballHits) {
			if (e instanceof FxEvent.BallHit h && !(h.speed() < HIT_MIN_SPEED || simNow - lastHit < HIT_MIN_DELAY)) {
				lastHit = simNow;
				impact(h.point(), h.normal(), "Car", h.speed());
			}
		}
		for (FxEvent e : b.events()) {
			if (e instanceof FxEvent.BallBounce bounce) {
				impact(bounce.point(), bounce.normal(), bounce.surface().soundSwitch(), bounce.speed());
			}
		}
		FxActor a = fx;
		if (a == null && fxDef != null) {
			a = new FxActor(fxDef, false, new FxSoundSink(p, ball, false, cues));
			a.setState(FxState.SPAWNED, true, simNow);
			fx = a;
		}
		if (a != null) {
			BallFxStates.apply(a, b, simNow);
			a.update(simNow);
		}
		Vec3 pos = new Vec3(pose.x(), pose.y(), pose.z());
		ball.setRtpc(GameParams.SPEED, b.velocity().length());
		ball.setRtpc(GameParams.ALTITUDE, CarRtpcs.altitude(level, pos));
		ball.setRtpc(GameParams.DISTANCE_TO_PLAYER, pos.distanceTo(viewTarget != null ? viewTarget : l.pos()) * 50);
		ball.setRtpc(GameParams.DOPPLER_ANGLE, CarRtpcs.doppler(pos, b.velocity(), l));
	}

	private void impact(Vec3 point, Vec3 normal, String materialType, double speed) {
		GameObject o = new GameObject(pooled++, null);
		o.setTransform(point, normal);
		o.setSwitch(GameParams.MATERIAL_TYPE, Fnv.hash(materialType));
		o.setRtpc(GameParams.IMPACT_INTENSITY, speed);
		p.post("Play_Impact_Ball", o);
	}

	GameObject ball() {
		return ball;
	}
}
