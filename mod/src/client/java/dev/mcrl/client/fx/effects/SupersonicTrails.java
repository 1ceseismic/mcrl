package dev.mcrl.client.fx.effects;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3f;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.fx.FxState;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.FxActorDef;

public final class SupersonicTrails {
	public static final String KEY = "supersonicTrail";
	private static final int[] WHEELS = {2, 3};
	private static final float BONE_OFFSET_UU = -15;

	private final Int2ObjectOpenHashMap<Car> cars = new Int2ObjectOpenHashMap<>();

	private record Car(FxActorDef def, FxActor parent, FxActor[] trails) {
		void release() {
			for (FxActor a : trails) {
				a.release();
			}
		}
	}

	private SupersonicTrails() {
	}

	public static void register() {
		SupersonicTrails t = new SupersonicTrails();
		FrameEvents.register((mc, frame, frameNanos) -> t.frame(FrameSignals.current()));
	}

	private static @Nullable FxActor carActor(int carId) {
		FxActors a = FxActors.game();
		return a == null ? null : a.actor(carId, FxActors.CAR);
	}

	private void frame(FrameSignals f) {
		RlCache rl = RlCache.loaded();
		FxActorDef d = rl == null ? null : rl.fxActors().get(KEY);
		double now = f.simSeconds();
		ObjectIterator<Int2ObjectMap.Entry<Car>> it = cars.int2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			Int2ObjectMap.Entry<Car> e = it.next();
			Car c = e.getValue();
			if (f.car(e.getIntKey()) == null || c.def() != d || c.parent() != carActor(e.getIntKey())) {
				c.release();
				it.remove();
			}
		}
		if (d == null) {
			return;
		}
		for (Map.Entry<Integer, CarSignals> e : f.cars().entrySet()) {
			CarSignals s = e.getValue();
			Car c = cars.get(s.carId());
			if (c == null) {
				FxActor parent = carActor(s.carId());
				if (parent == null) {
					continue;
				}
				FxActor[] trails = new FxActor[WHEELS.length];
				for (int k = 0; k < WHEELS.length; k++) {
					trails[k] = spawn(d, s, k, parent, now);
				}
				c = new Car(d, parent, trails);
				cars.put(s.carId(), c);
			}
			int mask = WheelFxRules.emitting(s);
			for (int k = 0; k < WHEELS.length; k++) {
				int i = WHEELS[k];
				FxActor a = c.trails()[k];
				a.setLocal(s.local(), now);
				a.setActive((mask >> i & 1) != 0 && s.wheel(i).contact());
				a.update(now);
			}
		}
	}

	private FxActor spawn(FxActorDef d, CarSignals s, int k, FxActor parent, double now) {
		Deferred sink = new Deferred();
		FxActor a = new FxActor(d, s.local(), sink);
		sink.target = FxSinks.forActor(new FxActorRef(a, s.carId(), KEY + WHEELS[k], null, new FxParams(), false));
		a.setState(FxState.SPAWNED, true, now);
		a.bindTo(parent);
		return a;
	}

	public static int wheelOf(String key) {
		if (key.length() != KEY.length() + 1 || !key.startsWith(KEY)) {
			return -1;
		}
		int w = key.charAt(KEY.length()) - '0';
		return w == 2 || w == 3 ? w : -1;
	}

	public static Vector3f origin(float[] wheelLocal, int i, Vector3f out) {
		return out.set(wheelLocal[3 * i], wheelLocal[3 * i + 1] + BONE_OFFSET_UU / 50, wheelLocal[3 * i + 2]);
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
