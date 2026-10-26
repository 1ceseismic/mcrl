package dev.mcrl.rlassets.arena;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.upk.UpkFixtureWriter;

public final class ArenaFixtures {
	private ArenaFixtures() {
	}

	public static UpkFixtureWriter engine() {
		UpkFixtureWriter e = new UpkFixtureWriter("Engine");
		int actorCls = e.scriptClass("Actor", 0);
		int vector = e.scriptStruct(actorCls, "Vector", 0, 0x80, e.body());
		for (String f : List.of("X", "Y", "Z")) {
			e.property(vector, f, "FloatProperty", 1, 0);
		}
		int rotator = e.scriptStruct(actorCls, "Rotator", 0, 0x80, e.body());
		for (String f : List.of("Pitch", "Yaw", "Roll")) {
			e.property(rotator, f, "IntProperty", 1, 0);
		}
		e.property(actorCls, "Location", "StructProperty", 1, vector);
		e.property(actorCls, "Rotation", "StructProperty", 1, rotator);
		e.property(actorCls, "DrawScale", "FloatProperty", 1, 0);
		e.property(actorCls, "DrawScale3D", "StructProperty", 1, vector);
		e.property(e.scriptClass("StaticMeshActor", actorCls), "StaticMeshComponent", "ObjectProperty", 1, 0);
		e.scriptClass("StaticMeshCollectionActor", actorCls);
		int comp = e.scriptClass("StaticMeshComponent", 0);
		e.property(comp, "StaticMesh", "ObjectProperty", 1, 0);
		e.property(comp, "Translation", "StructProperty", 1, vector);
		e.property(comp, "Rotation", "StructProperty", 1, rotator);
		e.property(comp, "Scale", "FloatProperty", 1, 0);
		e.property(comp, "Scale3D", "StructProperty", 1, vector);
		for (String c : List.of("StaticMesh", "Level", "World")) {
			e.scriptClass(c, 0);
		}
		return e;
	}

	public static byte[] nxsOf(byte[] cmf) {
		ByteBuffer c = ByteBuffer.wrap(cmf).order(ByteOrder.LITTLE_ENDIAN);
		int nt = c.getInt(0), nv = c.getInt(4);
		float[] verts = new float[3 * nv];
		int[] tris = new int[3 * nt];
		for (int i = 0; i < tris.length; i++) {
			tris[i] = c.getInt(8 + 4 * i);
		}
		for (int i = 0; i < verts.length; i++) {
			verts[i] = c.getFloat(8 + 12 * nt + 4 * i);
		}
		return ArenaExtractorTest.nxs(verts, tris, 4);
	}

	public static UpkFixtureWriter level(String pkg, List<byte[]> cmf) {
		UpkFixtureWriter w = new UpkFixtureWriter(pkg);
		int engine = w.imp("Core", "Package", 0, "Engine");
		int meshCls = w.imp("Core", "Class", engine, "StaticMesh"), smaCls = w.imp("Core", "Class", engine, "StaticMeshActor");
		int compCls = w.imp("Core", "Class", engine, "StaticMeshComponent");
		int group = w.exp(meshCls, 0, ArenaExtractor.COLLISION_GROUP, w.body().i32(0).none().toArray());
		List<Integer> meshes = new ArrayList<>();
		for (int i = 0; i < cmf.size(); i++) {
			meshes.add(w.exp(meshCls, group, String.format(Locale.ROOT, "Field_%02d", i), w.body().i32(0).none().toArray()));
		}
		int world = w.exp(w.imp("Core", "Class", engine, "World"), 0, "TheWorld", w.body().i32(0).none().toArray());
		ByteArrayOutputStream lb = new ByteArrayOutputStream();
		lb.writeBytes(new byte[] {0, 0, 0, 0, 5, 5, 5, 5});
		i32(lb, cmf.size());
		for (int i = 0; i < cmf.size(); i++) {
			i32(lb, meshes.get(i));
			for (int k = 0; k < 3; k++) {
				i32(lb, Float.floatToRawIntBits(1f));
			}
			i32(lb, i);
		}
		i32(lb, cmf.size());
		for (byte[] f : cmf) {
			byte[] m = nxsOf(f);
			i32(lb, 1);
			i32(lb, m.length);
			lb.writeBytes(m);
		}
		int level = w.exp(w.imp("Core", "Class", engine, "Level"), world, "PersistentLevel", lb.toByteArray());
		int coll = w.exp(w.imp("Core", "Class", engine, "StaticMeshCollectionActor"), level, "StaticMeshCollectionActor_0",
				w.body().i32(0).none().toArray());
		for (int i = 0; i < cmf.size(); i++) {
			int c = w.exp(compCls, coll, "StaticMeshComponent_" + i, w.body().i32(0).objProp("StaticMesh", meshes.get(i))
					.none().toArray());
			w.exp(smaCls, level, "StaticMeshActor_" + i, w.body().i32(0).objProp("StaticMeshComponent", c).none().toArray());
		}
		return w;
	}

	public static void acceptedArena(Path cacheDir, List<byte[]> cmf, String fingerprint) throws IOException {
		CacheWriter.writeSubtree(cacheDir, ArenaCache.DIR, CacheWriter.Guard.standard(null), d -> {
			Path soccar = d.resolve(ArenaCache.SOCCAR);
			Files.createDirectories(soccar);
			List<String> hashes = new ArrayList<>();
			for (int i = 0; i < cmf.size(); i++) {
				Files.write(soccar.resolve("mesh_" + i + ".cmf"), cmf.get(i));
				hashes.add(String.format(Locale.ROOT, "\"0x%08X\"", CmfHash.of(cmf.get(i))));
			}
			Files.writeString(d.resolve(ArenaCache.JSON), "{\"arenaVersion\": " + ArenaCache.ARENA_VERSION
					+ ", \"fingerprint\": \"" + fingerprint + "\", \"hashes\": [" + String.join(", ", hashes)
					+ "], \"accepted\": true}");
		});
	}

	private static void i32(ByteArrayOutputStream o, int v) {
		o.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
	}
}
