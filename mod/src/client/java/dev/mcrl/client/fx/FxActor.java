package dev.mcrl.client.fx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;

import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.FxActorDef;

public final class FxActor {
	public enum AttachState {
		DETACHED, ATTACHED, PENDING_DETACH
	}

	public interface Sink {
		void activate(AttachmentDef a);

		boolean deactivate(AttachmentDef a);
	}

	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Set<String> UNKNOWN_LOGGED = ConcurrentHashMap.newKeySet();
	private static final String RENDER_INACTIVE_CLASS = "FXActor_Boost_TA";

	private static final class Attachment {
		final AttachmentDef def;
		final long any, all, detach;
		final boolean allCanHold;
		boolean want;
		double changeTime, attachedAt;
		AttachState state = AttachState.DETACHED;

		Attachment(AttachmentDef def, Set<String> unknown) {
			this.def = def;
			any = mask(def.attachAny(), unknown);
			all = mask(def.attachAll(), unknown);
			detach = mask(def.detachAny(), unknown);
			boolean known = true;
			for (String n : def.attachAll()) {
				known &= FxState.byRlName(n) != null;
			}
			allCanHold = !def.attachAll().isEmpty() && known;
		}

		private static long mask(List<String> names, Set<String> unknown) {
			long m = 0;
			for (String n : names) {
				FxState s = FxState.byRlName(n);
				if (s == null) {
					unknown.add(n);
				} else {
					m |= bit(s);
				}
			}
			return m;
		}
	}

	private static final class Event {
		final FxState state;
		boolean ticked;

		Event(FxState state) {
			this.state = state;
		}
	}

	private final FxActorDef def;
	private final Sink sink;
	private final boolean renderInactive;
	private final Attachment[] attachments;
	private final Map<String, Attachment> byName = new HashMap<>();
	private boolean local;
	private long states;
	private boolean autoActivate = true, active;
	private final List<Event> events = new ArrayList<>(0);
	private final List<FxActor> children = new ArrayList<>(0);
	private @Nullable FxActor parent;
	private double lastNow = Double.NaN, offset;

	public FxActor(FxActorDef def, boolean local, Sink sink) {
		this.def = def;
		this.local = local;
		this.sink = Objects.requireNonNull(sink);
		JsonElement ri = def.props().get("bRenderInactive");
		renderInactive = ri != null && ri.isJsonPrimitive() ? ri.getAsBoolean()
				: def.className().endsWith(RENDER_INACTIVE_CLASS);
		List<AttachmentDef> list = def.attachments();
		attachments = new Attachment[list.size()];
		Set<String> unknown = new TreeSet<>();
		for (int i = 0; i < attachments.length; i++) {
			attachments[i] = new Attachment(list.get(i), unknown);
			byName.putIfAbsent(list.get(i).name(), attachments[i]);
		}
		for (String n : unknown) {
			if (UNKNOWN_LOGGED.add(n)) {
				LOG.warn("mcrl: FX state {} (in {}) is not one mcrl knows; attachments never attach on it", n,
						def.path());
			}
		}
	}

	public FxActorDef def() {
		return def;
	}

	public boolean local() {
		return local;
	}

	public boolean isOn(FxState s) {
		return (states & bit(s)) != 0;
	}

	public void setState(FxState s, boolean on, double now) {
		change(s, on, now);
	}

	public void fire(FxState e, double now) {
		events.add(new Event(e));
		if (!isOn(e)) {
			change(e, true, now);
		}
	}

	public void setActive(boolean on) {
		autoActivate = on;
	}

	public void setLocal(boolean local, double now) {
		if (this.local != local) {
			this.local = local;
			reevaluate(time(now));
		}
	}

