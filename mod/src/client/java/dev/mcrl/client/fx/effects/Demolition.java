package dev.mcrl.client.fx.effects;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.FxActorDef;

public final class Demolition {
	private static final String KEY = "demolish";
	public static final List<Consumer<Pose>> LISTENERS = new CopyOnWriteArrayList<>();

	private Demolition() {
	}

	public static void trigger(int carEntityId, @Nullable Pose eventPose) {
		FrameSignals f = FrameSignals.current();
		CarSignals s = f.car(carEntityId);
		Pose pose = s != null ? s.pose() : eventPose;
		if (pose == null) {
			return;
		}
		RlCache rl = RlCache.loaded();
		FxActorDef def = rl == null ? null : rl.fxActors().get(KEY);
		for (Consumer<Pose> l : LISTENERS) {
			l.accept(pose);
		}
		if (def != null) {
			FreeActors.play(def, KEY, pose, f.simSeconds(), new FxParams());
		}
	}
}
