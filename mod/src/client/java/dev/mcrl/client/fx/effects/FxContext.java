package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3f;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.BallSignals;
import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.EffectsSettings;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.fx.FxWorld;
import dev.mcrl.client.fx.particle.FxDefs;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.render.look.CarLooks;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.SimFrame;

public final class FxContext {
	private long seeds = System.nanoTime();

	private FxContext() {
	}

	public static void register() {
		FxContext ctx = new FxContext();
		SupersonicTrails.register();
		BallTrail.register();
		BallDissolve.register();
		WheelDust.register(ctx);
		VanillaWheelFx.register();
		Splashes.register();
		FlipResets.register(ctx);
		Impacts.register(ctx);
		FreeActors.register();
		SpawnFx.register();
		FxWorld.register();
		ActorEffects.Registry owners = new ActorEffects.Registry(ctx);
		FxSinks.registerActorSink("ParticleSystemComponent", new ParticleAttachment(owners));
		FxSinks.registerActorSink("StaticMeshComponent", new MeshAttachment(owners));
		FxSinks.registerActorSink("LensFlareComponent", new LensFlare(owners));
		FxSinks.registerActorSink("DecalComponent", new GroundReticle(owners));
	}

	public @Nullable FxSystemDef system(String path) {
		RlCache rl = RlCache.loaded();
		return rl == null ? null : FxDefs.system(rl, path);
	}

	public Vector3f socket(FxActorRef r, @Nullable String name) {
		RlCache rl = RlCache.loaded();
		return rl == null ? Sockets.carLocal((RlCache) null, name) : Sockets.carLocal(CarLooks.ofCar(rl, r.carId()), name);
	}

	public @Nullable Pose carPose(int carId) {
		CarSignals s = FrameSignals.current().car(carId);
		return s == null ? null : s.pose();
	}

	public @Nullable Pose ballPose() {
		BallSignals b = FrameSignals.current().ball();
		return b == null ? null : b.pose();
	}

	public EffectsSettings settings() {
		return McrlConfig.get().effects;
	}

	public FxWorld world() {
		return FxWorld.get();
	}

	public long seed() {
		return seeds++;
	}

	public Vector3f actorOrigin(FxActorRef r, Vector3f out) {
		int wheel = SupersonicTrails.wheelOf(r.key());
		SimFrame.CarSnap snap = wheel < 0 ? null : ClientPoses.carSnap(r.carId());
		return snap == null ? out.set(0, 0, 0) : SupersonicTrails.origin(snap.wheelLocal(), wheel, out);
	}
}
