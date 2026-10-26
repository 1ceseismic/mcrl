package dev.mcrl.client.render.stadium;

import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.match.StadiumConst;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.TextureInfo;

public record StadiumScene(String fingerprint, Map<String, Mesh> meshes, List<Instance> instances, StadiumLight light,
		@Nullable Fog fog, Map<String, float[]> colors, Map<String, Surface> surfaces, Map<String, TextureInfo> textures) {
	public static final int VERSION = 4;
	public static final String TEXTURE_PREFIX = "stadium/";
	// RocketSim arena walls, in blocks.
	private static final float FLOOR_HALF_X = 81.92f, FLOOR_HALF_Z = 102.4f;
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Set<String> LOGGED = Collections.synchronizedSet(new HashSet<>());

	public enum Tier { NEAR, FAR }

	public enum Pass { OPAQUE, MASKED, GLASS, GLOW }

	public record Instance(String mesh, List<String> materials, float[] m, Tier tier, Pass pass, int team, int pad) {
	}

	public record Surface(String texture, float[] tint, float @Nullable [] mask) {
	}

	public record Mesh(@Nullable Path file, @Nullable MeshData data) {
	}

	public record Fog(float[] color, float start, float end, float maxOpacity) {
	}

	public @Nullable Surface surface(Instance x) {
		List<String> m = x.materials();
		return m.isEmpty() ? null : surfaces.get(m.getFirst());
	}

	public float @Nullable [] color(Instance x) {
		List<String> m = x.materials();
		return m.isEmpty() ? null : colors.get(m.getFirst());
	}

	public static @Nullable StadiumScene load(Path dir) {
		Path file = dir.resolve("stadium.json");
		if (!Files.isRegularFile(file)) {
			once("no stadium scene at " + file + ": the arena meshes' look");
			return null;
		}
		try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			int version = o.has("version") ? o.get("version").getAsInt() : -1;
			if (version != VERSION) {
				once("the stadium scene at " + file + " is version " + version + ", this build reads " + VERSION
						+ ": the arena meshes' look");
				return null;
			}
			Map<String, Mesh> meshes = new LinkedHashMap<>();
			Path root = dir.normalize();
			for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("meshes").entrySet()) {
				Path p = dir.resolve(e.getValue().getAsString()).normalize();
				if (!p.startsWith(root)) {
					throw new IOException("mesh " + e.getKey() + " lies outside the stadium folder");
				}
				meshes.put(e.getKey(), new Mesh(p, null));
			}
			List<Instance> instances = new ArrayList<>();
			for (JsonElement e : o.getAsJsonArray("instances")) {
				JsonObject i = e.getAsJsonObject();
				String mesh = i.get("mesh").getAsString();
				if (!meshes.containsKey(mesh)) {
					throw new IOException("an instance names the unknown mesh " + mesh);
				}
				List<String> materials = new ArrayList<>();
				if (i.has("materials")) {
					for (JsonElement m : i.getAsJsonArray("materials")) {
						materials.add(m.getAsString());
					}
				}
				instances.add(new Instance(mesh, materials, floats(i.getAsJsonArray("m"), 12),
						Tier.valueOf(i.get("tier").getAsString().toUpperCase(Locale.ROOT)),
						Pass.valueOf(i.get("pass").getAsString().toUpperCase(Locale.ROOT)),
						i.has("team") ? i.get("team").getAsInt() : -1, i.has("pad") ? i.get("pad").getAsInt() : -1));
			}
			Map<String, TextureInfo> textures = Map.of();
			if (o.get("textures") instanceof JsonObject t) {
				JsonObject wrap = new JsonObject();
				wrap.add("textures", t);
				textures = CacheJson.materials(wrap).textures();
			}
			Map<String, float[]> colors = new LinkedHashMap<>();
			Map<String, Surface> surfaces = new LinkedHashMap<>();
			if (o.get("materials") instanceof JsonObject mats) {
				for (Map.Entry<String, JsonElement> e : mats.entrySet()) {
					if (!(e.getValue() instanceof JsonObject m)) {
						continue;
					}
					if (m.get("color") instanceof JsonArray c) {
						colors.put(e.getKey(), floats(c, 4));
					}
					if (m.get("texture") instanceof JsonElement t && textures.containsKey(t.getAsString())) {
						surfaces.put(e.getKey(), new Surface(t.getAsString(), m.get("tint") instanceof JsonArray a
								? floats(a, 3) : new float[] {1, 1, 1}, m.get("mask") instanceof JsonArray k ? floats(k, 3)
										: null));
					}
				}
			}
			return new StadiumScene(o.has("fingerprint") ? o.get("fingerprint").getAsString() : "", meshes, instances,
					light(o.getAsJsonObject("lights")), fog(o.getAsJsonObject("fog")), colors, surfaces, textures);
		} catch (IOException | RuntimeException e) {
			once("the stadium scene at " + file + " is unreadable (" + e.getMessage() + "): the arena meshes' look");
			return null;
		}
	}

	public static StadiumScene fallback(List<byte[]> cmf) {
		Map<String, Mesh> meshes = new LinkedHashMap<>();
		List<Instance> instances = new ArrayList<>();
		float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
		for (int f = 0; f < cmf.size(); f++) {
			String name = "arena_" + f;
			meshes.put(name, new Mesh(null, cmfMesh(cmf.get(f))));
			instances.add(new Instance(name, List.of(), identity, Tier.NEAR, Pass.OPAQUE, -1, -1));
		}
		float y = StadiumConst.ORIGIN.getY(), ox = StadiumConst.ORIGIN.getX(), oz = StadiumConst.ORIGIN.getZ();
		for (int team = 0; team <= 1; team++) {
			float z0 = team == 0 ? oz - FLOOR_HALF_Z : oz, z1 = team == 0 ? oz : oz + FLOOR_HALF_Z;
			String name = team == 0 ? "floor_blue" : "floor_orange";
			float[] t = {ox - FLOOR_HALF_X, y, z0, ox + FLOOR_HALF_X, y, z0, ox + FLOOR_HALF_X, y, z1,
					ox - FLOOR_HALF_X, y, z0, ox + FLOOR_HALF_X, y, z1, ox - FLOOR_HALF_X, y, z1};
			meshes.put(name, new Mesh(null, flat(t)));
			instances.add(new Instance(name, List.of(), identity, Tier.NEAR, Pass.OPAQUE, team, -1));
		}
		return new StadiumScene("fallback", meshes, instances, StadiumLight.FALLBACK, null, Map.of(), Map.of(), Map.of());
	}

	static MeshData cmfMesh(byte[] bytes) {
		ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int tris = b.getInt(), verts = b.getInt();
		if (tris < 0 || verts < 0 || 8 + 12L * tris + 12L * verts > bytes.length) {
			throw new IllegalArgumentException("not a .cmf: " + tris + " triangles, " + verts + " vertices in "
					+ bytes.length + " bytes");
		}
		int[] ix = new int[3 * tris];
		for (int i = 0; i < ix.length; i++) {
			ix[i] = b.getInt();
			if (ix[i] < 0 || ix[i] >= verts) {
				throw new IllegalArgumentException("a .cmf triangle names vertex " + ix[i] + " of " + verts);
			}
		}
		float ox = StadiumConst.ORIGIN.getX(), oy = StadiumConst.ORIGIN.getY(), oz = StadiumConst.ORIGIN.getZ();
		float[] p = new float[3 * verts];
		for (int v = 0; v < verts; v++) {
			float x = b.getFloat(), y = b.getFloat(), z = b.getFloat();
			p[3 * v] = ox + x;
			p[3 * v + 1] = oy + z;
			p[3 * v + 2] = oz + y;
		}
		float[] t = new float[9 * tris];
		for (int i = 0; i < ix.length; i++) {
			System.arraycopy(p, 3 * ix[i], t, 3 * i, 3);
		}
		return flat(t);
	}

	static MeshData flat(float[] t) {
		int corners = t.length / 3;
		float[] v = new float[corners * MeshData.STRIDE];
		int[] ix = new int[corners];
		for (int c = 0; c < corners; c += 3) {
			float ax = t[3 * c], ay = t[3 * c + 1], az = t[3 * c + 2];
			float ux = t[3 * c + 3] - ax, uy = t[3 * c + 4] - ay, uz = t[3 * c + 5] - az;
			float wx = t[3 * c + 6] - ax, wy = t[3 * c + 7] - ay, wz = t[3 * c + 8] - az;
			float[] n = StadiumLight.normalised(uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx);
			for (int k = 0; k < 3; k++) {
				int s = (c + k) * MeshData.STRIDE;
				System.arraycopy(t, 3 * (c + k), v, s, 3);
				System.arraycopy(n, 0, v, s + 3, 3);
				ix[c + k] = c + k;
			}
		}
		return new MeshData(v, ix, new float[0]);
	}

	private static StadiumLight light(@Nullable JsonObject o) {
		if (o == null) {
			return StadiumLight.FALLBACK;
		}
		List<StadiumLight.Key> keys = new ArrayList<>();
		if (o.has("key")) {
			for (JsonElement e : o.getAsJsonArray("key")) {
				JsonObject k = e.getAsJsonObject();
				float[] d = floats(k.getAsJsonArray("dir"), 3);
				keys.add(new StadiumLight.Key(StadiumLight.normalised(d[0], d[1], d[2]), floats(k.getAsJsonArray("color"),
						3), k.get("brightness").getAsFloat()));
			}
		}
		JsonObject sky = o.has("sky") ? o.getAsJsonObject("sky") : null;
		return sky == null ? new StadiumLight(keys, new float[3], 0)
				: new StadiumLight(keys, floats(sky.getAsJsonArray("color"), 3), sky.get("brightness").getAsFloat());
	}

	private static @Nullable Fog fog(@Nullable JsonObject o) {
		return o == null ? null
				: new Fog(floats(o.getAsJsonArray("color"), 3), o.get("start").getAsFloat(), o.get("end").getAsFloat(),
						o.has("maxOpacity") ? o.get("maxOpacity").getAsFloat() : 1f);
	}

	private static float[] floats(JsonArray a, int n) {
		if (a.size() != n) {
			throw new IllegalArgumentException("want " + n + " numbers, got " + a.size());
		}
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = a.get(i).getAsFloat();
		}
		return out;
	}

	private static void once(String line) {
		if (LOGGED.add(line)) {
			LOG.info("mcrl: {}", line);
		}
	}
}
