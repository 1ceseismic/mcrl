package dev.mcrl.terrain;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.mcrl.natives.Layouts;
import dev.mcrl.natives.McrlNative;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import static java.lang.foreign.ValueLayout.JAVA_SHORT;

public final class ShapeRegistry {
	// MCRL_SHAPE_* in mcrl.h.
	public static final int THIN = 1;
	public static final int NONFLOOR = 2;
	public static final int MATERIAL_SHIFT = 8;
	private static final int MAX_ID = 0xFFFE;
	private static final int UNIT = 4096;
	private static final int THIN_LIMIT = UNIT / 4;

	private enum Kind {
		STATE, PER_POSITION, POLLED
	}

	private record Key(Kind kind, short[][] boxes, int material) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && kind == k.kind && material == k.material && Arrays.deepEquals(boxes, k.boxes);
		}

		@Override
		public int hashCode() {
			return (Arrays.deepHashCode(boxes) * 31 + kind.hashCode()) * 31 + material;
		}
	}

	private record Shape(List<short[]> boxes, int flags, Kind kind) {
	}

	private final MaterialRegistry materials;
	private final ConcurrentHashMap<BlockState, Integer> byState = new ConcurrentHashMap<>();
	private final Map<Key, Integer> byBoxes = new HashMap<>();
	private final Object lock = new Object();
	private volatile Shape[] shapes = new Shape[256];
	// Published by writing size last.
	private volatile int size;
	private final Object syncLock = new Object();

	public ShapeRegistry() {
		this(new MaterialRegistry());
	}

	public ShapeRegistry(MaterialRegistry materials) {
		this.materials = materials;
	}

	public MaterialRegistry materials() {
		return materials;
	}

	public static short q(double v) {
		long r = Math.round(v * UNIT);
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, r));
	}

	public static boolean perPosition(BlockState state) {
		return state.hasOffsetFunction() || state.getBlock().hasDynamicShape();
	}

	// Offset blocks are dynamic too, but offsets never change.
	public static boolean polled(BlockState state) {
		return state.getBlock().hasDynamicShape() && state.hasBlockEntity();
	}

	public int idFor(BlockState state, BlockGetter level, BlockPos pos) {
		if (perPosition(state)) {
			VoxelShape vs = state.getCollisionShape(level, pos, CollisionContext.empty());
			return vs.isEmpty() ? 0
					: register(vs, polled(state) ? Kind.POLLED : Kind.PER_POSITION, materials.idFor(state.getBlock()));
		}
		Integer cached = byState.get(state);
		if (cached != null) {
			return cached;
		}
		VoxelShape vs = state.getCollisionShape(level, pos, CollisionContext.empty());
		int id = vs.isEmpty() ? 0 : register(vs, Kind.STATE, materials.idFor(state.getBlock()));
		byState.put(state, id);
		return id;
	}

	private int register(VoxelShape vs, Kind kind, int material) {
		List<short[]> boxes = new ArrayList<>();
		for (AABB a : vs.toAabbs()) {
			short[] b = { q(a.minX), q(a.minY), q(a.minZ), q(a.maxX), q(a.maxY), q(a.maxZ) };
			if (b[3] > b[0] && b[4] > b[1] && b[5] > b[2]) {
				boxes.add(b);
			}
		}
		if (boxes.isEmpty()) {
			return 0;
		}
		Key key = new Key(kind, boxes.toArray(new short[0][]), material);
		synchronized (lock) {
			Integer existing = byBoxes.get(key);
			if (existing != null) {
				return existing;
			}
			int n = size;
			if (n >= MAX_ID) {
				throw new IllegalStateException("shape registry full");
			}
			Shape[] arr = shapes;
			if (n == arr.length) {
				arr = Arrays.copyOf(arr, n * 2);
				shapes = arr;
			}
			arr[n] = new Shape(List.copyOf(boxes), flagsFor(boxes) | material << MATERIAL_SHIFT, kind);
			size = n + 1;
			byBoxes.put(key, n + 1);
			return n + 1;
		}
	}

	private static int flagsFor(List<short[]> boxes) {
		boolean allThin = true;
		boolean tall = false;
		for (short[] b : boxes) {
			if (!(b[3] - b[0] <= THIN_LIMIT || b[5] - b[2] <= THIN_LIMIT)) {
				allThin = false;
			}
			if (b[4] - b[1] > UNIT) {
				tall = true;
			}
		}
		return (allThin ? THIN : 0) | (tall ? NONFLOOR : 0);
	}

	private Shape shape(int id) {
		if (id < 1 || id > size) {
			throw new IllegalArgumentException("unknown shape id " + id);
		}
		return shapes[id - 1];
	}

	public int flagsOf(int id) {
		return id == 0 ? 0 : shape(id).flags();
	}

	public boolean isPolled(int id) {
		return id != 0 && shape(id).kind() == Kind.POLLED;
	}

	public int count() {
		return size;
	}

	public void syncNative(MemorySegment nativeShapes) {
		synchronized (syncLock) {
			int have = McrlNative.shapesCount(nativeShapes) - 1;
			int want = size;
			for (int id = have + 1; id <= want; id++) {
				List<short[]> boxes = shape(id).boxes();
				try (Arena arena = Arena.ofConfined()) {
					MemorySegment seg = arena.allocate(Layouts.BOX_Q.byteSize() * boxes.size(), 2);
					for (int i = 0; i < boxes.size(); i++) {
						short[] b = boxes.get(i);
						for (int k = 0; k < 6; k++) {
							seg.setAtIndex(JAVA_SHORT, i * 6L + k, b[k]);
						}
					}
					int got = McrlNative.shapesRegister(nativeShapes, seg, boxes.size(), flagsOf(id));
					if (got != id) {
						throw new IllegalStateException("native shape id " + got + " != java id " + id);
					}
				}
			}
		}
	}
}
