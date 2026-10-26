package dev.mcrl.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class CarOwners {
	public static final int DESPAWN_TICKS = 1200;
	public static final int CHECK_EVERY = 20;

	private static final Map<UUID, UUID> CURRENT = new HashMap<>();
	private static final List<CarEntity> DISCARD = new ArrayList<>();

	private CarOwners() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CURRENT.clear();
			DISCARD.clear();
		});
	}

	public static void claim(ServerPlayer p, CarEntity car) {
		UUID owner = p.getUUID();
		car.setOwner(owner);
		CURRENT.put(owner, car.getUUID());
		MinecraftServer server = p.level().getServer();
		for (ServerLevel level : server.getAllLevels()) {
			for (CarEntity other : List.copyOf(level.getEntities(McrlEntities.CAR,
					c -> !c.isRemoved() && c != car && owner.equals(c.owner())))) {
				other.discard();
			}
		}
	}

	public static @Nullable UUID current(UUID owner) {
		return CURRENT.get(owner);
	}

	public static boolean superseded(CarEntity car) {
		UUID owner = car.owner();
		if (owner == null) {
			return false;
		}
		@Nullable UUID current = CURRENT.get(owner);
		return current != null && !current.equals(car.getUUID());
	}

	public static void discardLater(CarEntity car) {
		DISCARD.add(car);
	}

	public static void tick(MinecraftServer server) {
		for (CarEntity car : List.copyOf(DISCARD)) {
			if (!car.isRemoved()) {
				car.discardQuietly();
			}
		}
		DISCARD.clear();
		int now = server.getTickCount();
		if (now % CHECK_EVERY != 0) {
			return;
		}
		for (ServerLevel level : server.getAllLevels()) {
			for (CarEntity car : List.copyOf(level.getEntities(McrlEntities.CAR, c -> !c.isRemoved()))) {
				UUID owner = car.owner();
				if (owner == null || car.isVehicle() || server.getPlayerList().getPlayer(owner) != null) {
					car.offlineSince = -1;
				} else if (car.offlineSince < 0) {
					car.offlineSince = now;
				} else if (now - car.offlineSince >= DESPAWN_TICKS) {
					car.discard();
				}
			}
		}
	}
}