	public void bindTo(FxActor parent) {
		if (parent == this) {
			throw new IllegalArgumentException("an FX actor cannot bind to itself");
		}
		unbind();
		this.parent = parent;
		parent.children.add(this);
		double now = Double.isNaN(lastNow) ? parent.lastNow
				: Double.isNaN(parent.lastNow) ? lastNow : Math.max(lastNow, parent.lastNow);
		if (Double.isNaN(now)) {
			now = 0;
		}
		for (FxState s : FxState.values()) {
			if (s != FxState.ACTIVE && parent.isOn(s)) {
				change(s, true, now);
			}
		}
	}

	public void update(double now) {
		double t = time(now);
		if (active != autoActivate) {
			active = autoActivate;
			if (!change(FxState.ACTIVE, active, now)) {
				reevaluate(t);
			}
		}
		for (int i = events.size() - 1; i >= 0; i--) {
			Event e = events.get(i);
			if (!isOn(e.state)) {
				continue;
			}
			if (!e.ticked) {
				e.ticked = true;
			} else {
				events.remove(i);
				change(e.state, false, now);
			}
		}
		for (Attachment a : attachments) {
			step(a, t);
		}
	}

	public void finished(String name) {
		for (Attachment a : attachments) {
			if (a.state == AttachState.PENDING_DETACH && a.def.name().equals(name)) {
				a.state = AttachState.DETACHED;
			}
		}
	}

	public AttachState state(String name) {
		Attachment a = byName.get(name);
		if (a == null) {
			throw new IllegalArgumentException("no attachment " + name + " in " + def.path());
		}
		return a.state;
	}

	public void release() {
		unbind();
		for (Attachment a : attachments) {
			AttachState was = a.state;
			a.state = AttachState.DETACHED;
			a.want = false;
			if (was == AttachState.ATTACHED) {
				sink.deactivate(a.def);
			}
		}
	}

	private void unbind() {
		if (parent != null) {
			parent.children.remove(this);
			parent = null;
		}
	}

	private boolean change(FxState s, boolean on, double now) {
		long b = bit(s);
		if (((states & b) != 0) == on) {
			return false;
		}
		states = on ? states | b : states & ~b;
		reevaluate(time(now));
		if (s != FxState.ACTIVE) {
			for (FxActor c : children) {
				c.change(s, on, now);
			}
		}
		return true;
	}

	private void reevaluate(double t) {
		for (Attachment a : attachments) {
			boolean w = wants(a);
			if (w != a.want) {
				a.want = w;
				a.changeTime = t;
			}
		}
	}

	private boolean wants(Attachment a) {
		long s = states;
		boolean w = (a.allCanHold && (s & a.all) == a.all) || (s & a.any) != 0;
		return w && (s & a.detach) == 0 && (active || renderInactive) && switch (a.def.target()) {
			case ALL -> true;
			case LOCAL -> local;
			case NON_LOCAL -> !local;
		};
	}

	private void step(Attachment a, double t) {
		AttachmentDef d = a.def;
		switch (a.state) {
			case DETACHED -> {
				if (a.want && since(a.changeTime, t) >= d.attachDelay()) {
					attach(a, t);
				}
			}
			case ATTACHED -> {
				if (d.lifeTime() > 0 && a.want && since(a.attachedAt, t) >= d.lifeTime()) {
					a.want = false;
					a.changeTime = t;
				}
				if (!a.want && since(a.changeTime, t) >= d.detachDelay()) {
					detach(a);
				}
			}
			case PENDING_DETACH -> {
				if (a.want) {
					attach(a, t);
				}
			}
		}
	}

	private void attach(Attachment a, double t) {
		a.state = AttachState.ATTACHED;
		a.attachedAt = t;
		sink.activate(a.def);
	}

	private void detach(Attachment a) {
		a.state = AttachState.PENDING_DETACH;
		if (sink.deactivate(a.def) && a.state == AttachState.PENDING_DETACH) {
			a.state = AttachState.DETACHED;
		}
	}

	private double time(double now) {
		if (now < lastNow) {
			offset += lastNow - now;
		}
		lastNow = now;
		return now + offset;
	}

	private static float since(double from, double t) {
		return (float) (t - from);
	}

	private static long bit(FxState s) {
		return 1L << s.ordinal();
	}
}
