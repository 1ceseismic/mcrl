package dev.mcrl.entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.Mcrl;
import dev.mcrl.McrlGameRules;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.TickRing;

public final class BallHits {
	public record Hit(int entityId, long simTick, double closing, double[] dvBall, double[] dvEnt, float damage) {
	}

	public static final ResourceKey<DamageType> BALL_DAMAGE_TYPE =
			ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "ball"));

	private static final Map<ServerLevel, BallHits> INSTANCES = new WeakHashMap<>();
	private static final int REMEMBER = 256;
	private static final double TICKS_PER_SECOND = 20;

	private long lastProcessed = -1;
	private final Map<Integer, Long> lastHitGameTick = new HashMap<>();
	private final List<Hit> hits = new ArrayList<>();

	public static BallHits of(ServerLevel level) {
		return INSTANCES.computeIfAbsent(level, l -> new BallHits());
	}

	static void clear() {
		INSTANCES.clear();
	}

	public List<Hit> hits() {
		return Collections.unmodifiableList(hits);
	}

	public void run(ServerLevel level, SimHost host) {
		List<TickRing.Entry> entries = host.ballRingSince(lastProcessed);
		if (entries.isEmpty()) {
			List<TickRing.Entry> all = host.ballRingSince(-1);
			if (all.isEmpty() || MobHits.resumeAfter(lastProcessed, host.ballRingOldestTick(),
					all.get(all.size() - 1).tick()) != -1) {
				return;
			}
			entries = all;
		}
		lastProcessed = entries.get(entries.size() - 1).tick();
		BallEntity ball = BallEntity.active(level);
		if (ball == null || host.kind() != SimHost.Kind.TERRAIN
				|| !level.getGameRules().get(McrlGameRules.BALL_HITS_ENTITIES)) {
			return;
		}
		long now = level.getGameTime();
		lastHitGameTick.values().removeIf(t -> !HitTuning.coolingDown(t, now));
		double r = BallEntity.RADIUS;
		AABB cover = null;
		for (TickRing.Entry e : entries) {
			AABB a = new AABB(e.pose().x() - r, e.pose().y() - r, e.pose().z() - r, e.pose().x() + r, e.pose().y() + r,
					e.pose().z() + r);
			cover = cover == null ? a : cover.minmax(a);
		}
		List<Entity> candidates = level.getEntities((Entity) null, cover,
				e -> eligible(e) && !HitTuning.coolingDown(lastHitGameTick.getOrDefault(e.getId(), Long.MIN_VALUE / 2), now));
		TickRing.Entry newest = entries.get(entries.size() - 1);
		List<Touch> touches = new ArrayList<>();
		for (Entity e : candidates) {
			AABB bb = e.getBoundingBox();
			for (TickRing.Entry t : entries) {
				if (sphereTouches(t.pose().x(), t.pose().y(), t.pose().z(), r, bb)) {
					touches.add(new Touch(e, t, bb.getCenter().distanceToSqr(t.pose().x(), t.pose().y(), t.pose().z())));
					break;
				}
			}
		}
		touches.sort(Comparator.comparingLong((Touch t) -> t.at().tick()).thenComparingDouble(Touch::distSq)
				.thenComparingInt(t -> t.e().getId()));
		double[] dvRun = {0, 0, 0};
		for (Touch t : touches) {
			hit(level, host, ball, t.e(), t.at(), newest, now, dvRun);
		}
	}

	private record Touch(Entity e, TickRing.Entry at, double distSq) {
	}

	static boolean eligible(Entity e) {
		if (e instanceof CarEntity || e instanceof BallEntity || e.isRemoved()) {
			return false;
		}
		if (e instanceof Player p && p.isCreative() && p.getAbilities().flying) {
			return false;
		}
		if (e instanceof LivingEntity le) {
			return le.isAlive() && !le.isSpectator() && !le.isPassenger() && !MobHits.decorativeStand(le);
		}
		return !e.isPassenger() && (MobHits.isLoose(e) || MobHits.isVehicle(e));
	}

	static boolean sphereTouches(double cx, double cy, double cz, double r, AABB bb) {
		double dx = cx - Math.clamp(cx, bb.minX, bb.maxX), dy = cy - Math.clamp(cy, bb.minY, bb.maxY);
		double dz = cz - Math.clamp(cz, bb.minZ, bb.maxZ);
		return dx * dx + dy * dy + dz * dz <= r * r;
	}

	static double[] normal(double cx, double cy, double cz, AABB bb) {
		double px = Math.clamp(cx, bb.minX, bb.maxX), py = Math.clamp(cy, bb.minY, bb.maxY);
		double pz = Math.clamp(cz, bb.minZ, bb.maxZ);
		double nx = cx - px, ny = cy - py, nz = cz - pz, d = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (d < 1e-9) {
			Vec3 c = bb.getCenter();
			nx = cx - c.x;
			ny = cy - c.y;
			nz = cz - c.z;
			d = Math.sqrt(nx * nx + ny * ny + nz * nz);
		}
		return d < 1e-9 ? new double[] {0, 1, 0} : new double[] {nx / d, ny / d, nz / d};
	}

	private void hit(ServerLevel level, SimHost host, BallEntity ball, Entity e, TickRing.Entry at, TickRing.Entry newest,
			long now, double[] dvRun) {
		AABB bb = e.getBoundingBox();
		double cx = at.pose().x(), cy = at.pose().y(), cz = at.pose().z();
		double[] n = normal(cx, cy, cz, bb);
		Vec3 ve = e.getKnownMovement();
		double[] vBall = {at.vx() + dvRun[0], at.vy() + dvRun[1], at.vz() + dvRun[2]};
		double[] vEnt = {ve.x * TICKS_PER_SECOND, ve.y * TICKS_PER_SECOND, ve.z * TICKS_PER_SECOND};
		double restitution = e instanceof Player ? BallHitTuning.E_PLAYER
				: e instanceof LivingEntity ? BallHitTuning.E_MOB : BallHitTuning.E_OBJECT;
		double[] dv = BallHitTuning.exchangeAfter(new double[] {at.vx(), at.vy(), at.vz()}, dvRun, vEnt, n,
				EntityMass.of(e), restitution);
		if (dv == null) {
			return;
		}
		double closing = -((vBall[0] - vEnt[0]) * n[0] + (vBall[1] - vEnt[1]) * n[1] + (vBall[2] - vEnt[2]) * n[2]);
		double[] dvBall = {dv[0], dv[1], dv[2]}, dvEnt = {dv[3], dv[4], dv[5]};
		host.addVelocity(-1, dvBall[0], dvBall[1], dvBall[2]);

		float damage = 0f;
		if (e instanceof LivingEntity le) {
			Player driver = driverOf(level, host.lastBallToucher());
			damage = MobHits.damageFor(level, driver, le, BallHitTuning.damage(closing, le.getHealth()));
			if (damage > 0f) {
				DamageSource src = new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
						.getOrThrow(BALL_DAMAGE_TYPE), ball, driver);
				le.hurtServer(level, src, damage);
			}
		}

		double hx = -n[0], hz = -n[2], h = Math.hypot(hx, hz);
		if (h < 1e-6) {
			hx = vBall[0];
			hz = vBall[2];
			h = Math.hypot(hx, hz);
		}
		double[] dir = h < 1e-6 ? new double[] {0, 1} : new double[] {hx / h, hz / h};
		double speed = Math.sqrt(dvEnt[0] * dvEnt[0] + dvEnt[1] * dvEnt[1] + dvEnt[2] * dvEnt[2]);
		double r = BallEntity.RADIUS;
		Pose np = newest.pose();
		MobHits.relocate(level, e, dir, moved -> sphereTouches(np.x(), np.y(), np.z(), r, moved));
		double[] d;
		if (MobHits.isLoose(e)) {
			d = MobHits.itemDelta(vBall);
		} else if (-(vBall[0] * n[0] + vBall[1] * n[1] + vBall[2] * n[2]) < HitTuning.DIRECTION_SPEED_MIN) {
			d = MobHits.knockDelta(dir, 0, ve.y);
		} else {
			d = MobHits.knockDelta(dir, speed, ve.y);
		}
		e.setDeltaMovement(d[0], d[1], d[2]);
		e.needsSync = true;
		if (e instanceof ServerPlayer) {
			e.syncVelocity = true;
		}
		if (e instanceof LivingEntity) {
			MobHits.sendKnock(e);
		}
		MobHits.flushSync(level, e);

		lastHitGameTick.put(e.getId(), now);
		hits.add(new Hit(e.getId(), at.tick(), closing, dvBall, dvEnt, damage));
		if (hits.size() > REMEMBER) {
			hits.remove(0);
		}
	}

	private static @Nullable Player driverOf(ServerLevel level, int carId) {
		if (carId < 0) {
			return null;
		}
		return level.getEntity(carId) instanceof CarEntity car && car.getControllingPassenger() instanceof Player p ? p
				: null;
	}
}
