package dev.mcrl.client.fx.particle;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;

public final class SystemInstance {
	public static final double MAX_STEP = 0.25;
	public static final double TELEPORT_UU = 500;
	private static final double REANCHOR_UU = 100_000;

	private final FxSystemDef def;
	private final FxParams params;
	private final EmitterInstance[] emitters;
	double cx, cy, cz, px, py, pz;
	final Quaternionf rot = new Quaternionf();
	double ax, ay, az;
	private boolean placed;
	private boolean active = true;
	boolean jumped;
	double vx, vy, vz;

	public SystemInstance(FxSystemDef def, long seed, FxParams params) {
		this.def = def;
		this.params = params;
		emitters = new EmitterInstance[def.emitters().size()];
		for (int i = 0; i < emitters.length; i++) {
			emitters[i] = new EmitterInstance(this, def.emitters().get(i), new FxRandom(FxRandom.emitterSeed(seed, i)));
		}
	}

	public FxSystemDef def() {
		return def;
	}

	public FxParams params() {
		return params;
	}

	public void setTransform(double x, double y, double z, Quaternionf mcRotation) {
		cx = x * 50;
		cy = z * 50;
		cz = y * 50;
		rot.set(-mcRotation.x, -mcRotation.z, -mcRotation.y, mcRotation.w);
		if (!placed) {
			placed = true;
			px = cx;
			py = cy;
			pz = cz;
			ax = cx;
			ay = cy;
			az = cz;
		}
	}

	public void positionMc(org.joml.Vector3d out) {
		out.set(cx / 50, cz / 50, cy / 50);
	}

	public void update(EmitterFrame f) {
		double dt = f.dt() > 0 ? Math.min(f.dt(), MAX_STEP) : 0;
		if (dt == 0) {
			return;
		}
		double dx = cx - px, dy = cy - py, dz = cz - pz;
		jumped = dx * dx + dy * dy + dz * dz > TELEPORT_UU * TELEPORT_UU;
		vx = jumped ? 0 : dx / dt;
		vy = jumped ? 0 : dy / dt;
		vz = jumped ? 0 : dz / dt;
		if (Math.abs(cx - ax) > REANCHOR_UU || Math.abs(cy - ay) > REANCHOR_UU || Math.abs(cz - az) > REANCHOR_UU) {
			float sx = (float) (ax - cx), sy = (float) (ay - cy), sz = (float) (az - cz);
			for (EmitterInstance e : emitters) {
				e.reanchor(sx, sy, sz);
			}
			ax = cx;
			ay = cy;
			az = cz;
		}
		for (EmitterInstance e : emitters) {
			e.update(f, dt);
		}
		px = cx;
		py = cy;
		pz = cz;
	}

	public void deactivate() {
		if (!active) {
			return;
		}
		active = false;
		for (EmitterInstance e : emitters) {
			e.deactivate();
		}
	}

	public void kill() {
		active = false;
		for (EmitterInstance e : emitters) {
			e.kill();
		}
	}

	public boolean active() {
		return active;
	}

	public boolean done() {
		for (EmitterInstance e : emitters) {
			if (!e.done()) {
				return false;
			}
		}
		return true;
	}

	public int live() {
		int n = 0;
		for (EmitterInstance e : emitters) {
			n += e.live();
		}
		return n;
	}

	public int emitterCount() {
		return emitters.length;
	}

	public EmitterInstance emitter(int i) {
		return emitters[i];
	}

	@Nullable EmitterInstance emitterNamed(String name) {
		for (EmitterInstance e : emitters) {
			if (e.def().name().equals(name)) {
				return e;
			}
		}
		return null;
	}
}
