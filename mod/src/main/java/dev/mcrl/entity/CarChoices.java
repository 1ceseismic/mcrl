package dev.mcrl.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CarChoices {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final int INTERVAL_TICKS = 10;

	private static final Map<UUID, CarChoice> CHOICES = new HashMap<>();
	private static final Set<UUID> PENDING = new HashSet<>();
	private static final Set<UUID> WARNED = new HashSet<>();

	private CarChoices() {
	}

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(CarChoices::tick);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> forget(handler.getPlayer().getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CHOICES.clear();
			PENDING.clear();
			WARNED.clear();
		});
	}

	public static void receive(ServerPlayer p, CarChoice c) {
		UUID id = p.getUUID();
		if (!c.valid()) {
			if (WARNED.add(id)) {
				LOG.warn("mcrl: ignoring an invalid car choice from {}: {}", p.getName().getString(), c);
			}
			return;
		}
		if (c.equals(CHOICES.get(id)) && !PENDING.contains(id)) {
			return;
		}
		LOG.debug("mcrl: car choice of {}: {}", p.getName().getString(), c);
		CHOICES.put(id, c);
		PENDING.add(id);
		apply(p.level().getServer(), id, c);
	}

	public static CarChoice get(ServerPlayer p) {
		return CHOICES.getOrDefault(p.getUUID(), CarChoice.DEFAULT);
	}

	public static void onLoad(CarEntity car) {
		UUID owner = car.owner();
		CarChoice c = owner == null ? null : CHOICES.get(owner);
		if (c != null && !car.matchOwned() && !car.choice().equals(c)) {
			PENDING.add(owner);
		}
	}

	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		for (UUID id : List.copyOf(PENDING)) {
			CarChoice c = CHOICES.get(id);
			if (c == null) {
				PENDING.remove(id);
			} else {
				apply(server, id, c);
			}
		}
	}

	private static void apply(MinecraftServer server, UUID owner, CarChoice c) {
		int now = server.getTickCount();
		boolean waiting = false;
		for (CarEntity car : carsOf(server, owner)) {
			if (car.choice().equals(c)) {
				continue;
			}
			if (car.isDemoed() || car.choiceChangedAt >= 0 && now - car.choiceChangedAt < INTERVAL_TICKS) {
				waiting = true;
				continue;
			}
			car.applyChoice(c);
			car.choiceChangedAt = now;
		}
		if (!waiting) {
			PENDING.remove(owner);
		}
	}

	private static List<CarEntity> carsOf(MinecraftServer server, UUID owner) {
		UUID current = CarOwners.current(owner);
		if (current != null) {
			for (ServerLevel level : server.getAllLevels()) {
				if (level.getEntity(current) instanceof CarEntity car && !car.isRemoved() && !car.matchOwned()
						&& owner.equals(car.owner())) {
					return List.of(car);
				}
			}
			return List.of();
		}
		List<CarEntity> out = new ArrayList<>();
		for (ServerLevel level : server.getAllLevels()) {
			out.addAll(level.getEntities(McrlEntities.CAR,
					x -> !x.isRemoved() && !x.matchOwned() && owner.equals(x.owner())));
		}
		return out;
	}

	private static void forget(UUID id) {
		CHOICES.remove(id);
		PENDING.remove(id);
	}
}
