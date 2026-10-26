package dev.mcrl.entity;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import dev.mcrl.math.Pose;

public final class CarLoadGate {
	private static final Map<ResourceKey<Level>, Set<CarEntity>> DEFERRED = new HashMap<>();

	private CarLoadGate() {
	}

	@FunctionalInterface
	public interface ChunkLoaded {
		boolean test(int cx, int cz);
	}

	static int chunkOf(double v) {
		return (int) Math.floor(v / 16.0);
	}

	public static boolean columnsReady(double x, double z, ChunkLoaded loaded) {
		if (!Double.isFinite(x) || !Double.isFinite(z)) {
			return false;
		}
		int cx = chunkOf(x), cz = chunkOf(z);
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				if (!loaded.test(cx + dx, cz + dz)) {
					return false;
				}
			}
		}
		return true;
	}

	public static void onLoad(ServerLevel level, CarEntity car) {
		if (ready(level, car)) {
			onUnload(level, car);
			car.enterSim(level);
		} else {
			DEFERRED.computeIfAbsent(level.dimension(), k -> Collections.newSetFromMap(new IdentityHashMap<>())).add(car);
		}
	}

	public static void onUnload(ServerLevel level, CarEntity car) {
		Set<CarEntity> s = DEFERRED.get(level.dimension());
		if (s != null) {
			s.remove(car);
			if (s.isEmpty()) {
				DEFERRED.remove(level.dimension());
			}
		}
	}

	public static void onEndLevelTick(ServerLevel level) {
		Set<CarEntity> s = DEFERRED.get(level.dimension());
		if (s == null) {
			return;
		}
		for (Iterator<CarEntity> it = s.iterator(); it.hasNext();) {
			CarEntity car = it.next();
			if (car.isRemoved() || car.level() != level) {
				it.remove();
			} else if (ready(level, car)) {
				it.remove();
				car.enterSim(level);
			}
		}
		if (s.isEmpty()) {
			DEFERRED.remove(level.dimension());
		}
	}

	public static boolean isWaiting(CarEntity car) {
		return car.level() instanceof ServerLevel level && DEFERRED.getOrDefault(level.dimension(), Set.of()).contains(car);
	}

	public static void clear() {
		DEFERRED.clear();
	}

	private static boolean ready(ServerLevel level, CarEntity car) {
		Pose p = car.entryPose();
		ServerChunkCache chunks = level.getChunkSource();
		return columnsReady(p.x(), p.z(), (cx, cz) -> chunks.getChunkNow(cx, cz) != null);
	}
}
