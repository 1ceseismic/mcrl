package dev.mcrl.client.link;

import java.util.ArrayList;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.net.McrlPayloads.KnockS2C;

public final class KnockReceiver {
	private static final double KNOCK_SYNC = 0.01;

	private record Flight(KnockFlight flight, Vec3 knockAt) {
	}

	private static final Int2ObjectOpenHashMap<Flight> FLIGHTS = new Int2ObjectOpenHashMap<>();

	private KnockReceiver() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(KnockS2C.TYPE, (payload, ctx) -> apply(ctx.client(), payload));
		ClientTickEvents.START_CLIENT_TICK.register(KnockReceiver::beforeTick);
		ClientTickEvents.END_CLIENT_TICK.register(KnockReceiver::afterTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> FLIGHTS.clear());
	}

	private static void apply(Minecraft mc, KnockS2C k) {
		if (mc.level == null) {
			return;
		}
		Entity e = mc.level.getEntity(k.entityId());
		if (e == null || e == mc.player || e.isLocalInstanceAuthoritative()) {
			return;
		}
		if (e.isInterpolating()) {
			e.getInterpolation().cancel();
		}
		e.snapTo(k.x(), k.y(), k.z());
		e.setDeltaMovement(k.vx(), k.vy(), k.vz());
		if (e instanceof LivingEntity mob) {
			float ground = KnockFlight.modifiedFriction(
					mc.level.getBlockState(mob.getBlockPosBelowThatAffectsMyMovement()).getBlock().getFriction(),
					(float) mob.getAttributeValue(Attributes.FRICTION_MODIFIER));
			KnockFlight f = new KnockFlight(k.x(), k.y(), k.z(), k.vx(), k.vy(), k.vz(), mob.onGround(), ground,
					(float) mob.getAttributeValue(Attributes.AIR_DRAG_MODIFIER), mob.getGravity(), k.y());
			Flight fl = new Flight(f, new Vec3(k.x(), k.y(), k.z()));
			FLIGHTS.put(e.getId(), fl);
			step(mc, e.getId(), fl, e);
		}
	}

	private static void beforeTick(Minecraft mc) {
		if (FLIGHTS.isEmpty()) {
			return;
		}
		if (mc.level == null) {
			FLIGHTS.clear();
			return;
		}
		for (int id : new ArrayList<>(FLIGHTS.keySet())) {
			Flight fl = FLIGHTS.get(id);
			Entity e = mc.level.getEntity(id);
			if (e == null || e.isRemoved()) {
				FLIGHTS.remove(id);
				continue;
			}
			if (!e.isInterpolating()) {
				continue;
			}
			PositionAndRotation target = e.getInterpolation().target();
			if (target == null) {
				continue;
			}
			Vec3 t = target.position();
			e.getInterpolation().cancel();
			if (t.distanceTo(fl.knockAt()) <= KNOCK_SYNC) {
				continue;
			}
			switch (fl.flight().onServerUpdate(t.x, t.y, t.z)) {
				case HOLD -> FLIGHTS.remove(id);
				case FOLLOW -> {
					e.moveOrInterpolateTo(t, target.yRot(), target.xRot());
					FLIGHTS.remove(id);
				}
			}
		}
	}

	private static void afterTick(Minecraft mc) {
		if (FLIGHTS.isEmpty() || mc.level == null) {
			return;
		}
		for (int id : new ArrayList<>(FLIGHTS.keySet())) {
			Flight fl = FLIGHTS.get(id);
			Entity e = mc.level.getEntity(id);
			if (e == null || e.isRemoved()) {
				FLIGHTS.remove(id);
				continue;
			}
			step(mc, id, fl, e);
		}
	}

	private static void step(Minecraft mc, int id, Flight fl, Entity e) {
		KnockFlight f = fl.flight();
		boolean flying = f.state() == KnockFlight.State.FLYING;
		if (f.tick()) {
			AABB box = e.getBoundingBox().move(f.x() - e.getX(), f.y() - e.getY(), f.z() - e.getZ());
			if (flying && !mc.level.noCollision(e, box)) {
				f.blocked();
			} else {
				e.setPos(f.x(), f.y(), f.z());
				e.setDeltaMovement(f.vx(), f.vy(), f.vz());
			}
		}
		if (f.state() == KnockFlight.State.DONE) {
			FLIGHTS.remove(id);
		}
	}
}
