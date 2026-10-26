package dev.mcrl.rlassets;

import static dev.mcrl.rlassets.PskFixtures.argb;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcrl.rlassets.arena.ArenaFixtures;
import dev.mcrl.rlassets.extract.MaterialExtractor;
import dev.mcrl.rlassets.extract.RlExtractor;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.SoundCue;
import dev.mcrl.rlassets.model.TextureInfo;
import dev.mcrl.rlassets.wwise.SoundEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConverterTest {
	@TempDir
	Path tmp;
	Path fixtures;
	Path log;
	Path cooked;
	LocateResult loc;
	UmodelRunner umodel;
	final List<String> progress = new ArrayList<>();
	RlExtractor extractor;
	final List<String> unpackedSeen = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		fixtures = tmp.resolve("fixtures");
		log = tmp.resolve("umodel.log");
		cooked = Files.createDirectories(tmp.resolve("rl/TAGame/CookedPCConsole"));
		Files.writeString(cooked.resolve("Startup.upk"), "not a package");
		loc = new LocateResult(LocateResult.State.FOUND, cooked, tmp.resolve("rl"), "test", "found via test");
		Path script = tmp.resolve("bin/umodel");
		Files.createDirectories(script.getParent());
		Files.writeString(script, """
				#!/bin/sh
				out=""; obj=""; unpack=0; pkgs=""
				for a in "$@"; do
				  case "$a" in -out=*) out="${a#-out=}" ;; -mcrl-unpack) unpack=1 ;; -pkg=*) pkgs="$pkgs ${a#-pkg=}" ;; esac
				  obj="$a"
				done
				echo "$*" >> '%s'
				if [ $unpack = 1 ]; then
				  mode=$(cat '%s' 2>/dev/null)
				  [ "$mode" = unpatched ] && { echo "bad command line: invalid option -mcrl-unpack" >&2; exit 1; }
				  mkdir -p "$out"; rc=0
				  for p in $obj $pkgs; do
				    if [ "$mode" = partial ] && [ "$p" = Explosion_Default_SF ]; then echo "mcrl-unpack: FAILED $p"; rc=1; continue; fi
				    if [ -f '%s'/unpack/"$p.bin" ]; then cp '%s'/unpack/"$p.bin" "$out/$p.bin"; else echo "image $p" > "$out/$p.bin"; fi
				    echo "mcrl-unpack: $p 10 bytes"
				  done
				  exit $rc
				fi
				objs=""
				for a in "$@"; do case "$a" in -obj=*) objs="$objs ${a#-obj=}" ;; esac; done
				if [ -n "$objs" ]; then
				  for o in $objs; do [ -d '%s'/"$o" ] || { echo "Export \"$o\" was not found in specified package(s)"; exit 1; }; done
				  mkdir -p "$out"; for o in $objs; do cp -r '%s'/"$o"/. "$out"/; done
				  exit 0
				fi
				[ -d '%s'/"$obj" ] || { echo "object $obj not found" >&2; exit 1; }
				mkdir -p "$out" && cp -r '%s'/"$obj"/. "$out"/
				""".formatted(log, tmp.resolve("unpack.mode"), fixtures, fixtures, fixtures, fixtures, fixtures, fixtures));
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
		umodel = new UmodelRunner(script, cooked, new ToolRunner(tmp.resolve("work"), null));
		extractor = (dir, warnings) -> {
			try (Stream<Path> s = Files.list(dir)) {
				s.map(p -> p.getFileName().toString()).sorted().forEach(unpackedSeen::add);
			}
			return stubData();
		};
		materialStub();
	}

	static RlExtractor.RlData stubData() {
		JsonObject effects = JsonParser.parseString("""
				{"systems": {"Pkg:FX.Smoke_PS": {"lodDistances": [0.0, 3072.0], "props": {},
				  "emitters": [{"name": "Smoke", "class": "ParticleSpriteEmitter", "cookedOut": false, "props": {},
				    "modules": [{"class": "ParticleModuleLifetime", "enabled": true, "props": {"LifeTime": {"$dist":
				      {"op": 1, "chunk": 1, "numElements": 1, "timeScale": 0.0, "startTime": 0.0, "table": [1, 1, 1, 1],
				       "param": null}}}}]}]}},
				 "meshes": {"Pkg:FX.Dome_SM": "fx_dome_sm"}}
				""").getAsJsonObject();
		JsonObject fx = JsonParser.parseString("""
				{"actors": {"car": {"path": "TAGame:FXActors.Car.Car_FXActor", "class": "FXActor_Car_TA",
				  "props": {"bRenderInactive": false}, "attachments": [{"name": "JumpParticle", "attachAny": ["Jump"],
				  "target": "ALL", "componentClass": "ParticleSystemComponent",
				  "component": {"$path": "TAGame:FXActors.Car.Car_FXActor.ParticleSystemComponent_0",
				    "Template": "Pkg:FX.Smoke_PS"}}]}},
				 "objects": {"ballDefault": {"$class": "Ball_TA", "VisualRadius": 95.0}}}
				""").getAsJsonObject();
		JsonObject check = new JsonObject();
		check.addProperty("systems", 1);
		check.addProperty("warnings", 0);
		return new RlExtractor.RlData(effects, fx, check, Set.of("Pkg:FX.Smoke_Mat"), Set.of("Pkg:FX.Dome_SM"),
				Map.of("TAGame:SFX_Car_Boost.Boost_Standard_Loop", new SoundCue("Play_Boost_Standard_Loop",
						"Stop_Boost_Standard_Loop")));
	}

	static void text(Path f, String s) throws IOException {
		Files.createDirectories(f.getParent());
		Files.writeString(f, s);
	}

	static String micProps(String... paramAndRef) {
		StringBuilder sb = new StringBuilder("TextureParameterValues[" + paramAndRef.length / 2 + "] =\n{\n");
		for (int i = 0; i < paramAndRef.length; i += 2) {
			sb.append("    TextureParameterValues[").append(i / 2).append("] =\n    {\n        ParameterInfo = \n")
					.append("        ParameterValue = Texture2D'").append(paramAndRef[i + 1]).append("'\n")
					.append("        ParameterName = ").append(paramAndRef[i]).append("\n    }\n");
		}
		return sb.append("}\n").toString();
	}

	static String socket(int i, String name, double x, double y, double z) {
		return "    Sockets[" + i + "] =\n    {\n        SocketName = " + name + "\n        BoneName = chassis_jnt\n"
				+ "        RelativeLocation = { X=" + x + ", Y=" + y + ", Z=" + z + " }\n    }\n";
	}

	void writeFixtures(double frontDx, double backDx) throws IOException {
		Path body = fixtures.resolve("Body_Octane_PremiumSkin_SK");
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.materials.addAll(List.of("OctaneChassis_MIC", "MIC_Body_Octane"));
		PskFixtures.box(b, new double[] {-60, -36, -12}, new double[] {80, 36, 43}, 1, 0);
		PskFixtures.octaneWheelBones(b, true, frontDx, backDx);
		b.write(body.resolve("Body_Octane_SF/SkeletalMesh3/Body_Octane_PremiumSkin_SK.psk"));
		text(body.resolve("Body_Octane_SF/SkeletalMesh3/Body_Octane_PremiumSkin_SK.props.txt"), "Sockets[3] =\n{\n"
				+ socket(0, "RocketBoost", -44.75, 0, 7.5) + socket(1, "BoostEmitterLeft", -46, -8, 6.5)
				+ socket(2, "BoostEmitterRight", -46, 8, 6.5) + "}\n");
		text(body.resolve("Body_Octane/MaterialInstanceConstant/MIC_Body_Octane.props.txt"),
				micProps("Diffuse", "Body_Octane_Textures.Pepe_Body_D", "Skin", "Body_Octane_Textures.Pepe_Body_BlankSkin"));
		text(body.resolve("Body_Octane/MaterialInstanceConstant/OctaneChassis_MIC.props.txt"),
				micProps("Diffuse", "Body_OctaneTest.Chasis_Pepe_D"));
		PskFixtures.png(body.resolve("Body_Octane_Textures/Texture2D/Pepe_Body_D.png"), 8, 8, (x, y) -> argb(150, 255, 255, 255));
		PskFixtures.png(body.resolve("Body_Octane_Textures/Texture2D/Pepe_Body_BlankSkin.png"), 4, 4,
				(x, y) -> argb(255, x < 2 ? 255 : 0, 0, 0));
		PskFixtures.png(body.resolve("Body_OctaneTest/Texture2D/Chasis_Pepe_D.png"), 4, 4, (x, y) -> argb(255, 40, 40, 40));

		Path wheel = fixtures.resolve("WHEEL_Star_SM");
		PskFixtures.Builder w = new PskFixtures.Builder();
		w.materials.add("Wheel_OEM_MIC");
		PskFixtures.ring(w, 10, 5, 12, 0);
		PskFixtures.ring(w, 16.313, 7.264, 16, 0);
		w.write(wheel.resolve("WHEEL_Star/StaticMesh3/WHEEL_Star_SM.pskx"));
		text(wheel.resolve("WHEEL_Star/MaterialInstanceConstant/Wheel_OEM_MIC.props.txt"),
				micProps("RimDiffuse", "WHEEL_Star.OEM_D", "RimRGB", "WHEEL_Star.OEM_RGB", "TireDiffuse",
						"Tire_Swarm_Tyr_Textures.Tire_Swarm_Tyr_Diffuse"));
		PskFixtures.png(wheel.resolve("WHEEL_Star/Texture2D/OEM_D.png"), 4, 4, (x, y) -> argb(255, 10, 10, 10));
		PskFixtures.png(wheel.resolve("WHEEL_Star/Texture2D/OEM_RGB.png"), 4, 4, (x, y) -> argb(255, 0, 200, 128));
		PskFixtures.png(wheel.resolve("Tire_Swarm_Tyr_Textures/Texture2D/Tire_Swarm_Tyr_Diffuse.png"), 2, 2,
				(x, y) -> argb(255, 20, 20, 20));

		Path ball = fixtures.resolve("Ball_DefaultBall00");
		PskFixtures.Builder s = new PskFixtures.Builder();
		s.materials.add("MAT_Ball_V3");
		PskFixtures.sphere(s, new double[] {0, 0, 0.03}, 91.927, 12, 18, 0);
		s.write(ball.resolve("Ball_Default/StaticMesh3/Ball_DefaultBall00.pskx"));
		text(ball.resolve("Ball_Default/Material3/MAT_Ball_V3.props.txt"), """
				CollectedTextureParameters[1] =
				{
				    CollectedTextureParameters[0] =
				    {
				        Texture = Texture2D'Ball_Default_Textures.Textures.Ball_Default00_D'
				        Name = Diffuse
				        Group = None
				    }
				}
				""");
		PskFixtures.png(ball.resolve("Ball_Default_Textures/Texture2D/Ball_Default00_D.png"), 4, 4, (x, y) -> argb(255, 100, 100, 100));

		mesh(fixtures.resolve("Octane_SM/Boost_Tests/StaticMesh3/Octane_SM.pskx"), "MasterBoost_Standard_MIC");
		mesh(fixtures.resolve("OctaneAmbientBoost_SM/Boost_Sec/StaticMesh3/OctaneAmbientBoost_SM.pskx"), "material_0");
		mesh(fixtures.resolve("Dome_SM/FX/StaticMesh3/Dome_SM.pskx"), "M_Dome");
		for (String h : List.of("BoostMeter_Background", "BoostMeter_Glow", "BoostMeter_FillTintablePortion", "BoostMeter_Fill")) {
			PskFixtures.png(fixtures.resolve(h + "/GFX_Hud/Texture2D/" + h + ".png"), 8, 8, (x, y) -> argb(x * 30, 255, 128, 0));
		}
		PskFixtures.png(fixtures.resolve("GlowingRedDot/gfx_shared/Texture2D/GlowingRedDot.png"), 32, 32, (x, y) -> argb(200, 255, 0, 0));
		PskFixtures.png(fixtures.resolve("Glow_LinearFade/gfx_shared/Texture2D/Glow_LinearFade.png"), 16, 4, (x, y) -> argb(x * 16, 255, 255, 255));
		rawTextures();
	}

	static void mesh(Path f, String material) throws IOException {
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.materials.add(material);
		PskFixtures.box(b, new double[] {-10, -5, -2}, new double[] {0, 5, 2}, 0, 0);
		b.write(f);
	}

	Converter.ConvertResult convert(Path cache) {
		return Converter.convert(loc, umodel, null, cache, progress::add, new CacheWriter.Guard(tmp.resolve(".minecraft"), null),
				extractor);
	}

	@Test
	void syntheticInstallConvertsEndToEnd() throws IOException {
		writeFixtures(0, 0);
		Path cache = tmp.resolve("data/rl-cache/v1");
		Converter.ConvertResult r = convert(cache);
		assertTrue(r.ok(), r.message());
		assertEquals(Converter.Failure.NONE, r.failure());

		JsonObject m = JsonParser.parseString(Files.readString(cache.resolve("manifest.json"))).getAsJsonObject();
		assertEquals(2, m.get("format").getAsInt());
		assertEquals(Converter.fingerprint(loc), m.get("fingerprint").getAsString());
		assertEquals(Set.of("cues"), m.getAsJsonObject("sounds").keySet());
		assertTrue(m.get("soundsMissing").getAsString().contains("vgmstream"));
		assertTrue(r.message().contains("sounds skipped"), r.message());
		Set<String> meshes = new TreeSet<>(m.getAsJsonObject("meshes").keySet());
		Set<String> want = new TreeSet<>(List.of("body_shell", "chassis", "ball"));
		for (String part : new String[] {"rim", "tyre"}) {
			for (String axle : new String[] {"front", "back"}) {
				for (String side : new String[] {"right", "left"}) {
					want.add("wheel_" + part + "_" + axle + "_" + side);
				}
			}
		}
		assertEquals(want, meshes);
		for (String name : meshes) {
			JsonObject e = m.getAsJsonObject("meshes").getAsJsonObject(name);
			assertTrue(Mcrm.read(cache.resolve(e.get("file").getAsString())).vertexCount() > 0, name);
		}
		Set<String> raw = new TreeSet<>();
		TEXTURES.keySet().forEach(t -> raw.add(t.toLowerCase(java.util.Locale.ROOT)));
		assertEquals(raw, new TreeSet<>(m.getAsJsonObject("textures").keySet()));
		try (Stream<Path> files = Files.list(cache.resolve("textures"))) {
			Set<String> names = new TreeSet<>(files.map(f -> f.getFileName().toString().replace(".png", "")).toList());
			assertEquals(raw, names, "no baked map in the cache");
		}
		assertEquals("body_blue", m.getAsJsonObject("meshes").getAsJsonObject("body_shell").get("texture").getAsString());
		assertEquals("wheel_tyre",
				m.getAsJsonObject("meshes").getAsJsonObject("wheel_tyre_back_left").get("texture").getAsString());
		double tz = 0.0198;
		JsonObject sockets = m.getAsJsonObject("sockets");
		assertEquals(-46.0 / 50, sockets.getAsJsonArray("BoostEmitterLeft").get(0).getAsDouble(), 1e-6);
		assertEquals(-8.0 / 50, sockets.getAsJsonArray("BoostEmitterLeft").get(2).getAsDouble(), 1e-6);
		assertEquals(8.0 / 50, sockets.getAsJsonArray("BoostEmitterRight").get(2).getAsDouble(), 1e-6);
		assertEquals((7.5 + tz) / 50, sockets.getAsJsonArray("RocketBoost").get(1).getAsDouble(), 2e-6);

		WheelRig rig = WheelRig.fromJson(m.get("wheels"));
		assertArrayEquals(new float[] {1.02561f, (float) ((-5.9716 + tz) / 50), 26.1061f / 50}, rig.disc()[0], 4e-6f);
		assertArrayEquals(new float[] {-34.3206f / 50, (float) ((-4.3198 + tz) / 50), -29.395f / 50}, rig.disc()[3], 4e-6f);
		assertArrayEquals(new float[] {1.02561f, (float) ((-5.9717 + tz) / 50), -25.2743f / 50}, rig.pivot()[1], 4e-6f);
		assertNull(rig.pivot()[2]);
		assertEquals(tz, rig.tz(), 1e-4);
		assertArrayEquals(new double[] {-6.0, -6.0, -4.3, -4.3}, rig.presetRestZ());
		assertEquals(8, rig.travelMax());
		assertEquals(10, rig.blendRate());

		JsonObject a = JsonParser.parseString(Files.readString(cache.resolve("alignment.json"))).getAsJsonObject();
		JsonObject bodyA = a.getAsJsonObject("body");
		assertEquals(0.0, bodyA.getAsJsonArray("translationUU").get(0).getAsDouble());
		assertEquals(0.0, bodyA.getAsJsonArray("translationUU").get(1).getAsDouble());
		assertEquals(tz, bodyA.getAsJsonArray("translationUU").get(2).getAsDouble(), 1e-4);
		assertEquals("Body_Octane_SF.upk Body_Octane_PremiumSkin_SK", bodyA.get("mesh").getAsString());
		assertEquals(0.5, bodyA.get("skinMaskFraction").getAsDouble(), 1e-12, "the box top's two triangles, one on red");
		assertTrue(bodyA.has("lsqFitDiagnostic"));
		WheelRig reported = WheelRig.fromJson(a.get("wheels"));
		for (int i = 0; i < 4; i++) {
			assertArrayEquals(rig.disc()[i], reported.disc()[i]);
		}
		assertArrayEquals(rig.pivot()[0], reported.pivot()[0]);
		assertEquals(rig.tz(), reported.tz());
		assertEquals(91.927, a.getAsJsonObject("ball").get("fittedRadiusUU").getAsDouble(), 1e-2);
		assertEquals(TEXTURES.size(), a.getAsJsonObject("textures").get("raw").getAsInt());
		assertEquals("Pepe_Body_BlankSkin", a.getAsJsonObject("textures").get("skin").getAsString());
		assertTrue(a.getAsJsonArray("warnings").toString().contains("BallLocatorArrow_SM"), a.toString());

		List<String> calls = Files.readAllLines(log).stream()
				.filter(c -> !c.contains("-mcrl-unpack") && !c.contains("-mcrl-keep-alpha")).toList();
		assertEquals(7, calls.size(), calls.toString());
		for (String c : calls) {
			assertTrue(c.contains(" -export ") && c.contains("-path=" + cooked), c);
		}
		assertTrue(calls.get(0).endsWith("Body_Octane_SF.upk Body_Octane_PremiumSkin_SK"), calls.get(0));
		assertTrue(calls.get(2).endsWith("MENU_Main_p.upk Ball_DefaultBall00"));
		assertTrue(Converter.isCurrent(loc, cache));
		assertEquals("NO_DECODER", m.get("soundsMissingKind").getAsString());
		assertFalse(m.has("soundsDecoder"));
		assertTrue(Converter.isCurrent(loc, cache, null));
		assertFalse(Converter.isCurrent(loc, cache, "/some/vgmstream-cli;1@2"),
				"a cache made without a decoder is stale once vgmstream is available");
		assertTrue(Converter.fingerprint(loc).endsWith(";sounds=" + SoundEvents.VERSION), Converter.fingerprint(loc));
		for (String bank : SoundEvents.bankFiles()) {
			assertTrue(Converter.fingerprint(loc).contains(";" + bank + "="), bank + " is in the fingerprint");
		}
		assertTrue(Converter.fingerprint(loc).contains(";Body_Octane_SF.upk="), "the body's package is in the fingerprint");
		try (var s = Files.list(tmp.resolve("work"))) {
			assertTrue(s.noneMatch(p -> p.getFileName().toString().startsWith("rlconvert-")), "export dir removed");
		}
		assertTrue(progress.stream().anyMatch(l -> l.startsWith("warning: ") && l.contains("BallLocatorArrow_SM")), progress.toString());
	}

	@Test
	void misalignedBodyFailsValidationAndWritesNoCache() throws IOException {
		writeFixtures(0, -3.0);
		Path cache = tmp.resolve("data/rl-cache/v1");
		Converter.ConvertResult r = convert(cache);
		assertFalse(r.ok());
		assertEquals(Converter.Failure.VALIDATION, r.failure());
		assertTrue(r.message().contains("BR_Disc_jnt") && r.message().contains("preset rest"), r.message());
		assertFalse(Files.exists(cache));
		assertFalse(Converter.isCurrent(loc, cache));
	}

	Converter.ConvertResult convert(Path cache, Path vgmstream) {
		VgmstreamRunner vgm = vgmstream == null ? null : new VgmstreamRunner(vgmstream, new ToolRunner(tmp.resolve("work"), null));
		return Converter.convert(loc, umodel, vgm, cache, progress::add, new CacheWriter.Guard(tmp.resolve(".minecraft"), null),
				extractor);
	}

	static Map<String, String> tree(Path dir) throws IOException {
		Map<String, String> out = new TreeMap<>();
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : s.filter(Files::isRegularFile).toList()) {
				out.put(dir.relativize(p).toString(), java.util.HexFormat.of().formatHex(Files.readAllBytes(p)));
			}
		}
		return out;
	}

	static final Map<String, String> TEXTURES = new LinkedHashMap<>();

	static {
		TEXTURES.put("Pepe_Body_D", "Startup:Body_Octane_Textures.Pepe_Body_D");
		TEXTURES.put("Pepe_Body_BlankSkin", "Startup:Body_Octane_Textures.Pepe_Body_BlankSkin");
		TEXTURES.put("Chasis_Pepe_D", "Startup:Body_OctaneTest.Chasis_Pepe_D");
		TEXTURES.put("OEM_D", "Startup:WHEEL_Star.OEM_D");
		TEXTURES.put("OEM_RGB", "Startup:WHEEL_Star.OEM_RGB");
		TEXTURES.put("Tire_Swarm_Tyr_Diffuse", "Startup:Tire_Swarm_Tyr_Textures.Tire_Swarm_Tyr_Diffuse");
		TEXTURES.put("Ball_Default00_D", "GameInfo_Soccar_SF:Ball_Default_Textures.Ball_Default00_D");
		TEXTURES.put("Glow01_D", "TAGame:General_FX.Glow01_D");
		TEXTURES.put("Swirls_D", "Startup:General_FX.Mat.Swirls_D");
	}

	void rawTextures() throws IOException {
		for (Map.Entry<String, String> e : TEXTURES.entrySet()) {
			String top = e.getValue().substring(e.getValue().indexOf(':') + 1).split("\\.")[0];
			boolean skin = e.getKey().equals("Pepe_Body_BlankSkin");
			PskFixtures.png(fixtures.resolve(e.getKey() + "/" + top + "/Texture2D/" + e.getKey() + ".png"), 8, 4,
					(x, y) -> skin ? argb(0, x < 4 ? 255 : 0, 0, 0) : argb(x * 30, e.getKey().length() * 9, y * 60, 7));
		}
	}

	final List<MaterialExtractor.Named> askedNamed = new ArrayList<>();
	boolean materialsFail;
	final List<String> askedRefs = new ArrayList<>();

	void materialStub(String... leaveOut) {
		RlExtractor base = extractor;
		extractor = new RlExtractor() {
			@Override
			public RlData extract(Path unpacked, List<String> warnings) throws IOException {
				return base.extract(unpacked, warnings);
			}

			@Override
			public MaterialExtractor.Result materials(Path unpacked, List<MaterialExtractor.Named> named,
					Collection<String> refs, List<String> warnings) {
				if (materialsFail) {
					throw new IllegalStateException("no readable material package");
				}
				askedNamed.addAll(named);
				askedRefs.addAll(refs);
				Map<String, MaterialDef> m = new LinkedHashMap<>();
				m.put("MIC_Body_Octane", new MaterialDef("MIC_Body_Octane", "Startup:Body_Octane.MIC_Body_Octane",
						"MaterialInstanceConstant", "Body_Paintable_Mat", Map.of(), Map.of(),
						Map.of("Diffuse", "Pepe_Body_D", "Skin", "Pepe_Body_BlankSkin"), Map.of("Glass", true), null, null, null,
						null, List.of("Pepe_Body_D", "GrassCube")));
				m.put("Body_Paintable_Mat", new MaterialDef("Body_Paintable_Mat", "Startup:VPM.Body_Paintable_Mat", "Material",
						null, Map.of(), Map.of(), Map.of(), Map.of(), "BLEND_Opaque", false, "MLM_Custom",
						new MaterialDef.Defaults(Map.of(), Map.of(), Map.of(), Map.of()), List.of()));
				Map<String, MaterialExtractor.Texture> t = new LinkedHashMap<>();
				TEXTURES.forEach((name, path) -> {
					if (!List.of(leaveOut).contains(name)) {
						t.put(name, new MaterialExtractor.Texture(name, path, "Texture2D", new TextureInfo(name,
								path.substring(0, path.indexOf(':')), "", 2048, 1024, 0, 0, "PF_DXT5", "TC_Default", true,
								"TEXTUREGROUP_World", "TA_Wrap", "TA_Wrap")));
					}
				});
				t.put("GrassCube", new MaterialExtractor.Texture("GrassCube", "TAGame:Grass_Textures.GrassCube",
						"TextureRenderTargetCube", null));
				return new MaterialExtractor.Result(m, t);
			}
		};
	}

	static Path baseCache(Path cache) throws IOException {
		text(cache.resolve("manifest.json"), "{\"format\": 2, \"fingerprint\": \"base\"}");
		return cache;
	}

	Converter.ConvertResult convertStadium(Path cache, boolean force) {
		return Converter.convertStadium(loc, umodel, cache, force, progress::add,
				new CacheWriter.Guard(tmp.resolve(".minecraft"), null));
	}

	boolean unpacked() throws IOException {
		return Files.exists(log) && Files.readString(log).contains("-mcrl-unpack");
	}

	static byte[] quad(float z) {
		java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 24 + 48).order(java.nio.ByteOrder.LITTLE_ENDIAN);
		b.putInt(2).putInt(4);
		for (int i : new int[] {0, 1, 2, 0, 2, 3}) {
			b.putInt(i);
		}
		for (float[] v : new float[][] {{0, 0, z}, {1, 0, z}, {1, 1, z}, {0, 1, z}}) {
			b.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]);
		}
		return b.array();
	}

	@Test
	void a_failed_acceptance_writes_nothing_and_keeps_the_older_arena() throws IOException {
		Path cache = baseCache(tmp.resolve("data/rl-cache/v2"));
		ArenaFixtures.acceptedArena(cache, List.of(quad(3)), "older");
		Map<String, String> before = tree(cache);
		Files.createDirectories(fixtures.resolve("unpack"));
		ArenaFixtures.engine().write(fixtures.resolve("unpack"));
		ArenaFixtures.level(Converter.STADIUM, List.of(quad(0), quad(1))).write(fixtures.resolve("unpack"));
		Converter.ConvertResult r = convertStadium(cache, false);
		assertEquals(Converter.Failure.VALIDATION, r.failure(), r.message());
		assertTrue(r.message().contains("failed RocketSim's acceptance test (0 of 16 known hashes, 4 triangles)"), r.message());
		assertTrue(r.message().contains("mesh 1: 2 triangles"), r.message());
		assertTrue(unpacked());
		assertEquals(before, tree(cache), "nothing written, the older arena kept");
		assertFalse(Files.exists(tmp.resolve("work/rlstadium-" + ProcessHandle.current().pid() + "/unpacked")),
				"RL's package images are never kept");
	}
}
