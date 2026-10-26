package dev.mcrl.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

class MaterialRegistryTest {
	@BeforeAll
	static void boot() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void ids_do_not_depend_on_lookup_order() {
		MaterialRegistry a = new MaterialRegistry(), b = new MaterialRegistry();
		a.idFor(Blocks.ICE);
		b.idFor(Blocks.BLUE_ICE);
		b.idFor(Blocks.SLIME_BLOCK);
		List<Block> all = new ArrayList<>();
		BuiltInRegistries.BLOCK.forEach(all::add);
		for (Block x : all.reversed()) {
			assertEquals(a.idFor(x), b.idFor(x), x.toString());
		}
		assertEquals(a.count(), b.count());
	}

	@Test
	void sync_native_in_id_order() {
		MaterialRegistry r = new MaterialRegistry();
		r.seed(List.of(Blocks.ICE, Blocks.SLIME_BLOCK));
		List<Integer> ids = new ArrayList<>();
		List<Float> lat = new ArrayList<>();
		MaterialRegistry.NativeSet fake = (shapes, id, m) -> {
			ids.add(id);
			lat.add(m.get(JAVA_FLOAT, 0));
			return 0;
		};
		r.syncNative(MemorySegment.NULL, fake);
		assertEquals(List.of(1, 2), ids);
		assertEquals(List.of(r.materialOf(1).wheelLat(), r.materialOf(2).wheelLat()), lat);
		r.syncNative(MemorySegment.NULL, fake);
		assertEquals(2, ids.size());
		assertEquals(3, r.register(0.7f, 1f, 0f));
		r.syncNative(MemorySegment.NULL, fake);
		assertEquals(List.of(1, 2, 3), ids);
	}
}
