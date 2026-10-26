package dev.mcrl.client.fx.particle;

import static dev.mcrl.client.fx.particle.ParticleBuffer.A;
import static dev.mcrl.client.fx.particle.ParticleBuffer.AGE;
import static dev.mcrl.client.fx.particle.ParticleBuffer.B;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BA;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BB;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BG;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BR;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BSX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BSY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BSZ;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BVX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BVY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.BVZ;
import static dev.mcrl.client.fx.particle.ParticleBuffer.CAM;
import static dev.mcrl.client.fx.particle.ParticleBuffer.COLLISIONS;
import static dev.mcrl.client.fx.particle.ParticleBuffer.D0;
import static dev.mcrl.client.fx.particle.ParticleBuffer.DMX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.DMY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.DMZ;
import static dev.mcrl.client.fx.particle.ParticleBuffer.FROZEN;
import static dev.mcrl.client.fx.particle.ParticleBuffer.G;
import static dev.mcrl.client.fx.particle.ParticleBuffer.HALTED;
import static dev.mcrl.client.fx.particle.ParticleBuffer.LIFE;
import static dev.mcrl.client.fx.particle.ParticleBuffer.MAXC;
import static dev.mcrl.client.fx.particle.ParticleBuffer.MRRX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.MRX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.OOX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.ORRX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.ORX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.PX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.PY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.PZ;
import static dev.mcrl.client.fx.particle.ParticleBuffer.R;
import static dev.mcrl.client.fx.particle.ParticleBuffer.ROT;
import static dev.mcrl.client.fx.particle.ParticleBuffer.ROT_RATE;
import static dev.mcrl.client.fx.particle.ParticleBuffer.SUB;
import static dev.mcrl.client.fx.particle.ParticleBuffer.SX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.SY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.SZ;
import static dev.mcrl.client.fx.particle.ParticleBuffer.TRAIL;
import static dev.mcrl.client.fx.particle.ParticleBuffer.VX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.VY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.VZ;

import java.util.Arrays;

import org.jspecify.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FxBudget;
import dev.mcrl.client.fx.particle.FxEmitterDef.Burst;
import dev.mcrl.client.fx.particle.FxEmitterDef.Kind;
import dev.mcrl.client.fx.particle.FxEmitterDef.PerUnit;
import dev.mcrl.client.fx.particle.FxEmitterDef.Required;

public final class EmitterInstance {
	// Float slack: 60 frames at 60/s spawn 60.
	private static final double EPS = 1e-6;
	private static final double SURFACE_GAP = 0.01;

	private final SystemInstance sys;
	private final FxEmitterDef def;
	final FxRandom rng;
	final ParticleBuffer p = new ParticleBuffer();
	private final boolean scaled;
	private final float dur, offset, period;
	private final int loops;
	private final FxModule[] modules;
	private final FxModule.@Nullable Collision collision;
	private final int trails;
	private final float[] trailOffsets;
	private final int[] trailCount;
	final BeamData.@Nullable Noise noise;
	private double time;
	private double burstsDoneTo = -1;
	private double rateCarry, unitCarry;
	private boolean active = true;
	private long spawned;
	private int sourceIndex;
	final float[] tmp = new float[4], tmp2 = new float[3], tmp3 = new float[3];
	final Vector3f w = new Vector3f(), w2 = new Vector3f();
	float ox, oy, oz;
	private final Vector3f v = new Vector3f();
	private final Vector3d d = new Vector3d();

