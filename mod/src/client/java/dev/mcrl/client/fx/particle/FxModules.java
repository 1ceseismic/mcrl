package dev.mcrl.client.fx.particle;

import static dev.mcrl.client.fx.particle.ParticleBuffer.A;
import static dev.mcrl.client.fx.particle.ParticleBuffer.AX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.AY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.AZ;
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
import static dev.mcrl.client.fx.particle.ParticleBuffer.D0;
import static dev.mcrl.client.fx.particle.ParticleBuffer.DMX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.DMY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.DMZ;
import static dev.mcrl.client.fx.particle.ParticleBuffer.G;
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
import static dev.mcrl.client.fx.particle.ParticleBuffer.VX;
import static dev.mcrl.client.fx.particle.ParticleBuffer.VY;
import static dev.mcrl.client.fx.particle.ParticleBuffer.VZ;

import org.joml.Vector3f;

import dev.mcrl.client.fx.particle.FxEmitterDef.SubUv;
import dev.mcrl.rlassets.model.Distribution;

public final class FxModules {
	private FxModules() {
	}

	static void spawn(EmitterInstance e, FxModule m, int i, float tau) {
		float[][] c = e.p.f;
		float[] v = e.tmp;
		Vector3f w = e.w;
		FxParams prm = e.params();
		switch (m) {
			case FxModule.Lifetime l -> c[LIFE][i] += Math.max(0, FxDistributions.scalar(l.lifeTime(), tau, e.rng, prm));
			case FxModule.Size s -> {
				vec(s.startSize(), tau, e, v);
				c[BSX][i] += v[0];
				c[BSY][i] += v[1];
				c[BSZ][i] += v[2];
			}
			case FxModule.Color col -> {
				vec(col.color(), tau, e, v);
				float a = FxDistributions.scalar(col.alpha(), tau, e.rng, prm);
				c[BR][i] = v[0];
				c[BG][i] = v[1];
				c[BB][i] = v[2];
				c[BA][i] = col.clampAlpha() ? clamp01(a) : a;
			}
			case FxModule.ColorOverLife col -> {
				vec(col.color(), 0, e, v);
				float a = FxDistributions.scalar(col.alpha(), 0, e.rng, prm);
				c[BR][i] = v[0];
				c[BG][i] = v[1];
				c[BB][i] = v[2];
				c[BA][i] = col.clampAlpha() ? clamp01(a) : a;
			}
			case FxModule.Velocity vel -> {
				vec(vel.start(), tau, e, v);
				e.toParticleSpace(w.set(v[0], v[1], v[2]), vel.world());
				float radial = FxDistributions.scalar(vel.radial(), tau, e.rng, prm);
				if (radial != 0) {
					float ox = c[PX][i] - e.ox, oy = c[PY][i] - e.oy, oz = c[PZ][i] - e.oz;
					float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
					if (len > 1e-6f) {
						w.add(ox / len * radial, oy / len * radial, oz / len * radial);
					}
				}
				addBaseVelocity(c, i, w);
			}
			case FxModule.VelocityInheritParent inh -> {
				vec(inh.scale(), tau, e, v);
				e.systemVelocity(w, inh.world());
				w.mul(v[0], v[1], v[2]);
				float len = w.length();
				if (inh.maxAdded() > 0 && len > inh.maxAdded()) {
					w.mul(inh.maxAdded() / len);
				}
				e.toParticleSpace(w, inh.world());
				addBaseVelocity(c, i, w);
			}
			case FxModule.Acceleration acc -> {
				vec(acc.acceleration(), tau, e, v);
				e.toParticleSpace(w.set(v[0], v[1], v[2]), acc.world());
				c[AX][i] = w.x;
				c[AY][i] = w.y;
				c[AZ][i] = w.z;
			}
			case FxModule.Rotation r -> c[ROT][i] += FxDistributions.scalar(r.start(), tau, e.rng, prm);
			case FxModule.RotationRate r -> c[ROT_RATE][i] += FxDistributions.scalar(r.start(), tau, e.rng, prm);
			case FxModule.MeshRotation r -> {
				vec(r.start(), tau, e, v);
				c[MRX][i] += v[0];
				c[MRX + 1][i] += v[1];
				c[MRX + 2][i] += v[2];
			}
			case FxModule.MeshRotationRate r -> {
				vec(r.start(), tau, e, v);
				c[MRRX][i] += v[0];
				c[MRRX + 1][i] += v[1];
				c[MRRX + 2][i] += v[2];
			}
			case FxModule.Location loc -> {
				vec(loc.start(), tau, e, v);
				addPosition(c, i, e.toParticleSpace(w.set(v[0], v[1], v[2]), false));
			}
			case FxModule.LocationWorldOffset loc -> {
				vec(loc.start(), tau, e, v);
				addPosition(c, i, e.toParticleSpace(w.set(v[0], v[1], v[2]), true));
			}
			case FxModule.LocationSphere sp -> sphere(e, sp, i, tau);
			case FxModule.LocationCylinder cy -> cylinder(e, cy, i, tau);
			case FxModule.LocationEmitter le -> e.spawnAtSource(le, i);
			case FxModule.Orbit o -> {
				if (o.offsetOptions().spawn()) {
					orbit(o.offset(), o.offsetOptions(), 0, tau, e, c, OOX, i);
				}
				if (o.rotationOptions().spawn()) {
					orbit(o.rotation(), o.rotationOptions(), 0, tau, e, c, ORX, i);
				}
				if (o.rateOptions().spawn()) {
					orbit(o.rate(), o.rateOptions(), 0, tau, e, c, ORRX, i);
				}
			}
			case FxModule.SubUV s -> {
				SubUv how = e.def().required().interpolation();
				if (how == SubUv.RANDOM || how == SubUv.RANDOM_BLEND) {
					c[SUB][i] = e.rng.nextInt(e.subImages());
				}
			}
			case FxModule.ParameterDynamic d -> dynamic(e, d, i, 0, tau, true);
			case FxModule.Collision col -> {
				vec(col.damping(), tau, e, v);
				c[DMX][i] = v[0];
				c[DMY][i] = v[1];
				c[DMZ][i] = v[2];
				c[MAXC][i] = Math.max(1, Math.round(FxDistributions.scalar(col.maxCollisions(), tau, e.rng, prm)));
			}
			default -> {
			}
		}
	}

