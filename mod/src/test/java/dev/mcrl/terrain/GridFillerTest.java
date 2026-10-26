package dev.mcrl.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

class GridFillerTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	static class Stub implements GridFiller.BlockView {
		final Map<BlockPos, BlockState> blocks = new HashMap<>();
		final Set<Long> loaded = new HashSet<>();

		void load(int cx, int cz) {
			loaded.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
		}

		public boolean chunkLoaded(int cx, int cz) {
			return loaded.contains(((long) cx << 32) | (cz & 0xFFFFFFFFL));
		}

		public BlockState get(int x, int y, int z) {
			return blocks.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
		}

		public net.minecraft.world.level.BlockGetter level() {
			return net.minecraft.world.level.EmptyBlockGetter.INSTANCE;
		}

		public int minSectionY() {
			return -4;
		}

		public int maxSectionY() {
			return 19;
		}
	}

	static int idx(int x, int y, int z) {
		return (((y + 2) * 20) + z + 2) * 20 + x + 2;
	}

	@Test
	void grid_fluid_plane_size_checked() {
		short[] cells = new short[GridFiller.N * GridFiller.N * GridFiller.N];
		assertThrows(IllegalArgumentException.class, () -> new Grid(cells, false, new byte[GridFiller.CORE_CELLS - 1]));
		assertThrows(IllegalArgumentException.class, () -> new Grid(cells, false, new byte[0]));
		assertEquals(GridFiller.CORE_CELLS, new Grid(cells, false, new byte[GridFiller.CORE_CELLS]).fluid().length);
		assertNull(new Grid(cells, false).fluid());
	}

	@Test
	void shapeResolutionNeverLoadsChunks() {
		int[] lookups = {0};
		GridFiller.ChunkLookup none = (cx, cz) -> {
			lookups[0]++;
			return null;
		};
		var getter = new GridFiller.NonLoadingGetter(LevelHeightAccessor.create(-64, 384), none);
		BlockPos p = new BlockPos(100, 70, -300);
		assertTrue(getter.getBlockState(p).isAir(), "an unloaded chunk reads as air");
		assertNull(getter.getBlockEntity(p));
		assertTrue(getter.getFluidState(p).isEmpty());
		assertEquals(-64, getter.getMinY());
		assertEquals(384, getter.getHeight());
		assertEquals(3, lookups[0], "every read asks the non-loading lookup");
	}

	@Test
	void missingNeighbourIsUnknown() {
		Stub v = new Stub();
		v.load(0, 0);
		v.load(1, 0);
		Grid grid = GridFiller.fill(v, 0, 0, 0, new ShapeRegistry());
		assertNotNull(grid);
		short[] g = grid.cells();
		assertEquals((short) 0xFFFF, g[idx(-1, 5, 5)]);
		assertEquals((short) 0xFFFF, g[idx(-2, 5, 5)]);
		assertEquals((short) 0xFFFF, g[idx(5, 5, -1)]);
		assertEquals(0, g[idx(17, 5, 5)]);
		assertEquals(0, g[idx(5, 5, 5)]);
	}

	@Test
	void negativeSectionFill() {
		Stub v = new Stub();
		for (int cx = -2; cx <= 0; cx++)
			for (int cz = -2; cz <= 0; cz++)
				v.load(cx, cz);
		v.blocks.put(new BlockPos(-1, 5, -1), Blocks.STONE.defaultBlockState());
		v.blocks.put(new BlockPos(0, 5, 0), Blocks.STONE.defaultBlockState());
		ShapeRegistry r = new ShapeRegistry();
		short[] g = GridFiller.fill(v, -1, 0, -1, r).cells();
		assertEquals(1, g[idx(15, 5, 15)]);
		assertEquals(1, g[idx(16, 5, 16)]);
		assertEquals(0, g[idx(14, 5, 14)]);
	}
}