	EmitterInstance(SystemInstance sys, FxEmitterDef def, FxRandom rng) {
		this.sys = sys;
		this.def = def;
		this.rng = rng;
		Required r = def.required();
		Kind k = def.typeData().kind();
		scaled = k == Kind.SPRITE || k == Kind.MESH;
		dur = Math.max(1e-3f, r.duration());
		offset = Math.max(0, r.delay());
		period = r.delayFirstLoopOnly() ? dur : offset + dur;
		loops = Math.max(0, r.loops());
		modules = def.modules().toArray(new FxModule[0]);
		FxModule.Collision col = null;
		for (FxModule m : modules) {
			if (m instanceof FxModule.Collision c) {
				col = c;
			}
		}
		collision = col;
		FxEmitterDef.Ribbon ribbon = def.typeData().ribbon();
		trails = k == Kind.RIBBON && ribbon != null ? ribbon.maxTrails() : 1;
		trailCount = new int[trails];
		float[] offsets = new float[0];
		FxModule.BeamNoise beamNoise = null;
		for (FxModule m : modules) {
			if (m instanceof FxModule.TrailSource ts) {
				offsets = ts.offsets();
			} else if (m instanceof FxModule.BeamNoise bn) {
				beamNoise = bn;
			}
		}
		trailOffsets = offsets;
		FxEmitterDef.Beam beam = def.typeData().beam();
		noise = k == Kind.BEAM && beamNoise != null ? new BeamData.Noise(beamNoise, beam != null ? beam.maxBeams() : 1)
				: null;
	}

	public FxEmitterDef def() {
		return def;
	}

	FxParams params() {
		return sys.params();
	}

	int subImages() {
		return def.required().subImagesH() * def.required().subImagesV();
	}

	public int live() {
		return p.size();
	}

	public int drawCount() {
		int max = def.required().maxDrawCount();
		return max < 0 ? p.size() : Math.min(max, p.size());
	}

	public boolean active() {
		return active;
	}

	public int trails() {
		return trails;
	}

	public int trail(int i) {
		return (int) p.f[TRAIL][i];
	}

	public long spawned() {
		return spawned;
	}

	public boolean done() {
		return (!active || spawningOver(time)) && p.size() == 0;
	}

	public float loopTime() {
		return loopTime(time);
	}

	void update(EmitterFrame f, double dt) {
		double t0 = time, t1 = time + dt;
		time = t1;
		float tau = loopTime(t1);
		advance((float) dt, tau, f.collider());
		if (noise != null) {
			noise.update(this, t1, tau);
		}
		if (!active || spawningOver(t0)) {
			return;
		}
		if (def.typeData().kind() == Kind.BEAM) {
			FxEmitterDef.Beam beam = def.typeData().beam();
			int want = activeTime(t0, t1) > 0 ? (beam != null ? beam.maxBeams() : 1) - p.size() : 0;
			for (int k = 0; k < want; k++) {
				if (!spawnOne(1, 0, tau, 0)) {
					break;
				}
			}
			return;
		}
		double factor = scaled ? f.spawnFactor() : 1;
		double inLoop = activeTime(t0, t1);
		int n = 0;
		boolean processRate = def.spawn().processRate();
		PerUnit pu = def.perUnit();
		if (pu != null && inLoop > 0) {
			double dx = sys.cx - sys.px, dy = sys.cy - sys.py, dz = sys.cz - sys.pz;
			double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
			double limit = pu.maxFrameDistance() > 0 ? pu.maxFrameDistance() : SystemInstance.TELEPORT_UU;
			boolean moved = false;
			if (dist > limit) {
				unitCarry = 0;
			} else if (dist > pu.movementTolerance() * pu.unitScalar() && pu.unitScalar() > 0) {
				float perUnit = Math.max(0, FxDistributions.scalar(pu.spawnPerUnit(), tau, rng, params()));
				unitCarry += dist * perUnit / pu.unitScalar() * factor;
				int k = (int) Math.floor(unitCarry + EPS);
				unitCarry = Math.max(0, unitCarry - k);
				n += k;
				moved = true;
			}
			processRate &= pu.processSpawnRate() && !(pu.ignoreRateWhenMoving() && moved);
		}
		if (processRate && inLoop > 0) {
			rateCarry += def.spawn().rate(tau / dur, rng, params()) * factor * inLoop;
			int k = (int) Math.floor(rateCarry + EPS);
			rateCarry = Math.max(0, rateCarry - k);
			n += k;
		}
		for (int k = 0; k < n; k++) {
			double frac = (k + 1) / (double) n;
			if (!spawnEvent(frac, (float) ((1 - frac) * dt), tau)) {
				break;
			}
		}
		if (def.spawn().processBursts() && !def.spawn().bursts().isEmpty()) {
			bursts(t1, factor, tau);
		}
		FxEmitterDef.Ribbon ribbon = def.typeData().ribbon();
		if (ribbon != null && def.typeData().kind() == Kind.RIBBON) {
			capTrails(ribbon.maxPointsPerTrail());
		}
	}

