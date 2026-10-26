package dev.mcrl.rlassets.stadium;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;

public final class StadiumCache {
	public static final int STADIUM_VERSION = 4;
	public static final String DIR = "stadium", JSON = "stadium.json";

	private StadiumCache() {
	}

	public static List<String> write(Path cacheDir, StadiumExtractor.StadiumData d, String fingerprint,
			CacheWriter.Guard guard) throws IOException {
		return CacheWriter.writeSubtree(cacheDir, DIR, guard, dir -> {
			Files.createDirectories(dir.resolve("meshes"));
			JsonObject meshes = new JsonObject();
			for (Map.Entry<String, MeshData> e : d.meshes().entrySet()) {
				String file = "meshes/" + e.getKey() + ".mcrm";
				Mcrm.write(dir.resolve(file), e.getValue());
				meshes.addProperty(e.getKey(), file);
			}
			Map<String, TextureInfo> textures = new TreeMap<>();
			if (!d.textures().isEmpty()) {
				Files.createDirectories(dir.resolve("textures"));
			}
			java.util.Set<String> stems = new java.util.HashSet<>();
			for (Map.Entry<String, StadiumExtractor.TextureFile> e : d.textures().entrySet()) {
				String stem = textureStem(e.getKey());
				for (int k = 2; !stems.add(stem); k++) {
					stem = textureStem(e.getKey()) + "_" + k;
				}
				Files.copy(e.getValue().png(), dir.resolve("textures/" + stem + ".png"));
				TextureInfo t = e.getValue().info();
				textures.put(e.getKey(), t.exported(DIR + "/textures/" + stem + ".png", t.exportedWidth(),
						t.exportedHeight()));
			}
			Files.writeString(dir.resolve(JSON), sceneJson(d, fingerprint, meshes, textures), StandardCharsets.UTF_8);
		});
	}

	static String textureStem(String name) {
		return "stadium_" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}

	public static String sceneJson(StadiumExtractor.StadiumData d, String fingerprint, JsonObject meshes) {
		return sceneJson(d, fingerprint, meshes, Map.of());
	}

	public static String sceneJson(StadiumExtractor.StadiumData d, String fingerprint, JsonObject meshes,
			Map<String, TextureInfo> textures) {
		JsonObject o = json(d, fingerprint, meshes);
		o.add("textures", CacheJson.materials(new Materials(Map.of(), textures)).getAsJsonObject("textures"));
		return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o) + "\n";
	}

	static JsonObject json(StadiumExtractor.StadiumData d, String fingerprint, JsonObject meshes) {
		JsonObject o = new JsonObject();
		o.addProperty("version", STADIUM_VERSION);
		o.addProperty("fingerprint", fingerprint);
		o.add("meshes", meshes);
		JsonArray instances = new JsonArray();
		for (StadiumExtractor.Instance i : d.instances()) {
			JsonObject j = new JsonObject();
			j.addProperty("mesh", i.mesh());
			JsonArray mats = new JsonArray();
			mats.add(i.material());
			j.add("materials", mats);
			j.add("m", floats(i.m()));
			j.addProperty("tier", i.tier().name().toLowerCase(Locale.ROOT));
			j.addProperty("pass", i.pass().name().toLowerCase(Locale.ROOT));
			j.addProperty("team", i.team());
			j.addProperty("pad", i.pad());
			instances.add(j);
		}
		o.add("instances", instances);
		JsonObject materials = new JsonObject();
		d.looks().forEach((name, look) -> {
			JsonObject m = new JsonObject();
			m.addProperty("pass", look.pass().name().toLowerCase(Locale.ROOT));
			m.add("color", floats(look.color()));
			m.addProperty("source", look.source());
			if (look.texture() != null) {
				m.addProperty("texture", look.texture());
				m.add("tint", floats(look.tint()));
				if (look.mask() != null) {
					m.add("mask", floats(look.mask()));
				}
			}
			materials.add(name, m);
		});
		o.add("materials", materials);
		JsonObject lights = new JsonObject();
		JsonArray keys = new JsonArray();
		for (StadiumExtractor.Light l : d.keys()) {
			JsonObject k = new JsonObject();
			k.add("dir", floats(l.dir()));
			k.add("color", floats(l.color()));
			k.addProperty("brightness", l.brightness());
			keys.add(k);
		}
		lights.add("key", keys);
		JsonObject sky = new JsonObject();
		sky.add("color", floats(d.sky()));
		sky.addProperty("brightness", 1);
		lights.add("sky", sky);
		o.add("lights", lights);
		if (d.fog() != null) {
			JsonObject f = new JsonObject();
			f.add("color", floats(d.fog().color()));
			f.addProperty("start", d.fog().start());
			f.addProperty("end", d.fog().end());
			f.addProperty("maxOpacity", d.fog().maxOpacity());
			o.add("fog", f);
		}
		o.add("report", report(d));
		return o;
	}

	static JsonObject report(StadiumExtractor.StadiumData d) {
		JsonObject r = new JsonObject();
		r.addProperty("instances", d.instances().size());
		r.addProperty("near", d.instances().stream().filter(i -> i.tier() == StadiumExtractor.Tier.NEAR).count());
		r.addProperty("far", d.instances().stream().filter(i -> i.tier() == StadiumExtractor.Tier.FAR).count());
		r.addProperty("padInstances", d.instances().stream().filter(i -> i.pad() >= 0).count());
		JsonObject t = new JsonObject();
		t.addProperty("near", d.triangles(StadiumExtractor.Tier.NEAR));
		t.addProperty("far", d.triangles(StadiumExtractor.Tier.FAR));
		r.add("triangles", t);
		r.addProperty("meshes", d.meshes().size());
		r.addProperty("materials", d.looks().size());
		r.addProperty("padsMatched", d.padsMatched());
		r.addProperty("skipped", d.skipped());
		r.addProperty("masks", d.looks().values().stream().filter(l -> l.source().startsWith("mask ")).count());
		r.addProperty("textured", d.looks().values().stream().filter(l -> l.texture() != null).count());
		r.addProperty("textures", d.textures().size());
		JsonArray w = new JsonArray();
		d.warnings().forEach(w::add);
		r.add("warnings", w);
		return r;
	}

	private static JsonArray floats(float[] v) {
		JsonArray a = new JsonArray();
		for (float f : v) {
			a.add(f);
		}
		return a;
	}

	public static boolean isCurrent(Path cacheDir, String fingerprint) {
		JsonObject o = json(cacheDir);
		try {
			return o != null && o.get("version").getAsInt() == STADIUM_VERSION
					&& o.get("fingerprint").getAsString().equals(fingerprint);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static @Nullable JsonObject json(Path cacheDir) {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(cacheDir.resolve(DIR).resolve(JSON),
					StandardCharsets.UTF_8));
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}
}
