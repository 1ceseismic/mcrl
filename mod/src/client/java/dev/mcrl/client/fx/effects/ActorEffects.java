package dev.mcrl.client.fx.effects;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.AttachmentDef;

public abstract class ActorEffects implements FxEffect {
	protected final FxContext ctx;
	private final List<Entry> entries = new ArrayList<>();
	private boolean stopped, removed;
	private final Vector3f v = new Vector3f(), origin = new Vector3f();
	private final Quaternionf q = new Quaternionf();

	protected ActorEffects(FxContext ctx) {
		this.ctx = ctx;
	}

	protected abstract @Nullable Pose pose();

	private static final class Entry {
		final FxActorRef ref;
		final AttachmentDef def;
		final @Nullable Group group;
		final @Nullable String template;
		final @Nullable FxParams params;
		final Vector3f offset;
		final Quaternionf rot;
		final boolean absoluteRotation;
		final List<SystemInstance> instances = new ArrayList<>(2);
		boolean detaching;
		boolean gated;

		Entry(FxActorRef ref, AttachmentDef def, @Nullable Group group, @Nullable String template,
				@Nullable FxParams params, Vector3f offset, Quaternionf rot, boolean absoluteRotation) {
			this.ref = ref;
			this.def = def;
			this.group = group;
			this.template = template;
			this.params = params;
			this.offset = offset;
			this.rot = rot;
			this.absoluteRotation = absoluteRotation;
		}

		@Nullable SystemInstance active() {
			if (instances.isEmpty()) {
				return null;
			}
			SystemInstance s = instances.get(instances.size() - 1);
			return s.active() ? s : null;
		}
	}

	void attachParticles(FxActorRef r, AttachmentDef a, @Nullable Group group, String template, FxParams params,
			Vector3f offset, Quaternionf rot, boolean absoluteRotation) {
		Entry e = new Entry(r, a, group, template, params, offset, rot, absoluteRotation);
		entries.add(e);
		if (group != null && !ctx.settings().on(group)) {
			e.gated = true;
		} else {
			start(e);
		}
	}

	void attachStatic(FxActorRef r, AttachmentDef a, @Nullable Group group, Vector3f offset, Quaternionf rot) {
		entries.add(new Entry(r, a, group, null, null, offset, rot, false));
	}

	boolean detach(FxActorRef r, AttachmentDef a) {
		boolean done = true;
		for (Entry e : entries) {
			if (e.ref == r && e.def == a && !e.detaching) {
				e.detaching = true;
				SystemInstance s = e.active();
				if (s != null) {
					s.deactivate();
				}
				for (SystemInstance i : e.instances) {
					done &= i.done();
				}
			}
		}
		entries.removeIf(e -> e.detaching && e.instances.stream().allMatch(SystemInstance::done));
		return done;
	}

	private void start(Entry e) {
		FxSystemDef def = e.template == null ? null : ctx.system(e.template);
		if (def == null) {
			return;
		}
		SystemInstance s = new SystemInstance(def, ctx.seed(), e.params);
		Pose p = pose();
		if (p != null) {
			place(s, e, p);
		}
		e.instances.add(s);
	}

	private void place(SystemInstance s, Entry e, Pose p) {
		q.set(p.qx(), p.qy(), p.qz(), p.qw());
		q.transform(v.set(e.offset).add(ctx.actorOrigin(e.ref, origin)));
		s.setTransform(p.x() + v.x, p.y() + v.y, p.z() + v.z, e.absoluteRotation ? q.set(e.rot) : q.mul(e.rot));
	}

	@Override
	public void update(FxFrame f) {
		Pose p = pose();
		if (p == null && !stopped) {
			stop();
		}
		for (Entry e : entries) {
			boolean on = e.group == null || f.settings().on(e.group);
			SystemInstance live = e.active();
			if (e.template != null && !e.detaching && !stopped) {
				if (live != null && !on) {
					live.deactivate();
					e.gated = true;
				} else if (live == null && on && e.gated) {
					e.gated = false;
					start(e);
				}
			}
			for (SystemInstance s : e.instances) {
				if (p != null) {
					place(s, e, p);
				}
				s.update(f.emitter());
			}
			if (!e.instances.isEmpty()) {
				SystemInstance last = e.instances.get(e.instances.size() - 1);
				e.instances.removeIf(s -> s != last && s.done());
			}
		}
		for (int i = entries.size() - 1; i >= 0; i--) {
			Entry e = entries.get(i);
			if (e.detaching && e.instances.stream().allMatch(SystemInstance::done)) {
				entries.remove(i);
				if (!finishing(e.ref, e.def)) {
					e.ref.actor().finished(e.def.name());
				}
			}
		}
	}

	private boolean finishing(FxActorRef r, AttachmentDef a) {
		for (Entry e : entries) {
			if (e.ref == r && e.def == a && e.detaching) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void collect(FxDrawList out) {
		Pose p = pose();
		for (Entry e : entries) {
			for (SystemInstance s : e.instances) {
				out.add(s);
			}
			if (e.template == null && p != null && !e.detaching && !stopped && !e.gated
					&& (e.group == null || ctx.settings().on(e.group))) {
				out.addStatic(new FxDrawList.Static(e.ref, e.def, p, e.offset, e.rot));
			}
		}
	}

	@Override
	public boolean done() {
		return entries.isEmpty();
	}

	@Override
	public void removed() {
		removed = true;
		Runnable r = onRemoved;
		onRemoved = null;
		if (r != null) {
			r.run();
		}
	}

	@Nullable Runnable onRemoved;

	boolean isRemoved() {
		return removed;
	}

	@Override
	public void stop() {
		stopped = true;
		for (Entry e : entries) {
			e.detaching = true;
			SystemInstance s = e.active();
			if (s != null) {
				s.deactivate();
			}
		}
	}

	public List<SystemInstance> systems() {
		List<SystemInstance> out = new ArrayList<>();
		for (Entry e : entries) {
			out.addAll(e.instances);
		}
		return out;
	}

	public static final class Registry {
		private final FxContext ctx;
		private final Map<Integer, CarEffects> cars = new HashMap<>();
		private @Nullable BallEffects ball;
		private final Map<FxActorRef, FreeEffects> free = new IdentityHashMap<>();

		public Registry(FxContext ctx) {
			this.ctx = ctx;
		}

		FxContext context() {
			return ctx;
		}

		ActorEffects get(FxActorRef r) {
			if (r.carId() >= 0) {
				CarEffects c = cars.get(r.carId());
				if (c == null || c.isRemoved()) {
					CarEffects made = ctx.world().add(new CarEffects(ctx, r.carId()));
					int id = r.carId();
					made.onRemoved = () -> cars.remove(id, made);
					cars.put(id, made);
					c = made;
				}
				return c;
			}
			Pose origin = r.origin();
			if (origin == null) {
				if (ball == null || ball.isRemoved()) {
					BallEffects made = ctx.world().add(new BallEffects(ctx));
					made.onRemoved = () -> {
						if (ball == made) {
							ball = null;
						}
					};
					ball = made;
				}
				return ball;
			}
			FreeEffects f = free.get(r);
			if (f == null || f.isRemoved()) {
				FreeEffects made = ctx.world().add(new FreeEffects(ctx, origin));
				made.onRemoved = () -> free.remove(r, made);
				free.put(r, made);
				f = made;
			}
			return f;
		}

		@Nullable ActorEffects existing(FxActorRef r) {
			ActorEffects e = r.carId() >= 0 ? cars.get(r.carId()) : r.origin() == null ? ball : free.get(r);
			return e == null || e.isRemoved() ? null : e;
		}
	}
}