	private boolean spawnEvent(double frac, float age, float tau) {
		for (int t = 0; t < trails; t++) {
			if (!spawnOne(frac, age, tau, t)) {
				return false;
			}
		}
		return true;
	}

	private void capTrails(int max) {
		Arrays.fill(trailCount, 0);
		boolean any = false;
		for (int i = p.size() - 1; i >= 0; i--) {
			int t = Math.min(trails - 1, Math.max(0, (int) p.f[TRAIL][i]));
			if (++trailCount[t] > max) {
				p.markDead(i);
				any = true;
			}
		}
		if (any) {
			p.removeDead();
		}
	}

	private void advance(float dt, float tau, FxCollider collider) {
		float[][] c = p.f;
		boolean anyDead = false;
		for (int i = 0; i < p.size(); i++) {
			float age = c[AGE][i] += dt;
			float life = c[LIFE][i];
			if (life > 0 && age >= life) {
				p.markDead(i);
				anyDead = true;
			}
		}
		if (anyDead) {
			p.removeDead();
		}
		anyDead = false;
		for (int i = 0; i < p.size(); i++) {
			float life = c[LIFE][i];
			derive(i, life > 0 ? c[AGE][i] / life : 0, dt, tau);
			if (c[COLLISIONS][i] == FROZEN) {
				c[VX][i] = c[VY][i] = c[VZ][i] = 0;
				c[BVX][i] = c[BVY][i] = c[BVZ][i] = 0;
				continue;
			}
			float fx = c[PX][i], fy = c[PY][i], fz = c[PZ][i];
			c[PX][i] += c[VX][i] * dt;
			c[PY][i] += c[VY][i] * dt;
			c[PZ][i] += c[VZ][i] * dt;
			c[ROT][i] += c[ROT_RATE][i] * dt;
			for (int k = 0; k < 3; k++) {
				c[MRX + k][i] += c[MRRX + k][i] * dt;
			}
			if (collision != null && c[COLLISIONS][i] >= 0 && collide(i, fx, fy, fz, tau, collider)) {
				anyDead = true;
			}
		}
		if (anyDead) {
			p.removeDead();
		}
	}

	private void derive(int i, float t, float dt, float tau) {
		float[][] c = p.f;
		c[VX][i] = c[BVX][i];
		c[VY][i] = c[BVY][i];
		c[VZ][i] = c[BVZ][i];
		c[SX][i] = c[BSX][i];
		c[SY][i] = c[BSY][i];
		c[SZ][i] = c[BSZ][i];
		c[R][i] = c[BR][i];
		c[G][i] = c[BG][i];
		c[B][i] = c[BB][i];
		c[A][i] = c[BA][i];
		for (FxModule m : modules) {
			FxModules.update(this, m, i, t, dt, tau);
		}
	}

