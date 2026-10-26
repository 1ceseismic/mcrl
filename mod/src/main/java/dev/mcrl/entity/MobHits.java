package dev.mcrl.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.Mcrl;
import dev.mcrl.McrlGameRules;
import dev.mcrl.math.OrientedBox;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.mixin.ChunkMapAccessor;
import dev.mcrl.mixin.TrackedEntityAccessor;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.sim.TickRing;

public final class MobHits {
	public record Hit(double closing, double horizontal, Vec3 position, Vec3 delta, @Nullable Vec3 syncBase,
			float damage) {
	}

	public static final ResourceKey<DamageType> CAR_DAMAGE_TYPE =
			ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "car"));

	private static final Map<ServerLevel, MobHits> INSTANCES = new WeakHashMap<>();
	private static final int REMEMBER = 1024;
	private static final double RELOCATE_MAX = 8.0, RELOCATE_STEP = 0.1, RELOCATE_MARGIN = 0.05;

	private final Map<Integer, Long> lastProcessed = new HashMap<>();
	private final Map<Integer, Long> lastHitGameTick = new HashMap<>();
	private final Map<Integer, Integer> counts = remembered();
	private final Map<Integer, Hit> lastHits = remembered();

	private static <V> Map<Integer, V> remembered() {
		return new LinkedHashMap<>() {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Integer, V> eldest) {
				return size() > REMEMBER;
			}
		};
	}

	public static MobHits of(ServerLevel level) {
		return INSTANCES.computeIfAbsent(level, l -> new MobHits());
	}

	public static void clear() {
		INSTANCES.clear();
		BallHits.clear();
	}

	public static Runnable trigger(ServerLevel level) {
		MinecraftServer server = level.getServer();
		AtomicBoolean pending = new AtomicBoolean();
		Runnable task = () -> {
			pending.set(false);
			tick(level);
		};
		return () -> {
			if (pending.compareAndSet(false, true)) {
				server.schedule(server.wrapRunnable(task));
			}
		};
	}

	public static void tick(ServerLevel level) {
		SimHost host = SimHosts.get(level.dimension());
		if (host == null) {
			return;
		}
		of(level).run(level, host);
		BallHits.of(level).run(level, host);
	}

	public int hitCount(int entityId) {
		return counts.getOrDefault(entityId, 0);
	}

	public Hit lastHit(int entityId) {
		return lastHits.get(entityId);
	}

	public static double[] pushDirection(double vx, double vy, double vz, double carX, double carZ, double mobX,
			double mobZ) {
		double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
		if (speed > HitTuning.DIRECTION_SPEED_MIN) {
			return new double[] {vx / speed, vy / speed, vz / speed};
		}
		double[] h = centreToMob(carX, carZ, mobX, mobZ);
		return new double[] {h[0], 0, h[1]};
	}

	public static double[] centreToMob(double carX, double carZ, double mobX, double mobZ) {
		double dx = mobX - carX, dz = mobZ - carZ, d = Math.hypot(dx, dz);
		return d > 1e-6 ? new double[] {dx / d, dz / d} : new double[] {0, 1};
	}

	public static double closing(double vx, double vy, double vz, double[] dir) {
		return Math.max(0.0, vx * dir[0] + vy * dir[1] + vz * dir[2]);
	}

	public static double[] launchDirection(double[] dir, double carX, double carZ, double mobX, double mobZ) {
		double h = Math.hypot(dir[0], dir[2]);
		if (h > 1e-6) {
			return new double[] {dir[0] / h, dir[2] / h};
		}
		return centreToMob(carX, carZ, mobX, mobZ);
	}

	public static double[] knockDelta(double[] dir, double closing, double currentVy) {
		double h = HitTuning.horizontalSpeed(closing) / 20.0;
		double vy = HitTuning.separationOnly(closing) ? currentVy : HitTuning.popSpeed(closing) / 20.0;
		return new double[] {dir[0] * h, vy, dir[1] * h};
	}

	public static double objectPush(double closing, double mass) {
		double dv = (1 + HitTuning.OBJECT_RESTITUTION) * EntityMass.CAR / (EntityMass.CAR + mass) * closing;
		return Math.min(dv, HitTuning.OBJECT_PUSH_CAP);
	}

	public static double[] itemDelta(double[] carV) {
		double k = HitTuning.OBJECT_CARRY / 20.0;
		return new double[] {carV[0] * k, carV[1] * k + HitTuning.OBJECT_POP, carV[2] * k};
	}

	public static long resumeAfter(long last, long oldest, long newest) {
		if (last < 0 || oldest == Long.MAX_VALUE) {
			return last;
		}
		return last + 1 < oldest || newest < last ? -1 : last;
	}

	// Per entry: the entity's demoed flag lags a tick.
	static List<TickRing.Entry> liveEntries(List<TickRing.Entry> entries) {
		for (TickRing.Entry e : entries) {
			if (e.wreck()) {
				return entries.stream().filter(x -> !x.wreck()).toList();
			}
		}
		return entries;
	}

	private void run(ServerLevel level, SimHost host) {
		long now = level.getGameTime();
		lastHitGameTick.values().removeIf(t -> !HitTuning.coolingDown(t, now));
		List<? extends CarEntity> cars = level.getEntities(McrlEntities.CAR, c -> !c.isRemoved());
		lastProcessed.keySet().removeIf(id -> cars.stream().noneMatch(c -> c.getId() == id));
		for (CarEntity car : cars) {
			int id = car.carId();
			long last = lastProcessed.getOrDefault(id, -1L);
			List<TickRing.Entry> entries = host.ringSince(id, last);
			if (entries.isEmpty()) {
				List<TickRing.Entry> all = host.ringSince(id, -1);
				if (all.isEmpty() || resumeAfter(last, host.ringOldestTick(id), all.get(all.size() - 1).tick()) != -1) {
					continue;
				}
				entries = all;
			}
			lastProcessed.put(id, entries.get(entries.size() - 1).tick());
			List<TickRing.Entry> live = liveEntries(entries);
			if (!live.isEmpty()) {
				processCar(level, car, live, now);
			}
		}
	}

	private void processCar(ServerLevel level, CarEntity car, List<TickRing.Entry> entries, long now) {
		int n = entries.size();
		OrientedBox[] boxes = new OrientedBox[n], low = new OrientedBox[n];
		double[] cover = null;
		HitboxPreset preset = car.hitboxPreset();
		for (int i = 0; i < n; i++) {
			boxes[i] = OrientedBox.car(entries.get(i).pose(), preset);
			low[i] = boxes[i].extendedDown(HitTuning.LOOSE_REACH);
			double[] a = low[i].aabb();
			if (cover == null) {
				cover = a;
			} else {
				for (int k = 0; k < 3; k++) {
					cover[k] = Math.min(cover[k], a[k]);
					cover[k + 3] = Math.max(cover[k + 3], a[k + 3]);
				}
			}
		}
		AABB query = new AABB(cover[0], cover[1], cover[2], cover[3], cover[4], cover[5]);
		List<Entity> candidates = level.getEntities((Entity) null, query, e -> carCandidate(car, e)
				&& !HitTuning.coolingDown(lastHitGameTick.getOrDefault(e.getId(), Long.MIN_VALUE / 2), now));
		for (Entity e : new ArrayList<>(candidates)) {
			AABB bb = e.getBoundingBox();
			OrientedBox[] reach = isLoose(e) ? low : boxes;
			for (int i = 0; i < n; i++) {
				if (reach[i].intersects(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ)) {
					if (e instanceof LivingEntity le) {
						knock(level, car, le, entries.get(i), boxes, i, now);
					} else {
						knockObject(level, car, e, entries.get(i), boxes, i, now);
					}
					break;
				}
			}
		}
	}

	static boolean carCandidate(CarEntity car, Entity e) {
		if (e instanceof LivingEntity le) {
			return eligible(car, le);
		}
		return !e.isRemoved() && !e.isPassenger() && (isLoose(e) || isVehicle(e));
	}

	static boolean isLoose(Entity e) {
		return e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof FallingBlockEntity
				|| e instanceof PrimedTnt;
	}

	static boolean isVehicle(Entity e) {
		return e instanceof AbstractBoat || e instanceof AbstractMinecart;
	}

	private static boolean eligible(CarEntity car, LivingEntity e) {
		return e.isAlive() && !e.isSpectator() && !decorativeStand(e) && !e.isPassenger()
				&& !car.getPassengers().contains(e);
	}

	static boolean decorativeStand(Entity e) {
		return e instanceof ArmorStand a && (a.isMarker() || a.isPermanentlyInvulnerable() || a.isNoGravity());
	}

	private static Predicate<AABB> inCar(OrientedBox[] boxes, int first) {
		return moved -> {
			for (int i = first; i < boxes.length; i++) {
				if (boxes[i].intersects(moved.minX, moved.minY, moved.minZ, moved.maxX, moved.maxY, moved.maxZ)) {
					return true;
				}
			}
			return false;
		};
	}

	private static DamageSource carSource(ServerLevel level, CarEntity car) {
		return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(CAR_DAMAGE_TYPE),
				car, car.getControllingPassenger());
	}

	private void knock(ServerLevel level, CarEntity car, LivingEntity e, TickRing.Entry hit, OrientedBox[] boxes,
			int first, long now) {
		OrientedBox hitBox = boxes[first];
		AABB bb = e.getBoundingBox();
		double mobX = (bb.minX + bb.maxX) / 2, mobZ = (bb.minZ + bb.maxZ) / 2;
		double[] push = pushDirection(hit.vx(), hit.vy(), hit.vz(), hitBox.cx(), hitBox.cz(), mobX, mobZ);
		double closing = closing(hit.vx(), hit.vy(), hit.vz(), push);
		double[] dir = HitTuning.separationOnly(closing) ? centreToMob(hitBox.cx(), hitBox.cz(), mobX, mobZ)
				: launchDirection(push, hitBox.cx(), hitBox.cz(), mobX, mobZ);
		double h = HitTuning.horizontalSpeed(closing);

		float damage = e instanceof ArmorStand ? 0f
				: damageFor(level, car.getControllingPassenger() instanceof Player p ? p : null, e, HitTuning.damage(closing));
		if (damage > 0f) {
			e.hurtServer(level, carSource(level, car), damage);
		}

		Vec3 syncBase = null;
		// Killed mobs still fly; killed players stay put.
		if (e.isAlive() || !(e instanceof Player)) {
			relocate(level, e, dir, inCar(boxes, first));
			double[] d = knockDelta(dir, closing, e.getDeltaMovement().y);
			e.setDeltaMovement(d[0], d[1], d[2]);
			e.needsSync = true;
			if (e instanceof ServerPlayer) {
				e.syncVelocity = true;
			}
			// Knock first: the sync then matches the client's position.
			sendKnock(e);
			syncBase = flushSync(level, e);
		}

		lastHitGameTick.put(e.getId(), now);
		counts.merge(e.getId(), 1, Integer::sum);
		lastHits.put(e.getId(), new Hit(closing, h, e.position(), e.getDeltaMovement(), syncBase, damage));
	}

	private void knockObject(ServerLevel level, CarEntity car, Entity e, TickRing.Entry hit, OrientedBox[] boxes,
			int first, long now) {
		OrientedBox hitBox = boxes[first];
		AABB bb = e.getBoundingBox();
		double ox = (bb.minX + bb.maxX) / 2, oz = (bb.minZ + bb.maxZ) / 2;
		double[] push = pushDirection(hit.vx(), hit.vy(), hit.vz(), hitBox.cx(), hitBox.cz(), ox, oz);
		double closing = closing(hit.vx(), hit.vy(), hit.vz(), push);
		boolean slow = HitTuning.separationOnly(closing);
		double[] dir = slow ? centreToMob(hitBox.cx(), hitBox.cz(), ox, oz)
				: launchDirection(push, hitBox.cx(), hitBox.cz(), ox, oz);
		double h;
		float damage = 0f;
		if (isVehicle(e)) {
			if (closing >= HitTuning.OBJECT_BREAK_AT && level.getGameRules().get(McrlGameRules.CAR_DAMAGE)) {
				damage = HitTuning.OBJECT_BREAK_DAMAGE;
				e.hurtServer(level, carSource(level, car), damage);
			}
			h = slow ? HitTuning.SEPARATION_SPEED : objectPush(closing, EntityMass.of(e));
			if (!e.isRemoved()) {
				relocate(level, e, dir, inCar(boxes, first));
				e.setDeltaMovement(dir[0] * h / 20.0, e.getDeltaMovement().y, dir[1] * h / 20.0);
			}
		} else {
			if (slow) {
				return;
			}
			double[] d = itemDelta(new double[] {hit.vx(), hit.vy(), hit.vz()});
			e.setDeltaMovement(d[0], d[1], d[2]);
			h = Math.hypot(d[0], d[2]) * 20.0;
		}
		Vec3 syncBase = null;
		if (!e.isRemoved()) {
			e.needsSync = true;
			syncBase = flushSync(level, e);
		}

		lastHitGameTick.put(e.getId(), now);
		counts.merge(e.getId(), 1, Integer::sum);
		lastHits.put(e.getId(), new Hit(closing, h, e.position(), e.getDeltaMovement(), syncBase, damage));
	}

	static float damageFor(ServerLevel level, @Nullable Player driver, LivingEntity e, float damage) {
		if (damage <= 0f || !level.getGameRules().get(McrlGameRules.CAR_DAMAGE)) {
			return 0f;
		}
		if (driver != null && e instanceof OwnableEntity pet && pet.getOwnerReference() != null
				&& pet.getOwnerReference().getUUID().equals(driver.getUUID())) {
			return 0f;
		}
		if (e instanceof Player target && (!level.isPvpAllowed() || target.isCreative())) {
			return 0f;
		}
		return damage;
	}

	static void sendKnock(Entity e) {
		McrlPayloads.KnockS2C knock = new McrlPayloads.KnockS2C(e.getId(), e.getX(), e.getY(), e.getZ(),
				e.getDeltaMovement().x, e.getDeltaMovement().y, e.getDeltaMovement().z);
		for (ServerPlayer p : PlayerLookup.tracking(e)) {
			if (p != e && ServerPlayNetworking.canSend(p, McrlPayloads.KnockS2C.TYPE)) {
				ServerPlayNetworking.send(p, knock);
			}
		}
	}

	static @Nullable Vec3 flushSync(ServerLevel level, Entity e) {
		Object tracked = ((ChunkMapAccessor) (Object) level.getChunkSource().chunkMap).mcrl$entityMap().get(e.getId());
		if (tracked == null) {
			return null;
		}
		ServerEntity se = ((TrackedEntityAccessor) tracked).mcrl$serverEntity();
		se.sendChanges();
		return se.getPositionBase();
	}

	static void relocate(ServerLevel level, Entity e, double[] dir, Predicate<AABB> obstacle) {
		AABB bb = e.getBoundingBox();
		for (double d = RELOCATE_STEP; d <= RELOCATE_MAX; d += RELOCATE_STEP) {
			double m = d + RELOCATE_MARGIN, ox = dir[0] * m, oz = dir[1] * m;
			AABB moved = bb.move(ox, 0, oz);
			if (obstacle.test(moved)) {
				continue;
			}
			if (level.noCollision(e, moved)) {
				if (e instanceof ServerPlayer p) {
					p.teleportTo(e.getX() + ox, e.getY(), e.getZ() + oz);
				} else {
					e.setPos(e.getX() + ox, e.getY(), e.getZ() + oz);
				}
			}
			return;
		}
	}
}
