package dev.mcrl.terrain;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

public final class GridFiller {
	public static final int BORDER = 2;
	public static final int N = 16 + 2 * BORDER;
	public static final short UNKNOWN = (short) 0xFFFF;
	public static final int FLUID_WATER = 1, FLUID_LAVA = 2, FLUID_MEDIUM = 3, FLUID_H_SHIFT = 2;
	public static final int CORE_CELLS = 16 * 16 * 16;

	public interface BlockView {
		boolean chunkLoaded(int cx, int cz);

		BlockState get(int x, int y, int z);

		BlockGetter level();

		int minSectionY();

		int maxSectionY();
	}

	private GridFiller() {
	}

	@FunctionalInterface
	public interface ChunkLookup {
		@Nullable LevelChunk get(int cx, int cz);
	}

	static int sectionIndex(int minSectionY, int blockY) {
		return (blockY >> 4) - minSectionY;
	}

	static int chunkCoord(int blockCoord) {
		return blockCoord >> 4;
	}

	static int localCoord(int blockCoord) {
		return blockCoord & 15;
	}

	static int worldCoord(int sectionCoord, int local) {
		return sectionCoord * 16 + local;
	}

	public static int gridIndex(int x, int y, int z) {
		return ((y * N) + z) * N + x;
	}

	public static @Nullable Grid fill(LevelReader level, ChunkLookup chunks, int sx, int sy, int sz,
			ShapeRegistry registry) {
		return fill(new LevelView(level, chunks), sx, sy, sz, registry);
	}

	public static @Nullable Grid fill(BlockView view, int sx, int sy, int sz, ShapeRegistry registry) {
		if (!view.chunkLoaded(sx, sz)) {
			return null;
		}
		short[] grid = new short[N * N * N];
		byte @Nullable [] fluid = null;
		boolean polled = false;
		int minY = view.minSectionY() * 16, maxY = view.maxSectionY() * 16 + 15;
		for (int y = 0; y < N; y++) {
			int wy = worldCoord(sy, y - BORDER);
			if (wy < minY || wy > maxY) {
				continue;
			}
			for (int z = 0; z < N; z++) {
				int wz = worldCoord(sz, z - BORDER);
				for (int x = 0; x < N; x++) {
					int wx = worldCoord(sx, x - BORDER);
					int i = gridIndex(x, y, z);
					if (!view.chunkLoaded(chunkCoord(wx), chunkCoord(wz))) {
						grid[i] = UNKNOWN;
						continue;
					}
					BlockState s = view.get(wx, wy, wz);
					if (s.isAir()) {
						continue;
					}
					int id = registry.idFor(s, view.level(), new BlockPos(wx, wy, wz));
					grid[i] = (short) id;
					polled |= id != 0 && registry.isPolled(id);
					int lx = x - BORDER, ly = y - BORDER, lz = z - BORDER;
					if (lx >= 0 && lx < 16 && ly >= 0 && ly < 16 && lz >= 0 && lz < 16) {
						byte b = fluidByte(view, s, wx, wy, wz, maxY);
						if (b != 0) {
							if (fluid == null) {
								fluid = new byte[CORE_CELLS];
							}
							fluid[(ly * 16 + lz) * 16 + lx] = b;
						}
					}
				}
			}
		}
		return new Grid(grid, polled, fluid);
	}

	static byte fluidByte(BlockView view, BlockState s, int wx, int wy, int wz, int maxY) {
		if (s.is(Blocks.POWDER_SNOW) || s.is(Blocks.COBWEB)) {
			return (byte) (FLUID_MEDIUM | 9 << FLUID_H_SHIFT);
		}
		FluidState f = s.getFluidState();
		if (f.isEmpty()) {
			return 0;
		}
		// By type: tags bind only after datapacks load.
		int type = f.getType().isSame(Fluids.WATER) ? FLUID_WATER : f.getType().isSame(Fluids.LAVA) ? FLUID_LAVA : 0;
		if (type == 0) {
			return 0;
		}
		boolean covered = wy < maxY && view.get(wx, wy + 1, wz).getFluidState().getType().isSame(f.getType());
		int h = covered ? 9 : Math.max(1, Math.min(8, f.getAmount()));
		return (byte) (type | h << FLUID_H_SHIFT);
	}

	private static final class LevelView implements BlockView {
		private final LevelReader level;
		private final ChunkLookup lookup;
		private final NonLoadingGetter getter;
		private int cx = Integer.MIN_VALUE, cz;
		private @Nullable LevelChunk chunk;

		LevelView(LevelReader level, ChunkLookup lookup) {
			this.level = level;
			this.lookup = lookup;
			this.getter = new NonLoadingGetter(level, this::chunk);
		}

		private @Nullable LevelChunk chunk(int x, int z) {
			if (chunk == null || x != cx || z != cz) {
				cx = x;
				cz = z;
				chunk = lookup.get(x, z);
			}
			return chunk;
		}

		public boolean chunkLoaded(int x, int z) {
			return chunk(x, z) != null;
		}

		public BlockState get(int x, int y, int z) {
			LevelChunk c = chunk(chunkCoord(x), chunkCoord(z));
			LevelChunkSection s = c.getSection(sectionIndex(level.getMinSectionY(), y));
			return s.getBlockState(localCoord(x), localCoord(y), localCoord(z));
		}

		public BlockGetter level() {
			return getter;
		}

		public int minSectionY() {
			return level.getMinSectionY();
		}

		public int maxSectionY() {
			return level.getMaxSectionY();
		}
	}

	static final class NonLoadingGetter implements BlockGetter {
		private final LevelHeightAccessor height;
		private final ChunkLookup chunks;

		NonLoadingGetter(LevelHeightAccessor height, ChunkLookup chunks) {
			this.height = height;
			this.chunks = chunks;
		}

		private @Nullable LevelChunk chunk(BlockPos pos) {
			return chunks.get(chunkCoord(pos.getX()), chunkCoord(pos.getZ()));
		}

		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			LevelChunk c = chunk(pos);
			return c == null ? null : c.getBlockEntity(pos);
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			LevelChunk c = chunk(pos);
			return c == null ? Blocks.AIR.defaultBlockState() : c.getBlockState(pos);
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			LevelChunk c = chunk(pos);
			return c == null ? Fluids.EMPTY.defaultFluidState() : c.getFluidState(pos);
		}

		@Override
		public int getHeight() {
			return height.getHeight();
		}

		@Override
		public int getMinY() {
			return height.getMinY();
		}
	}
}
