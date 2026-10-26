package dev.mcrl.rlassets.arena;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.mcrl.rlassets.upk.UpkFixtureWriter;
import dev.mcrl.rlassets.upk.UpkWorld;

class ArenaExtractorTest {
	static byte[] nxs(float[] verts, int[] tris, int width) {
		int flags = 2 | (width == 1 ? 8 : width == 2 ? 16 : 0);
		ByteBuffer b = ByteBuffer.allocate(36 + 4 * verts.length + width * tris.length + 7).order(ByteOrder.LITTLE_ENDIAN);
		b.put(new byte[] {'N', 'X', 'S', 1, 'M', 'E', 'S', 'H'}).putInt(1).putInt(flags).putFloat(0.001f).putInt(255)
				.putFloat(0).putInt(verts.length / 3).putInt(tris.length / 3);
		for (float v : verts) {
			b.putFloat(v);
		}
		for (int t : tris) {
			switch (width) {
				case 1 -> b.put((byte) t);
				case 2 -> b.putShort((short) t);
				default -> b.putInt(t);
			}
		}
		b.put(new byte[] {9, 9, 9, 9, 9, 9, 9});
		return b.array();
	}

	private static final float[] QUAD = {0, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0};
	private static final int[] QUAD_TRIS = {0, 1, 2, 0, 2, 3};

	private static void i32(ByteArrayOutputStream o, int v) {
		o.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
	}