	static void update(EmitterInstance e, FxModule m, int i, float t, float dt, float tau) {
		float[][] c = e.p.f;
		float[] v = e.tmp;
		Vector3f w = e.w;
		FxParams prm = e.params();
		switch (m) {
			case FxModule.SizeMultiplyLife s -> {
				vec(s.multiplier(), t, e, v);
				if (s.x()) {
					c[SX][i] *= v[0];
				}
				if (s.y()) {
					c[SY][i] *= v[1];
				}
				if (s.z()) {
					c[SZ][i] *= v[2];
				}
			}
			case FxModule.SizeScale s -> {
				vec(s.scale(), t, e, v);
				if (s.x()) {
					c[SX][i] = c[BSX][i] * v[0];
				}
				if (s.y()) {
					c[SY][i] = c[BSY][i] * v[1];
				}
				if (s.z()) {
					c[SZ][i] = c[BSZ][i] * v[2];
				}
			}
			case FxModule.ColorOverLife col -> {
				vec(col.color(), t, e, v);
				float a = FxDistributions.scalar(col.alpha(), t, e.rng, prm);
				c[R][i] = v[0];
				c[G][i] = v[1];
				c[B][i] = v[2];
				c[A][i] = col.clampAlpha() ? clamp01(a) : a;
			}
			case FxModule.ColorScaleOverLife col -> {
				float at = col.emitterTime() ? tau : t;
				vec(col.color(), at, e, v);
				c[R][i] *= v[0];
				c[G][i] *= v[1];
				c[B][i] *= v[2];
				c[A][i] *= FxDistributions.scalar(col.alpha(), at, e.rng, prm);
			}
			case FxModule.VelocityOverLifetime vol -> {
				vec(vol.scale(), t, e, v);
				if (vol.absolute()) {
					e.toParticleSpace(w.set(v[0], v[1], v[2]), vol.world());
					c[VX][i] = w.x;
					c[VY][i] = w.y;
					c[VZ][i] = w.z;
				} else {
					c[VX][i] *= v[0];
					c[VY][i] *= v[1];
					c[VZ][i] *= v[2];
				}
			}
			case FxModule.Acceleration acc -> {
				addVelocity(c, i, c[AX][i] * dt, c[AY][i] * dt, c[AZ][i] * dt);
			}
			case FxModule.AccelerationOverLifetime acc -> {
				if (dt > 0) {
					vec(acc.acceleration(), t, e, v);
					e.toParticleSpace(w.set(v[0], v[1], v[2]), acc.world());
					addVelocity(c, i, w.x * dt, w.y * dt, w.z * dt);
				}
			}
			case FxModule.SourceMovement sm -> {
				if (dt > 0 && !e.def().required().localSpace()) {
					vec(sm.scale(), t, e, v);
					c[PX][i] += (float) e.moveX() * v[0];
					c[PY][i] += (float) e.moveY() * v[1];
					c[PZ][i] += (float) e.moveZ() * v[2];
				}
			}
			case FxModule.Orbit o -> {
				if (o.offsetOptions().update()) {
					orbit(o.offset(), o.offsetOptions(), t, tau, e, c, OOX, i);
				}
				if (o.rotationOptions().update()) {
					orbit(o.rotation(), o.rotationOptions(), t, tau, e, c, ORX, i);
				}
				if (o.rateOptions().update()) {
					orbit(o.rate(), o.rateOptions(), t, tau, e, c, ORRX, i);
				}
			}
			case FxModule.SubUV s -> {
				SubUv how = e.def().required().interpolation();
				if (how == SubUv.LINEAR || how == SubUv.LINEAR_BLEND) {
					float idx = FxDistributions.scalar(s.index(), t, e.rng, prm);
					float f = how == SubUv.LINEAR_BLEND ? idx : (float) Math.floor(idx);
					c[SUB][i] = Math.max(0, Math.min(e.subImages() - 1, f));
				}
			}
			case FxModule.ParameterDynamic d -> dynamic(e, d, i, t, tau, false);
			case FxModule.CameraOffset co -> c[CAM][i] = FxDistributions.scalar(co.offset(), t, e.rng, prm);
			default -> {
			}
		}
	}

