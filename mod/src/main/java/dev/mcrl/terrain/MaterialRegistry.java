package dev.mcrl.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;
import dev.mcrl.terrain.SurfaceTuning.Material;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

public final class MaterialRegistry {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final int MAX_ID = 255;

	private record Key(float friction, float speedFactor, float bounce) {
		static Key of(float friction, float speedFactor, float bounce) {
			return new Key(SurfaceTuning.round(friction), SurfaceTuning.round(speedFactor), SurfaceTuning.round(bounce));
		}
	}

	private static final Comparator<Key> BY_VALUE = Comparator.comparingDouble(Key::friction)
			.thenComparingDouble(Key::speedFactor).thenComparingDouble(Key::bounce);

	private final ConcurrentHashMap<Block, Integer> byBlock = new ConcurrentHashMap<>();
	private final Map<Key, Integer> byKey = new HashMap<>();
	private final Object lock = new Object();
	private volatile Material[] materials = new Material[16];
	// Published by writing size last.
	private volatile int size;
	private boolean warnedFull;
	private volatile boolean seeded;
	private final Object syncLock = new Object();
	private int synced;

	public int idFor(Block block) {
		Integer cached = byBlock.get(block);
		if (cached != null) {
			return cached;
		}
		seed(BuiltInRegistries.BLOCK);
		int id = register(block.getFriction(), block.getSpeedFactor(), block.getBounceRestitution());
		byBlock.put(block, id);
		return id;
	}

	void seed(Iterable<Block> blocks) {
		if (seeded) {
			return;
		}
		synchronized (lock) {
			if (seeded) {
				return;
			}
			TreeMap<Key, Material> keys = new TreeMap<>(BY_VALUE);
			for (Block b : blocks) {
				Material m = SurfaceTuning.material(b.getFriction(), b.getSpeedFactor(), b.getBounceRestitution());
				if (m != null) {
					keys.putIfAbsent(Key.of(b.getFriction(), b.getSpeedFactor(), b.getBounceRestitution()), m);
				}
			}
			for (Map.Entry<Key, Material> e : keys.entrySet()) {
				if (!byKey.containsKey(e.getKey()) && add(e.getKey(), e.getValue()) == 0) {
					break;
				}
			}
			seeded = true;
		}
	}

	int register(float friction, float speedFactor, float bounce) {
		Material m = SurfaceTuning.material(friction, speedFactor, bounce);
		if (m == null) {
			return 0;
		}
		Key key = Key.of(friction, speedFactor, bounce);
		synchronized (lock) {
			Integer existing = byKey.get(key);
			return existing != null ? existing : add(key, m);
		}
	}

	private int add(Key key, Material m) {
		int n = size;
		if (n >= MAX_ID) {
			if (!warnedFull) {
				warnedFull = true;
				LOG.warn("mcrl: more than {} surface materials; {} and every later one behave like stone", MAX_ID, key);
			}
			return 0;
		}
		Material[] arr = materials;
		if (n == arr.length) {
			arr = Arrays.copyOf(arr, n * 2);
			materials = arr;
		}
		arr[n] = m;
		size = n + 1;
		byKey.put(key, n + 1);
		return n + 1;
	}

	public @Nullable Material materialOf(int id) {
		if (id == 0) {
			return null;
		}
		if (id < 1 || id > size) {
			throw new IllegalArgumentException("unknown material id " + id);
		}
		return materials[id - 1];
	}

	public int count() {
		return size;
	}

	@FunctionalInterface
	interface NativeSet {
		int set(MemorySegment shapes, int id, MemorySegment material);
	}

	public void syncNative(MemorySegment nativeShapes) {
		syncNative(nativeShapes, McrlNative::shapesSetMaterial);
	}

	void syncNative(MemorySegment nativeShapes, NativeSet nativeSet) {
		synchronized (syncLock) {
			int want = size;
			if (synced >= want) {
				return;
			}
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment seg = arena.allocate(Layouts.MATERIAL);
				for (int id = synced + 1; id <= want; id++) {
					materialOf(id).writeTo(seg);
					nativeSet.set(nativeShapes, id, seg);
					synced = id;
				}
			}
		}
	}
}
