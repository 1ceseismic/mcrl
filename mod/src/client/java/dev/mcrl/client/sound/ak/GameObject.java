package dev.mcrl.client.sound.ak;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.rlassets.wwise.HircModel.GameParameter;

public final class GameObject {
	private final long id;
	private final @Nullable GameObject parent;
	private @Nullable Globals globals;
	private final Map<Long, Ramp> rtpcs = new HashMap<>();
	private final Map<Long, Long> switches = new HashMap<>();
	private volatile Vec3 pos = Vec3.ZERO;
	private volatile Vec3 forward = new Vec3(0, 0, 1);

	private static final class Ramp {
		double value, target;

		Ramp(double value) {
			this.value = value;
			this.target = value;
		}
	}

	public GameObject(long id, @Nullable GameObject parent) {
		this.id = id;
		this.parent = parent;
	}

	public long id() {
		return id;
	}

	public @Nullable GameObject parent() {
		return parent;
	}

	void bind(Globals g) {
		for (GameObject o = this; o != null && o.globals == null; o = o.parent) {
			o.globals = g;
		}
	}

	private @Nullable Globals globals() {
		for (GameObject o = this; o != null; o = o.parent) {
			if (o.globals != null) {
				return o.globals;
			}
		}
		return null;
	}

	public void setRtpc(long param, double v) {
		Ramp r = rtpcs.get(param);
		if (r == null) {
			r = new Ramp(inherited(param));
			rtpcs.put(param, r);
		}
		r.target = v;
		Globals g = globals();
		GameParameter def = g == null ? null : g.parameter(param);
		if (def == null || def.ramp().equals("None") || r.value == v) {
			r.value = v;
		} else {
			g.ramping.add(this);
		}
	}

	public double rtpc(long param) {
		Ramp r = rtpcs.get(param);
		return r != null ? r.value : inherited(param);
	}

	private double inherited(long param) {
		if (parent != null) {
			return parent.rtpc(param);
		}
		Globals g = globals();
		return g == null ? 0 : g.rtpc(param);
	}

	boolean tick(double dt) {
		Globals g = globals();
		boolean moving = false;
		for (Iterator<Map.Entry<Long, Ramp>> it = rtpcs.entrySet().iterator(); it.hasNext();) {
			Map.Entry<Long, Ramp> e = it.next();
			Ramp r = e.getValue();
			if (r.value == r.target) {
				continue;
			}
			GameParameter def = g == null ? null : g.parameter(e.getKey());
			boolean up = r.target > r.value;
			double rate = def == null ? 0 : up ? def.up() : def.down();
			if (def == null || rate <= 0) {
				r.value = r.target;
			} else if (def.ramp().equals("SlewRate")) {
				double step = rate * dt;
				r.value = up ? Math.min(r.target, r.value + step) : Math.max(r.target, r.value - step);
			} else if (def.ramp().equals("FilteringOverTime")) {
				r.value += (r.target - r.value) * (1 - Math.exp(-dt / rate));
				if (Math.abs(r.target - r.value) < 1e-9 * Math.max(1, Math.abs(r.target))) {
					r.value = r.target;
				}
			} else {
				r.value = r.target;
			}
			moving |= r.value != r.target;
		}
		return moving;
	}

	public void setSwitch(long group, long value) {
		switches.put(group, value);
	}

	public long switchValue(long group) {
		Long v = switches.get(group);
		if (v != null) {
			return v;
		}
		return parent == null ? 0 : parent.switchValue(group);
	}

	public void setTransform(Vec3 pos, Vec3 forward) {
		this.pos = pos;
		this.forward = forward;
	}

	public Vec3 pos() {
		return pos;
	}

	public Vec3 forward() {
		return forward;
	}
}