	private static void dynamic(EmitterInstance e, FxModule.ParameterDynamic d, int i, float t, float tau,
			boolean atSpawn) {
		float[][] c = e.p.f;
		for (int k = 0; k < 4 && k < d.params().size(); k++) {
			FxModule.DynParam dp = d.params().get(k);
			if (atSpawn || !dp.spawnOnly()) {
				c[D0 + k][i] = FxDistributions.scalar(dp.value(), dp.emitterTime() ? tau : t, e.rng, e.params());
			}
		}
	}

	private static void orbit(Distribution d, FxModule.OrbitOptions o, float t, float tau, EmitterInstance e,
			float[][] c, int col, int i) {
		vec(d, o.emitterTime() ? tau : t, e, e.tmp);
		c[col][i] = e.tmp[0];
		c[col + 1][i] = e.tmp[1];
		c[col + 2][i] = e.tmp[2];
	}

	private static void sphere(EmitterInstance e, FxModule.LocationSphere sp, int i, float tau) {
		float[] v = e.tmp;
		Vector3f dir = e.w2;
		unitDirection(e, sp.axes(), dir, !sp.surfaceOnly());
		if (sp.surfaceOnly()) {
			normalize(dir);
		}
		float radius = FxDistributions.scalar(sp.radius(), tau, e.rng, e.params());
		vec(sp.center(), tau, e, v);
		float[][] c = e.p.f;
		Vector3f w = e.w;
		addPosition(c, i, e.toParticleSpace(w.set(v[0] + dir.x * radius, v[1] + dir.y * radius,
				v[2] + dir.z * radius), false));
		if (sp.velocity()) {
			float scale = FxDistributions.scalar(sp.velocityScale(), tau, e.rng, e.params());
			addBaseVelocity(c, i, e.toParticleSpace(w.set(dir).mul(radius * scale), false));
		}
	}