	private boolean collide(int i, float fx, float fy, float fz, float tau, FxCollider collider) {
		float[][] c = p.f;
		FxModule.Collision col = collision;
		if (c[AGE][i] < FxDistributions.scalar(col.delay(), tau, rng, params())) {
			return false;
		}
		toWorldMc(fx, fy, fz, d);
		Vec3 from = new Vec3(d.x, d.y, d.z);
		toWorldMc(c[PX][i], c[PY][i], c[PZ][i], d);
		FxCollider.Hit hit = collider.clip(from, new Vec3(d.x, d.y, d.z));
		if (hit == null) {
			return false;
		}
		Vec3 n = hit.normal(), at = hit.point();
		fromWorldMc(at.x + n.x * SURFACE_GAP, at.y + n.y * SURFACE_GAP, at.z + n.z * SURFACE_GAP, v);
		c[PX][i] = v.x;
		c[PY][i] = v.y;
		c[PZ][i] = v.z;
		toParticleSpace(v.set((float) n.x, (float) n.z, (float) n.y), true);
		float dot = c[BVX][i] * v.x + c[BVY][i] * v.y + c[BVZ][i] * v.z;
		c[BVX][i] = (c[BVX][i] - 2 * dot * v.x) * c[DMX][i];
		c[BVY][i] = (c[BVY][i] - 2 * dot * v.y) * c[DMY][i];
		c[BVZ][i] = (c[BVZ][i] - 2 * dot * v.z) * c[DMZ][i];
		c[VX][i] = c[BVX][i];
		c[VY][i] = c[BVY][i];
		c[VZ][i] = c[BVZ][i];
		if (++c[COLLISIONS][i] >= c[MAXC][i]) {
			switch (col.completion()) {
				case KILL -> {
					p.markDead(i);
					return true;
				}
				case FREEZE -> {
					c[COLLISIONS][i] = FROZEN;
					c[VX][i] = c[VY][i] = c[VZ][i] = 0;
					c[BVX][i] = c[BVY][i] = c[BVZ][i] = 0;
				}
				case HALT -> c[COLLISIONS][i] = HALTED;
			}
		}
		return false;
	}

	private void bursts(double t1, double factor, float tau) {
		if (t1 < offset) {
			burstsDoneTo = t1;
			return;
		}
		long kStart = burstsDoneTo < offset ? 0 : (long) Math.floor((burstsDoneTo - offset) / period);
		long kEnd = (long) Math.floor((t1 - offset) / period);
		if (loops > 0) {
			kEnd = Math.min(kEnd, loops - 1);
		}
		for (long k = kStart; k <= kEnd && k < kStart + 1000; k++) {
			double start = offset + k * period;
			for (Burst b : def.spawn().bursts()) {
				double at = start + b.time() * dur;
				if (at > burstsDoneTo && at <= t1) {
					int count = b.countLow() >= 0 && b.count() > b.countLow()
							? b.countLow() + rng.nextInt(b.count() - b.countLow() + 1) : b.count();
					count = FxBudget.burst(count, factor);
					for (int i = 0; i < count; i++) {
						if (!spawnEvent(1, 0, tau)) {
							break;
						}
					}
				}
			}
		}
		burstsDoneTo = t1;
	}

	private boolean spawnOne(double frac, float age, float tau, int trail) {
		int i = p.add();
		if (i < 0) {
			return false;
		}
		spawned++;
		float[][] c = p.f;
		if (def.required().localSpace()) {
			ox = oy = oz = 0;
		} else {
			double g = sys.jumped ? 1 : frac;
			ox = (float) (sys.px + (sys.cx - sys.px) * g - sys.ax);
			oy = (float) (sys.py + (sys.cy - sys.py) * g - sys.ay);
			oz = (float) (sys.pz + (sys.cz - sys.pz) * g - sys.az);
		}
		if (3 * trail + 2 < trailOffsets.length) {
			toParticleSpace(v.set(trailOffsets[3 * trail], trailOffsets[3 * trail + 1], trailOffsets[3 * trail + 2]),
					false);
			ox += v.x;
			oy += v.y;
			oz += v.z;
		}
		c[TRAIL][i] = trail;
		c[PX][i] = ox;
		c[PY][i] = oy;
		c[PZ][i] = oz;
		for (int col = R; col <= BA; col++) {
			c[col][i] = 1;
		}
		for (FxModule m : modules) {
			FxModules.spawn(this, m, i, tau);
		}
		c[AGE][i] = age;
		c[PX][i] += c[BVX][i] * age;
		c[PY][i] += c[BVY][i] * age;
		c[PZ][i] += c[BVZ][i] * age;
		float life = c[LIFE][i];
		derive(i, life > 0 ? age / life : 0, 0, tau);
		return true;
	}

