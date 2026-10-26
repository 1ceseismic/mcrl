package dev.mcrl.client.fx.effects;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.fx.FxState;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.FxActorDef;

public final class FreeActors {
	private static final Map<FxActor, double[]> TICKING = new IdentityHashMap<>();

	private FreeActors() {
	}

	public static void register() {
		FrameEvents.register((mc, frame, frameNanos) -> tick(FrameSignals.current().simSeconds()));
	}

	public static FxActor play(FxActorDef def, String key, Pose pose, double now, FxParams params) {
		return play(def, key, pose, now, params, FxState.SPAWNED, false);
	}

	public static FxActor play(FxActorDef def, String key, Pose pose, double now, FxParams params, FxState state,
			boolean local) {
		Deferred sink = new Deferred();
		FxActor a = new FxActor(def, false, sink);
		sink.target = FxSinks.forActor(new FxActorRef(a, -1, key, pose, params, local));
		a.setState(state, true, now);
		a.update(now);
		float delay = 0;
		for (AttachmentDef d : def.attachments()) {
			delay = Math.max(delay, d.attachDelay());
		}
		if (delay > 0) {
			TICKING.put(a, new double[] {now, now + delay});
		}
		return a;
	}

	private static void tick(double now) {
		Iterator<Map.Entry<FxActor, double[]>> it = TICKING.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<FxActor, double[]> e = it.next();
			double[] t = e.getValue();
			if (now < t[0]) {
				it.remove();
				continue;
			}
			e.getKey().update(now);
			if (now >= t[1]) {
				it.remove();
			}
		}
	}

	private static final class Deferred implements FxActor.Sink {
		private FxActor.Sink target = FxSinks.none();

		@Override
		public void activate(AttachmentDef a) {
			target.activate(a);
		}

		@Override
		public boolean deactivate(AttachmentDef a) {
			return target.deactivate(a);
		}
	}
}
