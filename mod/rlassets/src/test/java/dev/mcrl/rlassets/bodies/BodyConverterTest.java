package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.BodyFixtures;
import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.CacheWriterTest;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.Mcrm;
import dev.mcrl.rlassets.UmodelFixtures;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.extract.FxExtractor;
import dev.mcrl.rlassets.extract.MaterialExtractor;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;

class BodyConverterTest {
	@TempDir
	Path tmp;
	Path fixtures, log, cooked, cache;
	LocateResult loc;
	UmodelRunner umodel;
	final List<String> progress = new ArrayList<>();
	BodySpec spec;
	CacheWriter.Content baseContent;

	@BeforeEach
	void setUp() throws Exception {
		fixtures = tmp.resolve("fixtures");
		log = tmp.resolve("umodel.log");
		cooked = Files.createDirectories(tmp.resolve("rl/TAGame/CookedPCConsole"));
		for (String f : List.of("Startup.upk", "TAGame.upk", "Body_Test_SF.upk", "Body_Bad_SF.upk")) {
			Files.writeString(cooked.resolve(f), "not a package " + f);
		}
		loc = new LocateResult(LocateResult.State.FOUND, cooked, tmp.resolve("rl"), "test", "found via test");
		Path binary = tmp.resolve("bin/umodel");
		BodyFixtures.fakeUmodel(binary, fixtures, log);
		umodel = UmodelFixtures.runner(binary, cooked, tmp.resolve("work"));
		BodyFixtures.body(fixtures, "Body_Test_SF", "Body_Test_SK", List.of("Test_Chassis_MIC", "MIC_Body_Test"), 0);
		BodyFixtures.wheel(fixtures);
		BodyFixtures.staticMesh(fixtures, "Flame_SM", "FX", "M_Flame");
		BodyFixtures.texture(fixtures, "Body_Test_D", "Body_Test", 8, 4);

		cache = tmp.resolve("rl-cache/v2");
		CacheWriter.Content c = CacheWriterTest.content(List.of());
		JsonObject baseMaterials = CacheJson.materials(new Materials(Map.of(
				"Body_Paintable_Mat", base("Body_Paintable_Mat"), "MasterChassis_MAT", base("MasterChassis_MAT")),
				Map.of("Shared_N", info("Shared_N", "Startup", "textures/shared_n.png"))));
		JsonObject effects = JsonParser.parseString("{\"systems\": {\"Startup:FX.Base_PS\": {}}, \"meshes\": {}}")
				.getAsJsonObject();
		baseContent = new CacheWriter.Content(c.fingerprint(), c.rlVersion(), c.meshes(), c.sockets(),
				c.sounds(), c.extras(), c.alignment(), c.problems(), c.soundInfo(), c.wheels(),
				new CacheWriter.Fx(effects, new JsonObject(), null), new CacheWriter.Raw(Map.of(), baseMaterials,
						c.raw().textures(), Map.of()));
		CacheWriter.write(cache, baseContent);
		BodyCacheWriter.writeIndex(cache, new BodyIndex(BodyScanner.fingerprint(loc), List.of(
				new BodyIndex.Entry("Body_Bad", "Bad", "Body_Bad_SF.upk", 0, "Octane", false, "materials: X, Y, Z"),
				new BodyIndex.Entry("Body_Test", "Test", "Body_Test_SF.upk", 1, "MuscleCar", true, null))),
				CacheWriter.Guard.standard(null));
		BodySpec o = BodySpec.OCTANE;
		spec = new BodySpec("Body_Test", "Body_Test_SF.upk", "Body_Test_SK", 1, "MIC_Body_Test", "Test_Chassis_MIC",
				o.frontRest(), o.backRest(), o.presetFrontRadius(), o.presetBackRadius(), o.front(), o.back(), true,
				o.travelMin(), o.travelMax(), o.xOffset(), o.zOffset(), "Body_Test_SF:Body_Test.FXActor");
	}

	private static MaterialDef base(String name) {
		return new MaterialDef(name, "Startup:VPM." + name, "Material", null, Map.of(), Map.of(), Map.of(), Map.of(),
				"BLEND_Opaque", false, "MLM_Phong", new MaterialDef.Defaults(Map.of(), Map.of(), Map.of(), Map.of()),
				List.of());
	}

	private static MaterialDef mic(String name, String parent, Map<String, String> textures) {
		return new MaterialDef(name, "Body_Test_SF:Body_Test." + name, "MaterialInstanceConstant", parent, Map.of(),
				Map.of(), textures, Map.of(), null, null, null, null, List.of());
	}

	private static TextureInfo info(String name, String pkg, String file) {
		return new TextureInfo(name, pkg, file, 8, 4, 0, 0, "PF_DXT5", "TC_Default", true, "TEXTUREGROUP_Vehicle",
				"TA_Wrap", "TA_Wrap");
	}