	private static void cylinder(EmitterInstance e, FxModule.LocationCylinder cy, int i, float tau) {
		int h = Math.max(0, Math.min(2, cy.heightAxis())), r0 = h == 0 ? 1 : 0, r1 = h == 2 ? 1 : 2;
		float[] d = e.tmp2;
		int axes = cy.axes();
		for (int tries = 0; tries < 16; tries++) {
			for (int k = 0; k < 3; k++) {
				d[k] = axis(e, axes >> (2 * k));
			}
			if (d[r0] * d[r0] + d[r1] * d[r1] <= 1) {
				break;
			}
		}
		if (cy.surfaceOnly()) {
			float len = (float) Math.sqrt(d[r0] * d[r0] + d[r1] * d[r1]);
			if (len > 1e-6f) {
				d[r0] /= len;
				d[r1] /= len;
			} else {
				d[r0] = 1;
				d[r1] = 0;
			}
		}
		float radius = FxDistributions.scalar(cy.radius(), tau, e.rng, e.params());
		float half = FxDistributions.scalar(cy.height(), tau, e.rng, e.params()) / 2;
		float[] off = e.tmp3;
		off[r0] = d[r0] * radius;
		off[r1] = d[r1] * radius;
		off[h] = d[h] * half;
		float[] v = e.tmp;
		vec(cy.center(), tau, e, v);
		float[][] c = e.p.f;
		Vector3f w = e.w;
		addPosition(c, i, e.toParticleSpace(w.set(v[0] + off[0], v[1] + off[1], v[2] + off[2]), false));
		if (cy.velocity()) {
			if (cy.radialVelocity()) {
				off[h] = 0;
			}
			float scale = FxDistributions.scalar(cy.velocityScale(), tau, e.rng, e.params());
			addBaseVelocity(c, i, e.toParticleSpace(w.set(off[0], off[1], off[2]).mul(scale), false));
		}
	}

	private static void unitDirection(EmitterInstance e, int axes, Vector3f out, boolean ball) {
		for (int tries = 0; tries < 16; tries++) {
			out.set(axis(e, axes), axis(e, axes >> 2), axis(e, axes >> 4));
			if (!ball || out.lengthSquared() <= 1) {
				return;
			}
		}
	}

	private static float axis(EmitterInstance e, int bits) {
		boolean pos = (bits & 1) != 0, neg = (bits & 2) != 0;
		float r = e.rng.nextFloat();
		return pos && neg ? r * 2 - 1 : pos ? r : neg ? -r : 0;
	}

	private static void normalize(Vector3f v) {
		float len = v.length();
		if (len > 1e-6f) {
			v.div(len);
		} else {
			v.set(0, 0, 1);
		}
	}

	static void vec(Distribution d, float t, EmitterInstance e, float[] v) {
		int n = FxDistributions.components(d);
		FxDistributions.value(d, t, e.rng, e.params(), v);
		for (int k = n; k < 3; k++) {
			v[k] = v[0];
		}
	}

	private static void addPosition(float[][] c, int i, Vector3f w) {
		c[PX][i] += w.x;
		c[PY][i] += w.y;
		c[PZ][i] += w.z;
	}

	private static void addBaseVelocity(float[][] c, int i, Vector3f w) {
		c[BVX][i] += w.x;
		c[BVY][i] += w.y;
		c[BVZ][i] += w.z;
		c[VX][i] += w.x;
		c[VY][i] += w.y;
		c[VZ][i] += w.z;
	}

	private static void addVelocity(float[][] c, int i, float x, float y, float z) {
		c[BVX][i] += x;
		c[BVY][i] += y;
		c[BVZ][i] += z;
		c[VX][i] += x;
		c[VY][i] += y;
		c[VZ][i] += z;
	}

	private static float clamp01(float a) {
		return a < 0 ? 0 : a > 1 ? 1 : a;
	}

	public static void rotate(float roll, float pitch, float yaw, Vector3f v) {
		double r = roll * 2 * Math.PI, p = pitch * 2 * Math.PI, y = yaw * 2 * Math.PI;
		double sr = Math.sin(r), cr = Math.cos(r), sp = Math.sin(p), cp = Math.cos(p), sy = Math.sin(y), cy = Math.cos(y);
		double m00 = cp * cy, m01 = cp * sy, m02 = sp;
		double m10 = sr * sp * cy - cr * sy, m11 = sr * sp * sy + cr * cy, m12 = -sr * cp;
		double m20 = -(cr * sp * cy + sr * sy), m21 = cy * sr - cr * sp * sy, m22 = cr * cp;
		float x = v.x, yy = v.y, z = v.z;
		v.set((float) (x * m00 + yy * m10 + z * m20), (float) (x * m01 + yy * m11 + z * m21),
				(float) (x * m02 + yy * m12 + z * m22));
	}
}
