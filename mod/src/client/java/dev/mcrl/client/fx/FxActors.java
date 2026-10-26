package dev.mcrl.client.fx;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.IntFunction;

import org.jspecify.annotations.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.match.ClientTeams;
import dev.mcrl.client.render.look.CarLooks;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.FxActorDef;

public final class FxActors {
	public static final String CAR = "car", BODY = "body", BOOST = "boost", BALL = "ball";

	private static volatile @Nullable FxActors game;

	private record Car(@Nullable FxActor car, @Nullable FxActor body, @Nullable FxActor boost) {
		boolean matches(Map<String, FxActorDef> d) {
			return def(car) == d.get(CAR) && def(boost) == d.get(BOOST);
		}

		void frame(CarSignals s, McrlConfig.Team team, double now, boolean ownerOnly) {
			if (car != null) {
				car.setLocal(s.local(), now);
				CarFxStates.apply(car, s, team, now);
				car.update(now);
			}
			if (body != null) {
				body.setLocal(s.local() || !ownerOnly, now);
				body.update(now);
			}
			if (boost != null) {
				boost.setLocal(s.local(), now);
				boost.setActive(s.boosting());
				boost.update(now);
			}
		}

		void release() {
			for (FxActor a : new FxActor[] {car, body, boost}) {
				if (a != null) {
					a.release();
				}
			}
		}

		private static @Nullable FxActorDef def(@Nullable FxActor a) {
			return a == null ? null : a.def();
		}
	}

	private final Map<FxActor, FxActorRef> refs = new IdentityHashMap<>();
	private final Int2ObjectOpenHashMap<Car> cars = new Int2ObjectOpenHashMap<>();
	private @Nullable FxActor ball;

	private FxActors() {
	}

	public static @Nullable FxActors game() {
		return game;
	}

	public static void register() {
		FxActors a = new FxActors();
		game = a;
		FrameEvents.register((mc, frame, frameNanos) -> {
			McrlConfig cfg = McrlConfig.get();
			a.frame(FrameSignals.current(), id -> ClientTeams.ofCar(mc.level, id), cfg.effects.ownerOnly());
		});
	}

	private void frame(FrameSignals f, IntFunction<McrlConfig.Team> teamOf, boolean ownerOnly) {
		RlCache rl = RlCache.loaded();
		Map<String, FxActorDef> d = rl == null ? Map.of() : rl.fxActors();
		if (d.isEmpty() && cars.isEmpty() && ball == null) {
			return;
		}
		double now = f.simSeconds();
		ObjectIterator<Int2ObjectMap.Entry<Car>> it = cars.int2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			Int2ObjectMap.Entry<Car> e = it.next();
			CarSignals s = f.car(e.getIntKey());
			if (s == null || s.demoed() || !e.getValue().matches(d)) {
				release(e.getValue());
				it.remove();
			}
		}
		for (CarSignals s : f.cars().values()) {
			if (s.demoed()) {
				continue;
			}
			FxActorDef bd = bodyDef(d, s.carId());
			Car c = cars.get(s.carId());
			if (c == null) {
				c = create(d, bd, s.local(), now, s.carId(), ownerOnly);
				if (c == null) {
					continue;
				}
				cars.put(s.carId(), c);
			} else if (Car.def(c.body()) != bd) {
				c = rebody(c, bd, s.local(), now, s.carId(), ownerOnly);
				cars.put(s.carId(), c);
			}
			c.frame(s, teamOf.apply(s.carId()), now, ownerOnly);
		}
		BallSignals b = f.ball();
		FxActorDef bd = d.get(BALL);
		if (ball != null && (b == null || ball.def() != bd)) {
			ball.release();
			refs.remove(ball);
			ball = null;
		}
		if (b != null && bd != null) {
			FxActor a = ball;
			if (a == null) {
				a = spawn(bd, false, now, -1, BALL);
				ball = a;
			}
			BallFxStates.apply(a, b, now);
			a.update(now);
		}
	}

	public @Nullable FxActor actor(int carId, String key) {
		Car c = cars.get(carId);
		if (c == null) {
			return null;
		}
		return switch (key) {
			case CAR -> c.car();
			case BODY -> c.body();
			case BOOST -> c.boost();
			default -> null;
		};
	}

	public @Nullable FxActor ball() {
		return ball;
	}

	public @Nullable FxActorRef ref(FxActor a) {
		return refs.get(a);
	}

	private void release(Car c) {
		c.release();
		for (FxActor a : new FxActor[] {c.car(), c.body(), c.boost()}) {
			if (a != null) {
				refs.remove(a);
			}
		}
	}

	private @Nullable FxActorDef bodyDef(Map<String, FxActorDef> d, int carId) {
		RlCache rl = RlCache.loaded();
		FxActorDef own = rl == null ? null : CarLooks.ofCar(rl, carId).bodyFx();
		return own != null ? own : d.get(BODY);
	}

	private Car rebody(Car c, @Nullable FxActorDef bd, boolean local, double now, int carId, boolean ownerOnly) {
		FxActor old = c.body();
		if (old != null) {
			old.release();
			refs.remove(old);
		}
		FxActor body = bd == null ? null : spawn(bd, local || !ownerOnly, now, carId, BODY);
		if (body != null && c.car() != null) {
			body.bindTo(c.car());
		}
		return new Car(c.car(), body, c.boost());
	}

	private @Nullable Car create(Map<String, FxActorDef> d, @Nullable FxActorDef bd, boolean local, double now,
			int carId, boolean ownerOnly) {
		FxActorDef cd = d.get(CAR), xd = d.get(BOOST);
		if (cd == null && bd == null && xd == null) {
			return null;
		}
		FxActor car = cd == null ? null : spawn(cd, local, now, carId, CAR),
				body = bd == null ? null : spawn(bd, local || !ownerOnly, now, carId, BODY),
				boost = xd == null ? null : spawn(xd, local, now, carId, BOOST);
		if (car != null) {
			if (body != null) {
				body.bindTo(car);
			}
			if (boost != null) {
				boost.bindTo(car);
			}
		}
		return new Car(car, body, boost);
	}

	private FxActor spawn(FxActorDef def, boolean local, double now, int carId, String key) {
		Deferred sink = new Deferred();
		FxActor a = new FxActor(def, local, sink);
		FxActorRef r = new FxActorRef(a, carId, key, null, new FxParams(), false);
		sink.target = FxSinks.forActor(r);
		refs.put(a, r);
		a.setState(FxState.SPAWNED, true, now);
		return a;
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
