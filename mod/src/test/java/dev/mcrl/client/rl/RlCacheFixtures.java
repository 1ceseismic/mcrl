package dev.mcrl.client.rl;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.gson.JsonObject;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.WheelRig;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.rlassets.bodies.BodyManifest;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;

final class RlCacheFixtures {
	static final List<String> RAW_TEXTURES = List.of("Pepe_Body_D", "Pepe_Body_BlankSkin", "Chasis_Pepe_D", "OEM_D",
			"OEM_RGB", "Tire_Swarm_Tyr_Diffuse", "Ball_Default00_D");

	private RlCacheFixtures() {
	}

	static Path writeCache(Path dir, String fingerprint, Path scratch) throws Exception {
		Map<String, CacheWriter.MeshEntry> meshes = new LinkedHashMap<>();
		for (String m : RlCache.REQUIRED_MESHES) {
			meshes.put(m, new CacheWriter.MeshEntry(triangle(), textureOf(m)));
		}
		Map<String, Path> raw = new LinkedHashMap<>();
		Map<String, TextureInfo> infos = new LinkedHashMap<>();
		Path pngs = Files.createDirectories(scratch.resolve("raw-pngs"));
		for (String name : RAW_TEXTURES) {
			String stem = name.toLowerCase(Locale.ROOT);
			Path png = pngs.resolve(stem + ".png");
			ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
			raw.put(stem, png);
			infos.put(name, new TextureInfo(name, "Startup", "textures/" + stem + ".png", 2, 2, 2, 2, "PF_A8R8G8B8",
					"TC_Default", true, "TEXTUREGROUP_World", "TA_Wrap", "TA_Wrap"));
		}
		JsonObject materials = CacheJson.materials(new Materials(Map.of(), infos));
		Map<String, float[]> sockets = Map.of("RocketBoost", new float[] {-0.96f, 0.18f, 0f});
		CacheWriter.write(dir, new CacheWriter.Content(fingerprint, "test", meshes, sockets, new JsonObject(), Map.of(),
				new JsonObject(), List.of(), CacheWriter.SoundInfo.NONE, rig().toJson(), CacheWriter.Fx.NONE,
				new CacheWriter.Raw(Map.of(), materials, raw, Map.of())), new CacheWriter.Guard(scratch.resolve("fake-minecraft"), null));
		return dir;
	}

	static Path writeBody(Path cache, String id, int preset, String shellMat, String chassisMat,
			List<String> textures) throws Exception {
		Path dir = Files.createDirectories(BodyIds.dir(cache, id));
		Map<String, String> meshes = new LinkedHashMap<>();
		Files.createDirectories(dir.resolve("meshes"));
		for (String m : BodyManifest.MESHES) {
			Mcrm.write(dir.resolve("meshes").resolve(m + ".mcrm"), triangle());
			meshes.put(m, "meshes/" + m + ".mcrm");
		}
		Files.createDirectories(dir.resolve("textures"));
		Map<String, TextureInfo> infos = new LinkedHashMap<>();
		for (String name : textures) {
			String file = "textures/" + name.toLowerCase(Locale.ROOT) + ".png";
			ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", dir.resolve(file).toFile());
			infos.put(name, new TextureInfo(name, id + "_SF", file, 2, 2, 2, 2, "PF_A8R8G8B8", "TC_Default", true,
					"TEXTUREGROUP_Vehicle", "TA_Wrap", "TA_Wrap"));
		}
		Map<String, MaterialDef> mats = new LinkedHashMap<>();
		mats.put(shellMat, new MaterialDef(shellMat, id + "_SF:" + shellMat, "MaterialInstanceConstant", null, Map.of(),
				Map.of(), textures.isEmpty() ? Map.of() : Map.of("Diffuse", textures.get(0)), Map.of(), null, null, null,
				null, List.of()));
		mats.put(chassisMat, new MaterialDef(chassisMat, id + "_SF:" + chassisMat, "MaterialInstanceConstant", null,
				Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, List.of()));
		Files.writeString(dir.resolve("materials.json"), CacheJson.materials(new Materials(mats, infos)).toString(),
				StandardCharsets.UTF_8);
		BodyManifest m = new BodyManifest(id, BodyIds.label(id), preset, "fp;body=1", meshes, "materials.json", shellMat,
				chassisMat, id + "_SF:" + id + ".FXActor", null, null, Map.of("RocketBoost", new float[] {-1f, 0.2f, 0f}),
				rig(), new JsonObject());
		Files.writeString(dir.resolve("manifest.json"), m.toJson().toString(), StandardCharsets.UTF_8);
		return dir;
	}

	static WheelRig rig() {
		return new WheelRig(new float[][] {
			{1.0256f, -0.119f, 0.5221f}, {1.0256f, -0.119f, -0.5291f}, {-0.6864f, -0.086f, 0.5879f}, {-0.6864f, -0.086f, -0.5879f}},
				new float[][] {{1.0256f, -0.119f, 0.4985f}, {1.0256f, -0.119f, -0.5055f}, null, null},
				new double[] {-6.0, -6.0, -4.3, -4.3}, 0.0198, 0, 8, 10);
	}

	static LocateResult foundInstall(Path root) throws Exception {
		Path cooked = root.resolve("TAGame/CookedPCConsole");
		Files.createDirectories(cooked);
		Files.write(cooked.resolve("Startup.upk"), new byte[] {1, 2, 3, 4});
		return new LocateResult(LocateResult.State.FOUND, cooked, root, "test", "found via test");
	}

	private static String textureOf(String mesh) {
		if (mesh.equals("body_shell")) {
			return "body_blue";
		}
		if (mesh.startsWith("wheel_rim")) {
			return "wheel_rim";
		}
		if (mesh.startsWith("wheel_tyre")) {
			return "wheel_tyre";
		}
		return mesh;
	}

	private static MeshData triangle() {
		return new MeshData(new float[] {
			0, 0, 0, 0, 1, 0, 0, 0,
			1, 0, 0, 0, 1, 0, 1, 0,
			0, 0, 1, 0, 1, 0, 0, 1,
		}, new int[] {0, 2, 1});
	}
}