	void spawnAtSource(FxModule.LocationEmitter le, int i) {
		EmitterInstance src = sys.emitterNamed(le.emitterName());
		if (src == null || src == this || src.live() == 0) {
			return;
		}
		int k = sourceIndex++ % src.live();
		src.positionRl(k, d);
		fromWorldRl(d.x, d.y, d.z, v);
		float[][] c = p.f;
		c[PX][i] = v.x;
		c[PY][i] = v.y;
		c[PZ][i] = v.z;
		if (le.inheritVelocity()) {
			src.velocityRl(k, d);
			toParticleSpace(v.set((float) d.x, (float) d.y, (float) d.z), true).mul(le.velocityScale());
			c[BVX][i] += v.x;
			c[BVY][i] += v.y;
			c[BVZ][i] += v.z;
		}
	}

	void deactivate() {
		active = false;
		if (def.required().killOnDeactivate()) {
			p.clear();
			return;
		}
		for (int i = 0; i < p.size(); i++) {
			if (p.f[LIFE][i] == 0) {
				p.markDead(i);
			}
		}
		p.removeDead();
	}

	void kill() {
		active = false;
		p.clear();
	}

	void reanchor(float dx, float dy, float dz) {
		if (!def.required().localSpace()) {
			p.shift(dx, dy, dz);
		}
	}

	Vector3f toParticleSpace(Vector3f vec, boolean worldAxes) {
		boolean local = def.required().localSpace();
		if (worldAxes && local) {
			sys.rot.transformInverse(vec);
		} else if (!worldAxes && !local) {
			sys.rot.transform(vec);
		}
		return vec;
	}

	void systemVelocity(Vector3f out, boolean worldAxes) {
		out.set((float) sys.vx, (float) sys.vy, (float) sys.vz);
		if (!worldAxes) {
			sys.rot.transformInverse(out);
		}
	}

	double moveX() {
		return sys.cx - sys.px;
	}

	double moveY() {
		return sys.cy - sys.py;
	}

	double moveZ() {
		return sys.cz - sys.pz;
	}

	private void toWorldMc(float x, float y, float z, Vector3d out) {
		toWorldRl(x, y, z, out);
		out.set(out.x / 50, out.z / 50, out.y / 50);
	}

	private void toWorldRl(float x, float y, float z, Vector3d out) {
		if (def.required().localSpace()) {
			sys.rot.transform(v.set(x, y, z));
			out.set(sys.cx + v.x, sys.cy + v.y, sys.cz + v.z);
		} else {
			out.set(sys.ax + x, sys.ay + y, sys.az + z);
		}
	}

	private void fromWorldMc(double x, double y, double z, Vector3f out) {
		fromWorldRl(x * 50, z * 50, y * 50, out);
	}

	private void fromWorldRl(double x, double y, double z, Vector3f out) {
		if (def.required().localSpace()) {
			sys.rot.transformInverse(out.set((float) (x - sys.cx), (float) (y - sys.cy), (float) (z - sys.cz)));
		} else {
			out.set((float) (x - sys.ax), (float) (y - sys.ay), (float) (z - sys.az));
		}
	}

	public void trailSourceRl(int t, Vector3d out) {
		v.set(0, 0, 0);
		if (3 * t + 2 < trailOffsets.length) {
			v.set(trailOffsets[3 * t], trailOffsets[3 * t + 1], trailOffsets[3 * t + 2]);
			sys.rot.transform(v);
		}
		out.set(sys.cx + v.x, sys.cy + v.y, sys.cz + v.z);
	}

	void particleAxesToWorld(Vector3f vec) {
		if (def.required().localSpace()) {
			sys.rot.transform(vec);
		}
	}