	private static void f32(ByteArrayOutputStream o, float v) {
		o.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array());
	}

	private static float[] verts(byte[] cmf) {
		ByteBuffer b = ByteBuffer.wrap(cmf).order(ByteOrder.LITTLE_ENDIAN);
		int nt = b.getInt(0), nv = b.getInt(4);
		float[] v = new float[3 * nv];
		for (int i = 0; i < v.length; i++) {
			v[i] = b.getFloat(8 + 12 * nt + 4 * i);
		}
		return v;
	}

	@Test
	void extractPlacesOwnAndCollectedComponents() throws IOException {
		Path dir = Files.createTempDirectory("mcrl-arena-fixture");
		try {
			ArenaFixtures.engine().write(dir);

			UpkFixtureWriter w = new UpkFixtureWriter("Arena");
			int engine = w.imp("Core", "Package", 0, "Engine");
			java.util.function.Function<String, Integer> cls = n -> w.imp("Core", "Class", engine, n);
			int meshCls = cls.apply("StaticMesh"), smaCls = cls.apply("StaticMeshActor"), compCls = cls.apply("StaticMeshComponent");
			int group = w.exp(meshCls, 0, ArenaExtractor.COLLISION_GROUP, w.body().i32(0).none().toArray());
			int wall = w.exp(meshCls, group, "Wall_A", w.body().i32(0).none().toArray());
			int props = w.exp(meshCls, 0, "Props", w.body().i32(0).none().toArray());
			int chair = w.exp(meshCls, props, "Chair", w.body().i32(0).none().toArray());
			int world = w.exp(cls.apply("World"), 0, "TheWorld", w.body().i32(0).none().toArray());
			byte[] plain = nxs(QUAD, QUAD_TRIS, 2), mirrored = nxs(QUAD, new int[] {0, 2, 1, 0, 3, 2}, 2),
					collected = nxs(new float[] {0, 0, 0, 2, 0, 0, 2, 2, 0}, new int[] {0, 1, 2}, 1);
			ByteArrayOutputStream lb = new ByteArrayOutputStream();
			lb.writeBytes(new byte[] {0, 0, 0, 0, 5, 5, 5, 5});
			i32(lb, 3);
			for (float[] e3 : new float[][] {{2, 2, 2, 0}, {-2, 2, 2, 1}, {1, 1, 1, 2}}) {
				i32(lb, wall);
				f32(lb, e3[0]);
				f32(lb, e3[1]);
				f32(lb, e3[2]);
				i32(lb, (int) e3[3]);
			}
			i32(lb, 3);
			for (byte[] m : List.of(plain, mirrored, collected)) {
				i32(lb, 1);
				i32(lb, m.length);
				lb.writeBytes(m);
			}
			int level = w.exp(cls.apply("Level"), world, "PersistentLevel", lb.toByteArray());
			UpkFixtureWriter.Body v100 = w.body().f32(100).f32(200).f32(300);
			int a0 = w.exp(smaCls, level, "StaticMeshActor_0");
			int c0 = w.exp(compCls, a0, "StaticMeshComponent_0", w.body().i32(0).objProp("StaticMesh", wall)
					.structProp("Scale3D", "Vector", w.body().f32(-1).f32(1).f32(1)).none().toArray());
			w.export(a0).body = w.body().i32(0).objProp("StaticMeshComponent", c0).structProp("Location", "Vector", v100)
					.structProp("Rotation", "Rotator", w.body().i32(0).i32(16384).i32(0)).floatProp("DrawScale", 2).none()
					.toArray();
			int coll = w.exp(cls.apply("StaticMeshCollectionActor"), level, "StaticMeshCollectionActor_0",
					w.body().i32(0).none().toArray());
			int c1 = w.exp(compCls, coll, "StaticMeshComponent_1", w.body().i32(0).objProp("StaticMesh", wall)
					.structProp("Translation", "Vector", w.body().f32(1000).f32(-50).f32(25))
					.structProp("Rotation", "Rotator", w.body().i32(0).i32(0).i32(32768)).none().toArray());
			w.exp(smaCls, level, "StaticMeshActor_1", w.body().i32(0).objProp("StaticMeshComponent", c1)
					.structProp("Location", "Vector", w.body().f32(5).f32(5).f32(5)).floatProp("DrawScale", 3).none().toArray());
			int a2 = w.exp(smaCls, level, "StaticMeshActor_2");
			int c2 = w.exp(compCls, a2, "StaticMeshComponent_2", w.body().i32(0).objProp("StaticMesh", wall)
					.structProp("Translation", "Vector", w.body().f32(10).f32(0).f32(0)).none().toArray());
			w.export(a2).body = w.body().i32(0).objProp("StaticMeshComponent", c2).none().toArray();
			int a3 = w.exp(smaCls, level, "StaticMeshActor_3");
			int c3 = w.exp(compCls, a3, "StaticMeshComponent_3", w.body().i32(0).objProp("StaticMesh", wall)
					.floatProp("Scale", 3).none().toArray());
			w.export(a3).body = w.body().i32(0).objProp("StaticMeshComponent", c3).none().toArray();
			int a4 = w.exp(smaCls, level, "StaticMeshActor_4");
			int c4 = w.exp(compCls, a4, "StaticMeshComponent_4", w.body().i32(0).objProp("StaticMesh", chair).none()
					.toArray());
			w.export(a4).body = w.body().i32(0).objProp("StaticMeshComponent", c4).none().toArray();
			w.write(dir);

			ArenaExtractor.Arena arena = ArenaExtractor.extract(new UpkWorld(dir), "Arena");
			assertEquals(List.of("Arena:TheWorld.PersistentLevel.StaticMeshActor_0",
					"Arena:TheWorld.PersistentLevel.StaticMeshActor_1"),
					arena.meshes().stream().map(ArenaExtractor.Placed::actor).toList());
			ArenaExtractor.Placed own = arena.meshes().get(0), coll1 = arena.meshes().get(1);
			assertEquals(1, own.index(), "the mirrored copy at (-2, 2, 2)");
			assertEquals("Arena:" + ArenaExtractor.COLLISION_GROUP + ".Wall_A", own.mesh());
			assertArrayEquals(ArenaExtractor.cmf(ArenaExtractor.cooked(mirrored), new float[] {100, 200, 300},
					new int[] {0, 16384, 0}), own.cmf(), "the actor's location and rotation, the cooked winding kept");
			assertEquals(2, coll1.index());
			assertArrayEquals(ArenaExtractor.cmf(ArenaExtractor.cooked(collected), new float[] {1000, -50, 25},
					new int[] {0, 0, 32768}), coll1.cmf(), "the collected component's own transform");
			assertEquals(CmfHash.of(own.cmf()), own.hash());
			assertEquals(2, arena.problems().size(), arena.problems().toString());
			assertTrue(arena.problems().get(0).startsWith("Arena:TheWorld.PersistentLevel.StaticMeshActor_2: a component "
					+ "moved within its actor"), arena.problems().toString());
			assertTrue(arena.problems().get(1).startsWith("Arena:TheWorld.PersistentLevel.StaticMeshActor_3: no cooked mesh"),
					arena.problems().toString());
		} finally {
			try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
				for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.delete(f);
				}
			}
		}
	}
}