	BodyConverter.Read read() {
		Map<String, MaterialDef> m = new LinkedHashMap<>();
		m.put("MIC_Body_Test", mic("MIC_Body_Test", "Body_Paintable_Mat", Map.of("Diffuse", "Body_Test_D", "Skin",
				"Shared_N")));
		m.put("Body_Test_Skin", mic("Body_Test_Skin", "MIC_Body_Test", Map.of("Diffuse", "Body_Test_D")));
		m.put("Test_Chassis_MIC", mic("Test_Chassis_MIC", "MasterChassis_MAT", Map.of("Diffuse", "Shared_N", "Masks",
				"Shared_N", "Normal", "Shared_N")));
		m.put("Body_Paintable_Mat", base("Body_Paintable_Mat"));
		m.put("MasterChassis_MAT", base("MasterChassis_MAT"));
		Map<String, MaterialExtractor.Texture> t = new LinkedHashMap<>();
		t.put("Body_Test_D", new MaterialExtractor.Texture("Body_Test_D", "Body_Test_SF:Body_Test.Body_Test_D",
				"Texture2D", info("Body_Test_D", "Body_Test_SF", "")));
		t.put("Shared_N", new MaterialExtractor.Texture("Shared_N", "Startup:Shared.Shared_N", "Texture2D",
				info("Shared_N", "Startup", "")));
		JsonObject effects = JsonParser.parseString("""
				{"systems": {"Body_Test_SF:FX.Flame_PS": {"emitters": []}, "Startup:FX.Base_PS": {"emitters": []}},
				 "meshes": {"Body_Test_SF:FX.Flame_SM": "fx_flame_sm"}}
				""").getAsJsonObject();
		JsonObject actors = JsonParser.parseString("""
				{"actors": {"body": {"path": "Body_Test_SF:Body_Test.FXActor", "class": "FXActor_Car_TA",
				  "props": {}, "attachments": []}}, "objects": {}}
				""").getAsJsonObject();
		return new BodyConverter.Read(spec, "Body_Test_Skin", new MaterialExtractor.Result(m, t),
				new FxExtractor.Result(effects, actors, Set.of(), Set.of("Body_Test_SF:FX.Flame_SM")));
	}

	Converter.ConvertResult convert(String id) {
		return BodyConverter.convert(loc, umodel, id, cache, progress::add,
				new CacheWriter.Guard(tmp.resolve(".minecraft"), null), (unpacked, entry, warnings) -> read());
	}

	private Converter.ConvertResult convertOrFail(String id) {
		Converter.ConvertResult r = convert(id);
		assertTrue(r.ok(), r.message());
		return r;
	}

	@Test
	void syntheticBodyConvertsEndToEnd() throws Exception {
		byte[] baseManifest = Files.readAllBytes(cache.resolve("manifest.json"));
		Converter.ConvertResult r = convertOrFail("Body_Test");
		assertTrue(r.message().contains("preset 1"), r.message());
		Path dir = BodyIds.dir(cache, "Body_Test");
		BodyManifest m = BodyManifest.fromJson(JsonParser.parseString(Files.readString(dir.resolve("manifest.json"))));
		assertEquals(1, m.preset());
		assertEquals("Body_Test_Skin", m.shellMaterial(), "the product's skin over the shell");
		assertEquals("Test_Chassis_MIC", m.chassisMaterial());
		assertEquals("Body_Test_SF:Body_Test.FXActor", m.fxActor());
		assertEquals(BodyConverter.fingerprint(loc, "Body_Test_SF.upk", CacheWriter.fingerprintOf(cache)),
				m.fingerprint());
		Set<String> want = new TreeSet<>(BodyManifest.MESHES);
		want.add("fx_flame_sm");
		assertEquals(want, new TreeSet<>(m.meshes().keySet()), "10 meshes and the FX mesh the base lacks");
		for (String name : m.meshes().keySet()) {
			assertTrue(Mcrm.read(dir.resolve(m.meshes().get(name))).vertexCount() > 0, name);
		}
		assertArrayEquals(baseManifest, Files.readAllBytes(cache.resolve("manifest.json")), "the base is untouched");

		Materials own = CacheJson.materials(JsonParser.parseString(Files.readString(dir.resolve("materials.json")))
				.getAsJsonObject());
		assertEquals(Set.of("MIC_Body_Test", "Body_Test_Skin", "Test_Chassis_MIC"), own.materials().keySet());
		assertEquals(Set.of("Body_Test_D"), own.textures().keySet());
		assertEquals("textures/body_test_d.png", own.textures().get("Body_Test_D").file());
		assertEquals(8, own.textures().get("Body_Test_D").width());
		assertTrue(Files.isRegularFile(dir.resolve("textures/body_test_d.png")));
		JsonObject effects = JsonParser.parseString(Files.readString(dir.resolve("effects.json"))).getAsJsonObject();
		assertEquals(Set.of("Body_Test_SF:FX.Flame_PS"), effects.getAsJsonObject("systems").keySet());
		JsonObject fx = JsonParser.parseString(Files.readString(dir.resolve("fxactors.json"))).getAsJsonObject();
		assertTrue(fx.getAsJsonObject("actors").has("body"));

		assertEquals(4, m.wheels().disc().length);
		assertTrue(m.sockets().containsKey("RocketBoost"));
		assertTrue(m.report().getAsJsonArray("problems").isEmpty(), m.report().toString());

		List<String> calls = Files.readAllLines(log);
		assertTrue(calls.get(0).contains("-mcrl-unpack") && calls.get(0).endsWith(" Core")
				&& calls.get(0).contains("-pkg=Startup -pkg=Body_Test_SF"), calls.get(0));
		try (Stream<Path> s = Files.list(tmp.resolve("work"))) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith("rlbody-")), "the work dir is gone");
		}
		assertTrue(BodyConverter.isCurrent(loc, cache, "Body_Test"));
	}

	@Test
	void missingRequiredTextureRefused() throws Exception {
		CacheWriter.deleteTree(fixtures.resolve("Body_Test_D"));
		Converter.ConvertResult r = convert("Body_Test");
		assertEquals(Converter.Failure.VALIDATION, r.failure(), r.message());
		assertTrue(r.message().contains("Body_Test_Skin.Diffuse = Body_Test_D"), r.message());
		assertFalse(Files.exists(BodyIds.dir(cache, "Body_Test")));
	}
}
