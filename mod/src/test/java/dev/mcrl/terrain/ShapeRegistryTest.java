package dev.mcrl.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.mcrl.natives.McrlNative;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

class ShapeRegistryTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static int id(ShapeRegistry r, BlockState s) {
		return r.idFor(s, EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
	}

	@Test
	void syncAfterInterleavedIdFor() {
		ShapeRegistry r = new ShapeRegistry();
		MemorySegment shapes = McrlNative.shapesCreate();
		try {
			id(r, Blocks.STONE.defaultBlockState());
			r.syncNative(shapes);
			id(r, Blocks.OAK_STAIRS.defaultBlockState());
			id(r, Blocks.OAK_FENCE.defaultBlockState());
			id(r, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
			r.syncNative(shapes);
			assertEquals(r.count() + 1, McrlNative.shapesCount(shapes));
			id(r, Blocks.COBBLESTONE_WALL.defaultBlockState());
			r.syncNative(shapes);
			assertEquals(r.count() + 1, McrlNative.shapesCount(shapes));
		} finally {
			McrlNative.shapesDestroy(shapes);
		}
	}

	@Test
	void concurrentSyncWhileAppending() throws Exception {
		ShapeRegistry r = new ShapeRegistry();
		MemorySegment shapes = McrlNative.shapesCreate();
		try {
			var states = new java.util.ArrayList<BlockState>();
			for (var b : net.minecraft.core.registries.BuiltInRegistries.BLOCK)
				states.addAll(b.getStateDefinition().getPossibleStates());
			var stop = new java.util.concurrent.atomic.AtomicBoolean();
			var ex = java.util.concurrent.Executors.newFixedThreadPool(3);
			Runnable sync = () -> {
				while (!stop.get())
					r.syncNative(shapes);
			};
			var f1 = ex.submit(sync);
			var f2 = ex.submit(sync);
			var f3 = ex.submit(() -> {
				for (BlockState s : states)
					id(r, s);
				stop.set(true);
			});
			f3.get();
			f1.get();
			f2.get();
			ex.shutdown();
			r.syncNative(shapes);
			assertTrue(r.count() > 10);
			assertEquals(r.count() + 1, McrlNative.shapesCount(shapes));
		} finally {
			McrlNative.shapesDestroy(shapes);
		}
	}
}