	public Vector3f emitterAxisMc(int k, Vector3f out) {
		sys.rot.transform(v.set(k == 0 ? 1 : 0, k == 1 ? 1 : 0, k == 2 ? 1 : 0));
		return out.set(v.x, v.z, v.y);
	}

	public void emitterToWorldRl(float x, float y, float z, Vector3d out) {
		sys.rot.transform(v.set(x, y, z));
		out.set(sys.cx + v.x, sys.cy + v.y, sys.cz + v.z);
	}

	public void positionRl(int i, Vector3d out) {
		toWorldRl(p.f[PX][i], p.f[PY][i], p.f[PZ][i], out);
	}

	public void positionMc(int i, Vector3d out) {
		toWorldMc(p.f[PX][i], p.f[PY][i], p.f[PZ][i], out);
	}

	public void drawPositionRl(int i, Vector3d out) {
		positionRl(i, out);
		float[][] c = p.f;
		float age = c[AGE][i];
		v.set(c[OOX][i], c[OOX + 1][i], c[OOX + 2][i]);
		if (v.x != 0 || v.y != 0 || v.z != 0) {
			FxModules.rotate(c[ORX][i] + c[ORRX][i] * age, c[ORX + 1][i] + c[ORRX + 1][i] * age,
					c[ORX + 2][i] + c[ORRX + 2][i] * age, v);
			sys.rot.transform(v);
			out.add(v.x, v.y, v.z);
		}
	}

	public void velocityRl(int i, Vector3d out) {
		v.set(p.f[VX][i], p.f[VY][i], p.f[VZ][i]);
		if (def.required().localSpace()) {
			sys.rot.transform(v);
		}
		out.set(v.x, v.y, v.z);
	}

	public float age(int i) {
		return p.f[AGE][i];
	}

	public float life(int i) {
		return p.f[LIFE][i];
	}

	public float sizeX(int i) {
		return p.f[SX][i];
	}

	public float sizeY(int i) {
		return p.f[SY][i];
	}

	public float sizeZ(int i) {
		return p.f[SZ][i];
	}

	public void color(int i, float[] out) {
		out[0] = p.f[R][i];
		out[1] = p.f[G][i];
		out[2] = p.f[B][i];
		out[3] = p.f[A][i];
	}

	public float rotation(int i) {
		return p.f[ROT][i];
	}

	public Vector3f meshRotation(int i, Vector3f out) {
		return out.set(p.f[MRX][i], p.f[MRX + 1][i], p.f[MRX + 2][i]);
	}

	public int subImage(int i) {
		return (int) p.f[SUB][i];
	}

	public float subImageBlend(int i) {
		float s = p.f[SUB][i];
		return s - (float) Math.floor(s);
	}

	public float dynamic(int i, int k) {
		return p.f[D0 + k][i];
	}

	public float cameraOffset(int i) {
		return p.f[CAM][i];
	}

	private boolean spawningOver(double t) {
		return loops > 0 && t >= offset + (loops - 1) * (double) period + dur;
	}

	private float loopTime(double t) {
		if (t < offset) {
			return 0;
		}
		long k = (long) Math.floor((t - offset) / period);
		if (loops > 0 && k >= loops) {
			return dur;
		}
		return (float) Math.min(t - offset - k * (double) period, dur);
	}

	private double activeTime(double a, double b) {
		if (b <= offset) {
			return 0;
		}
		long k0 = a <= offset ? 0 : (long) Math.floor((a - offset) / period);
		long k1 = (long) Math.floor((b - offset) / period);
		if (loops > 0) {
			k1 = Math.min(k1, loops - 1);
		}
		double total = 0;
		for (long k = k0; k <= k1 && k < k0 + 1000; k++) {
			double ws = offset + k * (double) period, we = ws + dur;
			total += Math.max(0, Math.min(b, we) - Math.max(a, ws));
		}
		return total;
	}
}
