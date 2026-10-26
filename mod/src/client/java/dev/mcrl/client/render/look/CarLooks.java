package dev.mcrl.client.render.look;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import dev.mcrl.client.rl.BodyJobs;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.rlassets.bodies.BodyIds;

public final class CarLooks {
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private CarLooks() {
	}

	public static void register() {
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			REQUESTED.clear();
			BodyJobs.game().newSession();
			client.execute(RlLookAssets::retireBodies);
		});
	}

	public static BodyLook of(RlCache rl, String bodyId) {
		BodyLook look = rl.bodies().look(bodyId);
		if (look != null) {
			return look;
		}
		requestOnce(bodyId);
		return rl.bodies().octane();
	}

	private static void requestOnce(String bodyId) {
		if (BodyIds.valid(bodyId) && REQUESTED.add(bodyId)) {
			BodyJobs.game().request(bodyId);
		}
	}

	public static BodyLook of(RlCache rl, CarEntity car) {
		return of(rl, car.bodyId());
	}

	public static BodyLook ofCar(RlCache rl, int carId) {
		ClientLevel level = Minecraft.getInstance().level;
		if (carId >= 0 && level != null && level.getEntity(carId) instanceof CarEntity car) {
			return of(rl, car);
		}
		return rl.bodies().octane();
	}
}
