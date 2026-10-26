package dev.mcrl.rlassets;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.mcrl.rlassets.arena.ArenaCache;
import dev.mcrl.rlassets.arena.ArenaExtractor;
import dev.mcrl.rlassets.bodies.BodySpec;
import dev.mcrl.rlassets.extract.MaterialExtractor;
import dev.mcrl.rlassets.extract.RlExtractor;
import dev.mcrl.rlassets.extract.UpkExtractor;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.Effects;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;
import dev.mcrl.rlassets.stadium.StadiumCache;
import dev.mcrl.rlassets.stadium.StadiumExtractor;
import dev.mcrl.rlassets.upk.UpkWorld;
import dev.mcrl.rlassets.wwise.SoundEvents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class Converter {
	public enum Failure { NONE, RL_NOT_FOUND, TOOL_MISSING, ASSETS_CHANGED, VALIDATION, REFUSED, IO }

	public record ConvertResult(boolean ok, String message, Failure failure) {
		static ConvertResult success(String message) {
			return new ConvertResult(true, message, Failure.NONE);
		}

		static ConvertResult fail(Failure f, String message) {
			return new ConvertResult(false, message, f);
		}
	}

	public record Export(String pkg, String object, boolean required) {
	}

	public record Extra(String name, String pkg, String object) {
	}

	public static final List<Extra> EXTRAS = List.of(
			new Extra("boost_sheet", "Startup", "Octane_SM"),
			new Extra("amb_boost", "Body_Octane_SF", "OctaneAmbientBoost_SM"),
			new Extra("ball_arrow", "TAGame", "BallLocatorArrow_SM"));

	public static final List<Export> EXPORTS = List.of(
			new Export(OctaneVisual.BODY_PACKAGE, OctaneVisual.BODY_MESH, true),
			new Export("Startup.upk", "WHEEL_Star_SM", true),
			new Export("MENU_Main_p.upk", "Ball_DefaultBall00", true),
			new Export(EXTRAS.get(0).pkg() + ".upk", EXTRAS.get(0).object(), false),
			new Export(EXTRAS.get(1).pkg() + ".upk", EXTRAS.get(1).object(), false),
			new Export(EXTRAS.get(2).pkg() + ".upk", EXTRAS.get(2).object(), false));

	public static final Map<String, List<String>> HUD;

	static {
		Map<String, List<String>> hud = new LinkedHashMap<>();
		hud.put("GFX_Hud_SF", List.of("BoostMeter_Background", "BoostMeter_Glow", "BoostMeter_FillTintablePortion",
				"BoostMeter_Fill"));
		hud.put("Startup", List.of("GlowingRedDot", "Glow_LinearFade"));
		HUD = Collections.unmodifiableMap(hud);
	}

	public static final List<String> UNPACK = List.of("Core", "Engine", "GFxUI", "AkAudio", "IpDrv", "ProjectX", "TAGame",
			"Startup", "GameInfo_Soccar_SF", "Boost_Standard_SF", "ss_default_SF", "Body_Octane_SF", "Explosion_Default_SF",
			"Stadium_P");
	static final List<String> FINGERPRINT_PACKAGES = List.of("GFX_Hud_SF", "MENU_Main_p");

	private static final String BODY_MAT = MeshAssembler.SHELL_MATERIAL_NAME;

	private Converter() {
	}

	public static String fingerprint(LocateResult loc) {
		LinkedHashSet<String> extra = new LinkedHashSet<>();
		extra.add(OctaneVisual.BODY_PACKAGE);
		extra.addAll(SoundEvents.bankFiles());
		for (String p : UNPACK) {
			extra.add(p + ".upk");
		}
		for (String p : FINGERPRINT_PACKAGES) {
			extra.add(p + ".upk");
		}
		extra.removeAll(LocateResult.FINGERPRINT_FILES);
		String f = loc.fingerprint(extra);
		return f.isEmpty() ? f : f + ";converter=" + CONVERTER_VERSION + ";sounds=" + SoundEvents.VERSION;
	}

	public static final int CONVERTER_VERSION = 8;

	public static boolean isCurrent(LocateResult loc, Path cacheDir) {
		String f = fingerprint(loc);
		return !f.isEmpty() && f.equals(CacheWriter.fingerprintOf(cacheDir));
	}

	public static boolean isCurrent(LocateResult loc, Path cacheDir, @Nullable String decoder) {
		if (!isCurrent(loc, cacheDir)) {
			return false;
		}
		if (decoder == null) {
			return true;
		}
		CacheWriter.SoundsStatus st = CacheWriter.soundsStatus(cacheDir);
		if (st.missingKind() == null) {
			return st.missing() == null;
		}
		return switch (st.missingKind()) {
			case NO_DECODER -> false;
			case DECODE_FAILED -> decoder.equals(st.decoder());
			case BANKS -> true;
		};
	}

	public static ConvertResult convert(LocateResult loc, UmodelRunner umodel, @Nullable VgmstreamRunner vgmstream, Path cacheDir,
			Consumer<String> progress) {
		Path install = loc.installDir() != null ? loc.installDir() : loc.cookedDir();
		return convert(loc, umodel, vgmstream, cacheDir, progress, CacheWriter.Guard.standard(install), new UpkExtractor());
	}

	static ConvertResult convert(LocateResult loc, UmodelRunner umodel, @Nullable VgmstreamRunner vgmstream, Path cacheDir,
			Consumer<String> progress, CacheWriter.Guard guard, RlExtractor extractor) {
		long start = System.nanoTime();
		if (loc.state() != LocateResult.State.FOUND || loc.cookedDir() == null) {
			return ConvertResult.fail(Failure.RL_NOT_FOUND, loc.hint());
		}
		try {
			CacheWriter.checkTarget(cacheDir, guard);
		} catch (CacheWriter.TargetRefusedException e) {
			return ConvertResult.fail(Failure.REFUSED, e.getMessage());
		} catch (IOException e) {
			return ConvertResult.fail(Failure.IO, "cannot check the cache dir: " + e.getMessage());
		}
		Path work = umodel.workDir().resolve("rlconvert-" + ProcessHandle.current().pid());
		Path export = work.resolve("export");
		List<String> warnings = new ArrayList<>();
		ConvertResult r;
		try {
			removeStaleWork(umodel.workDir());
			CacheWriter.deleteTree(work);
			r = exportAll(umodel, export, warnings, progress);
			FxData fx = null;
			Assets assets = null;
			if (r == null) {
				fx = fxData(umodel, extractor, work.resolve("unpacked"), warnings, progress);
				r = fx.failure();
			}
			if (r == null) {
				assets = assets(umodel, extractor, work, fx, warnings, progress);
				r = assets.failure();
			}
			if (r == null) {
				Sounds sounds = extractSounds(loc, vgmstream, work.resolve("sounds-out"), warnings, progress);
				sounds = keepPreviousSounds(sounds, cacheDir, warnings);
				r = build(export, fingerprint(loc), rlVersion(loc.cookedDir()), cacheDir, guard, warnings, progress, sounds,
						fx, assets, start);
			}
		} catch (IOException e) {
			r = ConvertResult.fail(Failure.IO, "conversion failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			r = ConvertResult.fail(Failure.IO, "conversion interrupted (" + e.getMessage() + ")");
		} catch (RuntimeException e) {
			r = ConvertResult.fail(Failure.IO, "conversion failed: " + e);
		}
		try {
			CacheWriter.deleteTree(work.resolve("unpacked"));
			if (r.ok()) {
				CacheWriter.deleteTree(work);
			}
		} catch (IOException leftForNextRun) {
		}
		if (!r.ok() && (r.failure() == Failure.VALIDATION || r.failure() == Failure.ASSETS_CHANGED
				|| r.failure() == Failure.TOOL_MISSING)) {
			r = new ConvertResult(false, r.message() + " (exports kept in " + work + ")", r.failure());
		}
		return r;
	}

	private static @Nullable ConvertResult exportAll(UmodelRunner umodel, Path export, List<String> warnings,
			Consumer<String> progress) throws IOException, InterruptedException {
		for (Export e : EXPORTS) {
			progress.accept("Exporting " + e.pkg() + " " + e.object());
			ToolRunner.ToolResult t;
			try {
				t = umodel.export(e.pkg(), e.object(), export);
			} catch (IllegalArgumentException refused) {
				return ConvertResult.fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage());
			}
			if (t.exit() != 0 || t.timedOut()) {
				String out = lastLine(t.stderrTail().isBlank() ? t.stdoutTail() : t.stderrTail());
				if (couldNotRun(t)) {
					return ConvertResult.fail(Failure.TOOL_MISSING, "umodel could not run (exit " + t.exit() + "): " + out);
				}
				String why = "umodel " + (t.timedOut() ? "timed out" : "exit " + t.exit()) + " exporting " + e.pkg() + " "
						+ e.object() + ": " + out;
				if (e.required()) {
					return ConvertResult.fail(Failure.ASSETS_CHANGED, why);
				}
				warnings.add(why + " (optional, skipped)");
			}
		}
		return null;
	}

	record FxData(RlExtractor.@Nullable RlData data, @Nullable String missing, @Nullable ConvertResult failure,
			@Nullable ConvertResult unpack) {
		static final FxData NONE = new FxData(null, null, null, null);
	}

	private static FxData fxData(UmodelRunner umodel, RlExtractor extractor, Path unpacked, List<String> warnings,
			Consumer<String> progress) throws IOException, InterruptedException {
		progress.accept("Unpacking " + UNPACK.size() + " packages");
		ToolRunner.ToolResult t;
		try {
			t = umodel.unpack(UNPACK, unpacked);
		} catch (IllegalArgumentException refused) {
			return new FxData(null, null, ConvertResult.fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage()),
					null);
		}
		List<String> missing = new ArrayList<>();
		for (String p : UNPACK) {
			if (!Files.isRegularFile(unpacked.resolve(p + ".bin"))) {
				missing.add(p);
			}
		}
		String out = lastLine(t.stderrTail().isBlank() ? t.stdoutTail() : t.stderrTail());
		if (missing.size() == UNPACK.size()) {
			return new FxData(null, null, ConvertResult.fail(Failure.TOOL_MISSING, "umodel has no -mcrl-unpack or failed (exit "
					+ t.exit() + "): rebuild it with scripts/setup-tools.sh" + (out.isEmpty() ? "" : " (" + out + ")")), null);
		}
		ConvertResult unpack = null;
		if (!missing.isEmpty()) {
			warnings.add("umodel did not unpack " + String.join(", ", missing) + " (exit " + t.exit() + (out.isEmpty() ? "" : ": " + out)
					+ "); FX data from the others");
			if (t.exit() != 0 || t.timedOut()) {
				unpack = ConvertResult.fail(t.timedOut() ? Failure.IO : Failure.TOOL_MISSING, "umodel "
						+ (t.timedOut() ? "timed out" : "exit " + t.exit()) + " unpacking, so " + String.join(", ", missing)
						+ (missing.size() == 1 ? " was" : " were") + " not unpacked" + (out.isEmpty() ? "" : " (" + out + ")"));
			}
		}
		progress.accept("Reading the FX data");
		try {
			RlExtractor.RlData d = extractor.extract(unpacked, warnings);
			Effects.fromJson(d.effects());
			CacheJson.fxActors(d.fxActors().has("actors") ? d.fxActors().getAsJsonObject("actors") : new JsonObject());
			return new FxData(d, null, null, unpack);
		} catch (IOException | RuntimeException e) {
			String why = e.getMessage() != null ? e.getMessage() : e.toString();
			warnings.add("FX data skipped: " + why);
			return new FxData(null, why, null, unpack);
		}
	}

	record FxMesh(String name, String ref, String pkg, String object, String topPackage) {
	}

	record Assets(List<FxMesh> fxMeshes, @Nullable Materials materials, Map<String, Path> textures, Map<String, Path> hud,
			List<String> problems, @Nullable ConvertResult failure) {
	}

	private static Assets assets(UmodelRunner umodel, RlExtractor extractor, Path work, FxData fx, List<String> warnings,
			Consumer<String> progress) throws IOException, InterruptedException {
		Path export = work.resolve("export");
		RlExtractor.RlData data = fx.data();
		List<FxMesh> fxMeshes = new ArrayList<>();
		if (data != null) {
			JsonObject meshMap = data.effects().has("meshes") ? data.effects().getAsJsonObject("meshes") : new JsonObject();
			for (String ref : data.meshRefs()) {
				if (!meshMap.has(ref)) {
					continue;
				}
				FxMesh m = new FxMesh(meshMap.get(ref).getAsString(), ref, ref.substring(0, Math.max(0, ref.indexOf(':'))),
						lastName(ref), ref.substring(ref.indexOf(':') + 1).split("\\.")[0]);
				progress.accept("Exporting " + m.pkg() + ".upk " + m.object());
				ToolRunner.ToolResult t;
				try {
					t = umodel.export(m.pkg() + ".upk", m.object(), export);
				} catch (IllegalArgumentException refused) {
					return failed(ConvertResult.fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage()));
				}
				if (t.exit() != 0 || t.timedOut()) {
					warnings.add("FX mesh " + ref + " not exported (umodel " + (t.timedOut() ? "timed out" : "exit " + t.exit())
							+ "), so effects.json has no mesh for it");
					meshMap.remove(ref);
				} else {
					fxMeshes.add(m);
				}
			}
		}

		Map<String, Path> textures = new LinkedHashMap<>(), hud = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		Map<String, LinkedHashSet<String>> batches = new LinkedHashMap<>();
		MaterialExtractor.Result mr;
		progress.accept("Reading the materials");
		try {
			mr = extractor.materials(work.resolve("unpacked"), roots(Exports.index(export), fxMeshes),
					data != null ? data.materialRefs() : List.of(), warnings);
		} catch (IOException | RuntimeException e) {
			return lacking(fx, "the materials could not be read (" + (e.getMessage() != null ? e.getMessage() : e)
					+ "), and the cache needs them");
		}
		for (MaterialExtractor.Texture t : mr.textures().values()) {
			if (t.info() == null) {
				warnings.add("runtime texture, not exported: " + t.name() + " (" + t.className() + ")");
			} else {
				batches.computeIfAbsent(t.info().pkg(), k -> new LinkedHashSet<>()).add(lastName(t.path()));
			}
		}
		HUD.forEach((pkg, objs) -> batches.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).addAll(objs));
		Path raw = work.resolve("raw");
		for (Map.Entry<String, LinkedHashSet<String>> b : batches.entrySet()) {
			progress.accept("Exporting " + b.getValue().size() + " textures from " + b.getKey() + ".upk");
			ConvertResult refused = exportRaw(umodel, b.getKey() + ".upk", List.copyOf(b.getValue()), raw);
			if (refused != null) {
				return failed(refused);
			}
		}
		Exports rx = Exports.index(raw);
		HUD.values().forEach(objs -> objs.forEach(o -> {
			Path f = rx.file(o + ".png");
			if (f == null) {
				warnings.add("HUD texture " + o + " not exported");
			} else {
				hud.put(stem(o), f);
			}
		}));
		Map<String, TextureInfo> infos = new LinkedHashMap<>();
		Map<String, String> stems = new HashMap<>();
		for (MaterialExtractor.Texture t : mr.textures().values()) {
			if (t.info() == null) {
				continue;
			}
			Path f = rx.texture(lastName(t.path()), t.path().substring(t.path().indexOf(':') + 1).split("\\.")[0]);
			if (f == null) {
				warnings.add("texture " + t.name() + " (" + t.path() + ") not exported");
				continue;
			}
			String stem = stem(t.name());
			String other = stems.putIfAbsent(stem, t.name());
			if (other != null) {
				problems.add("textures " + other + " and " + t.name() + " are both textures/" + stem + ".png");
				continue;
			}
			int[] size = pngSize(f);
			infos.put(t.name(), t.info().exported("textures/" + stem + ".png", size[0], size[1]));
			textures.put(stem, f);
		}
		return new Assets(fxMeshes, new Materials(mr.materials(), infos), textures, hud, problems, null);
	}

	private static Assets failed(ConvertResult r) {
		return new Assets(List.of(), null, Map.of(), Map.of(), List.of(), r);
	}

	private static Assets lacking(FxData fx, String why) {
		ConvertResult u = fx.unpack();
		return failed(u == null ? ConvertResult.fail(Failure.ASSETS_CHANGED, why)
				: ConvertResult.fail(u.failure(), u.message() + "; " + why));
	}

	public static @Nullable ConvertResult exportRaw(UmodelRunner umodel, String pkg, List<String> objects, Path out)
			throws IOException, InterruptedException {
		try {
			ToolRunner.ToolResult t = umodel.exportObjects(pkg, objects, out);
			if ((t.exit() != 0 || t.timedOut()) && objects.size() > 1) {
				for (String o : objects) {
					umodel.exportObjects(pkg, List.of(o), out);
				}
			}
			return null;
		} catch (IllegalArgumentException refused) {
			return ConvertResult.fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage());
		}
	}

	private static List<MaterialExtractor.Named> roots(Exports ex, List<FxMesh> fxMeshes) {
		Map<String, MaterialExtractor.Named> out = new LinkedHashMap<>();
		String bodyPkg = OctaneVisual.BODY_PACKAGE.replace(".upk", "");
		for (String mesh : List.of(OctaneVisual.BODY_MESH, "WHEEL_Star_SM", "Ball_DefaultBall00")) {
			materialsOf(ex.mesh(mesh)).forEach(m -> out.putIfAbsent(m, new MaterialExtractor.Named(m, List.of(bodyPkg))));
		}
		for (Extra e : EXTRAS) {
			materialsOf(ex.mesh(e.object())).forEach(m -> out.putIfAbsent(m, new MaterialExtractor.Named(m, List.of(e.pkg()))));
		}
		for (FxMesh m : fxMeshes) {
			materialsOf(ex.meshIn(m.object(), m.topPackage()))
					.forEach(n -> out.putIfAbsent(n, new MaterialExtractor.Named(n, List.of(m.pkg()))));
		}
		return List.copyOf(out.values());
	}

	private static List<String> materialsOf(@Nullable Path mesh) {
		if (mesh == null) {
			return List.of();
		}
		try {
			return slots(PskReader.read(mesh)).stream().filter(m -> !m.equals("None")).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	// umodel's material_<n> is an empty slot, UE3's None.
	public static List<String> slots(PskMesh psk) {
		return psk.materials().stream().map(m -> m.matches("material_\\d+") ? "None" : m).toList();
	}

	public static String stem(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}

	public static int[] pngSize(Path png) throws IOException {
		try (InputStream in = Files.newInputStream(png)) {
			byte[] h = in.readNBytes(24);
			if (h.length < 24 || (h[0] & 0xFF) != 0x89 || h[1] != 'P' || h[12] != 'I' || h[13] != 'H') {
				throw new IOException(png + " is not a PNG");
			}
			ByteBuffer b = ByteBuffer.wrap(h);
			return new int[] {b.getInt(16), b.getInt(20)};
		}
	}

	private static String lastName(String path) {
		return path.substring(Math.max(path.lastIndexOf(':'), path.lastIndexOf('.')) + 1);
	}

	// nice exits 126 or 127: the tool never started.
	public static boolean couldNotRun(ToolRunner.ToolResult t) {
		return !t.timedOut() && (t.exit() == 126 || t.exit() == 127);
	}

	record Sounds(JsonObject json, CacheWriter.SoundInfo info) {
		static final Sounds NONE = new Sounds(new JsonObject(), CacheWriter.SoundInfo.NONE);

		static Sounds missing(String why, CacheWriter.SoundsMissingKind kind, @Nullable String decoder) {
			return new Sounds(new JsonObject(), new CacheWriter.SoundInfo(null, why, kind, decoder, null));
		}
	}

	private static Sounds extractSounds(LocateResult loc, @Nullable VgmstreamRunner vgm, Path stage, List<String> warnings,
			Consumer<String> progress) throws IOException, InterruptedException {
		if (vgm == null) {
			String why = "vgmstream-cli not available";
			warnings.add("sounds skipped: " + why);
			return Sounds.missing(why, CacheWriter.SoundsMissingKind.NO_DECODER, null);
		}
		String decoder = VgmstreamRunner.identity(vgm.executable());
		progress.accept("Extracting sounds");
		SoundExtractor.SoundSet set;
		try {
			set = SoundExtractor.extract(loc.cookedDir(), vgm, stage);
		} catch (IllegalArgumentException refused) {
			String why = "vgmstream not run: " + refused.getMessage();
			warnings.add("sounds skipped: " + why);
			return Sounds.missing(why, CacheWriter.SoundsMissingKind.DECODE_FAILED, decoder);
		}
		if (set.model().banks() == 0 || set.model().model().events().isEmpty()) {
			List<String> problems = set.model().warnings();
			String why = (set.model().banks() == 0 ? "no RL sound bank could be read"
					: "no RL sound event is in the banks") + (problems.isEmpty() ? "" : " (" + problems.get(0) + ")");
			warnings.add("sounds skipped: " + why);
			return Sounds.missing(why, CacheWriter.SoundsMissingKind.BANKS, decoder);
		}
		if (set.media() == 0) {
			List<String> missing = set.model().missing(), problems = set.model().warnings();
			String why = "no sound could be decoded (" + (!missing.isEmpty() ? missing.size() + " missing, e.g. " + missing.get(0)
					: !problems.isEmpty() ? problems.get(0) : "the sound model has no media") + ")";
			warnings.add("sounds skipped: " + why);
			return Sounds.missing(why, CacheWriter.SoundsMissingKind.DECODE_FAILED, decoder);
		}
		warnings.addAll(set.warnings());
		return new Sounds(set.toJson(), new CacheWriter.SoundInfo(stage, null, null, decoder, null));
	}

	private static Sounds keepPreviousSounds(Sounds fresh, Path cacheDir, List<String> warnings) {
		CacheWriter.SoundInfo i = fresh.info();
		if (i.missing() == null) {
			return fresh;
		}
		JsonObject old = CacheWriter.reusableSounds(cacheDir);
		if (old == null) {
			return fresh;
		}
		warnings.add("kept the previous cache's sounds (this conversion got none: " + i.missing() + ")");
		return new Sounds(old, new CacheWriter.SoundInfo(cacheDir, null, i.missingKind(), i.decoder(), i.missing()));
	}

	static ConvertResult build(Path export, String fingerprint, String rlVersion, Path cacheDir, CacheWriter.Guard guard,
			List<String> warnings, Consumer<String> progress, Sounds sounds, FxData fx, Assets assets, long start)
			throws IOException {
		Exports ex = Exports.index(export);
		List<String> problems = new ArrayList<>(assets.problems());

		progress.accept("Reading meshes");
		Map<String, PskMesh> psk = new HashMap<>();
		String bodyObj = OctaneVisual.BODY_MESH;
		for (String obj : List.of(bodyObj, "WHEEL_Star_SM", "Ball_DefaultBall00")) {
			Path f = ex.mesh(obj);
			if (f == null) {
				return ConvertResult.fail(Failure.ASSETS_CHANGED, "umodel exported no mesh for " + obj
						+ " (renamed or moved by an RL update?)");
			}
			try {
				psk.put(obj, PskReader.read(f));
			} catch (IOException e) {
				return ConvertResult.fail(Failure.VALIDATION, "unreadable mesh: " + e.getMessage());
			}
		}
		PskMesh bodyPsk = psk.get(bodyObj), wheelPsk = psk.get("WHEEL_Star_SM"), ballPsk = psk.get("Ball_DefaultBall00");
		Path bodyProps = ex.file(bodyObj + ".props.txt");
		PropsReader.Props sockets = bodyProps != null ? PropsReader.read(bodyProps) : PropsReader.parse("");
		if (bodyProps == null) {
			warnings.add(bodyObj + ".props.txt not exported: no sockets");
		}

		progress.accept("Placing the body and the wheels as RL does");
		MeshAssembler.Body body = MeshAssembler.body(bodyPsk, sockets, BodySpec.OCTANE);
		problems.addAll(body.problems());
		warnings.addAll(body.warnings());
		MeshAssembler.Wheels wheels = MeshAssembler.wheels(wheelPsk);
		warnings.addAll(wheels.warnings());
		MeshAssembler.Ball ball = MeshAssembler.ball(ballPsk);
		problems.addAll(ball.problems());
		Map<String, MeshData> extras = new LinkedHashMap<>();
		Map<String, List<String>> extraMaterials = new LinkedHashMap<>();
		for (Extra e : EXTRAS) {
			extra(e.name(), e.object(), ex.mesh(e.object()), extras, extraMaterials, warnings);
		}
		JsonObject meshMap = fx.data() != null && fx.data().effects().has("meshes")
				? fx.data().effects().getAsJsonObject("meshes") : new JsonObject();
		for (FxMesh m : assets.fxMeshes()) {
			if (!extra(m.name(), m.object(), ex.meshIn(m.object(), m.topPackage()), extras, extraMaterials, warnings)) {
				meshMap.remove(m.ref());
			}
		}

		Materials materials = assets.materials() != null ? assets.materials() : Materials.EMPTY;
		String bodyMat = material(bodyPsk, body.shellMaterial(), BODY_MAT);
		String skinName = materials.texture(bodyMat, "Skin");
		skinName = skinName != null ? skinName : "Pepe_Body_BlankSkin";
		Path skinFile = materials.textures().containsKey(skinName) ? assets.textures().get(stem(skinName)) : null;
		double skinFraction = skinFile == null ? Double.NaN : MeshAssembler.maskFraction(body.shell(),
				TextureBaker.Texels.read(skinFile));

		Map<String, CacheWriter.MeshEntry> meshes = new LinkedHashMap<>();
		meshes.put("body_shell", new CacheWriter.MeshEntry(body.shell(), "body_blue"));
		meshes.put("chassis", new CacheWriter.MeshEntry(body.chassis(), "chassis"));
		wheels.meshes().forEach((name, m) ->
				meshes.put(name, new CacheWriter.MeshEntry(m, name.startsWith("wheel_tyre") ? "wheel_tyre" : "wheel_rim")));
		meshes.put("ball", new CacheWriter.MeshEntry(ball.mesh(), "ball"));

		JsonObject wheelRig = body.rig() != null ? body.rig().toJson() : new JsonObject();
		JsonObject bodyBlock = new JsonObject();
		bodyBlock.addProperty("id", BodySpec.OCTANE.id());
		bodyBlock.addProperty("preset", BodySpec.OCTANE.preset());
		bodyBlock.addProperty("shellMaterial", bodyMat);
		bodyBlock.addProperty("chassisMaterial", material(bodyPsk, body.chassisMaterial(), MeshAssembler.CHASSIS_MATERIAL_NAME));
		bodyBlock.addProperty("fxActor", BodySpec.OCTANE.fxActor());
		JsonObject alignment = report(bodyObj, bodyPsk, body, wheels, ball, skinFraction, problems, warnings);
		JsonObject tex = new JsonObject();
		tex.addProperty("raw", assets.textures().size());
		tex.addProperty("hud", assets.hud().size());
		tex.addProperty("materials", materials.materials().size());
		tex.addProperty("skin", skinName);
		alignment.add("textures", tex);
		RlExtractor.RlData data = fx.data();
		if (data != null) {
			alignment.add("upkCheck", data.upkCheck());
		}
		alignment.addProperty("conversionSeconds", Math.round((System.nanoTime() - start) / 1e7) / 100.0);
		for (String w : warnings) {
			progress.accept("warning: " + w);
		}
		progress.accept("Writing the cache");
		CacheWriter.Fx fxFiles = data != null
				? new CacheWriter.Fx(data.effects(), data.fxActors(), CacheJson.soundCues(data.soundCues()), null)
				: new CacheWriter.Fx(null, null, fx.missing());
		CacheWriter.Raw raw = new CacheWriter.Raw(extraMaterials,
				assets.materials() != null ? CacheJson.materials(assets.materials()) : null, assets.textures(), assets.hud());
		try {
			List<String> late = CacheWriter.write(cacheDir, new CacheWriter.Content(fingerprint, rlVersion, meshes,
					body.sockets(), sounds.json(), extras, alignment, problems, sounds.info(), wheelRig, fxFiles, raw, bodyBlock),
					guard);
			for (String w : late) {
				progress.accept("warning: " + w);
			}
			warnings.addAll(late);
		} catch (CacheWriter.ValidationException e) {
			Files.createDirectories(export.getParent());
			Files.writeString(export.resolveSibling("alignment.json"), alignment.toString(), StandardCharsets.UTF_8);
			return ConvertResult.fail(Failure.VALIDATION, e.getMessage());
		}
		String msg = String.format(Locale.ROOT,
				"Rocket League assets converted into %s: body %s at Tz %.4f UU, ball radius %.3f UU (scale %.4f, %.2f%% outward)",
				cacheDir, bodyObj, body.tz(), ball.fittedRadius(), ball.scale(), 100 * ball.outwardFraction());
		if (!warnings.isEmpty()) {
			msg += "; warnings: " + String.join("; ", warnings);
		}
		return ConvertResult.success(msg);
	}

	private static boolean extra(String name, String object, @Nullable Path file, Map<String, MeshData> extras,
			Map<String, List<String>> materials, List<String> warnings) {
		if (file == null) {
			if (warnings.stream().noneMatch(w -> w.contains(" " + object + ":") || w.contains(" " + object + " "))) {
				warnings.add(object + " not exported, so the cache has no " + name);
			}
			return false;
		}
		try {
			PskMesh psk = PskReader.read(file);
			extras.put(name, MeshAssembler.mesh(psk, f -> true, MeshAssembler.Placement.IDENTITY));
			materials.put(name, slots(psk));
			return true;
		} catch (IOException e) {
			warnings.add(object + " unreadable, so the cache has no " + name + ": " + e.getMessage());
			return false;
		}
	}

	private static String material(PskMesh m, int i, String expected) {
		return i < m.materials().size() ? m.materials().get(i) : expected;
	}

	private static JsonObject report(String bodyObj, PskMesh bodyPsk, MeshAssembler.Body body, MeshAssembler.Wheels wheels,
			MeshAssembler.Ball ball, double skinFraction, List<String> problems, List<String> warnings) {
		JsonObject a = new JsonObject();
		a.addProperty("frame", "PSK frame (x forward, y left, z up), UU; meshes are stored as car-local MC blocks (x, z, -y)/50");
		JsonObject b = new JsonObject();
		b.addProperty("mesh", OctaneVisual.BODY_PACKAGE + " " + bodyObj);
		b.add("translationUU", arr(new double[] {OctaneVisual.HANDLING_PRESET_X_OFFSET, 0, body.tz()}));
		b.addProperty("TzUU", body.tz());
		b.addProperty("TzRule", "CalculatePresetSuspensionOffset (legacy): max(PF.z - FR_Disc.z, PB.z - BR_Disc.z)");
		b.addProperty("scale", 1);
		b.add("materials", strings(bodyPsk.materials()));
		b.addProperty("shellMaterial", body.shellMaterial());
		b.addProperty("chassisMaterial", body.chassisMaterial());
		num(b, "skinMaskFraction", skinFraction);
		b.addProperty("skinMaskRule", "fraction of body_shell triangles whose UV centroid samples Skin.R >= 0.5");
		JsonObject wl = new JsonObject();
		Map<String, double[]> preset = MeshAssembler.presetRest(BodySpec.OCTANE);
		for (String k : MeshAssembler.WHEELS) {
			JsonObject w = new JsonObject();
			w.addProperty("disc", MeshAssembler.discBone(k));
			if (body.discs().containsKey(k)) {
				w.add("discUU", arr(body.discs().get(k)));
			}
			if (body.pivots().containsKey(k)) {
				w.addProperty("pivot", MeshAssembler.pivotBone(k));
				w.add("pivotUU", arr(body.pivots().get(k)));
			}
			w.add("presetRestUU", arr(preset.get(k)));
			if (body.boneOffsets().containsKey(k)) {
				double[] o = body.boneOffsets().get(k);
				w.add("discMinusPresetUU", arr(o));
				w.addProperty("discMinusPresetLengthUU", Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
			}
			wl.add(k, w);
		}
		b.add("wheels", wl);
		MeshAssembler.BodyFit fit = body.lsqFit();
		if (fit != null) {
			JsonObject f = new JsonObject();
			f.addProperty("note", "diagnostic only: least-squares translation of the Disc joints onto RocketSim's wheel centres");
			f.add("translationUU", arr(fit.translation()));
			num(f, "maxResidualUU", fit.maxResidual());
			num(f, "rmsResidualUU", fit.rmsResidual());
			JsonObject r = new JsonObject();
			fit.residuals().forEach((k, v) -> r.add(k, arr(v)));
			f.add("residualsUU", r);
			b.add("lsqFitDiagnostic", f);
		}
		JsonObject sk = new JsonObject();
		body.sockets().forEach((n, v) -> sk.add(n, arr(new double[] {v[0], v[1], v[2]})));
		b.add("socketsBlocks", sk);
		a.add("body", b);

		JsonObject w = new JsonObject();
		w.addProperty("components", wheels.components());
		w.addProperty("tyreTriangles", wheels.tyreTriangles());
		w.addProperty("rimTriangles", wheels.rimTriangles());
		w.addProperty("tyreMaxRadialUU", wheels.tyreMaxRadial());
		w.addProperty("measuredRadiusUU", wheels.measuredRadius());
		w.addProperty("measuredHalfWidthUU", wheels.measuredHalfWidth());
		w.addProperty("expectedRadiusUU", MeshAssembler.WHEEL_MESH_RADIUS);
		w.addProperty("expectedHalfWidthUU", MeshAssembler.WHEEL_HALF_WIDTH);
		for (boolean front : new boolean[] {true, false}) {
			JsonObject ax = new JsonObject();
			double rs = OctaneVisual.radialScale(front), ws = OctaneVisual.axialScale(front);
			ax.add("scale", arr(new double[] {rs, ws, rs}));
			ax.addProperty("meshOffsetSideUU", OctaneVisual.meshOffsetSide(front));
			ax.addProperty("visualRadiusUU", wheels.measuredRadius() * rs);
			ax.addProperty("visualWidthUU", 2 * wheels.measuredHalfWidth() * ws);
			w.add(front ? "front" : "back", ax);
		}
		a.add("wheel", w);
		if (body.rig() != null) {
			a.add("wheels", body.rig().toJson());
		}

		JsonObject bl = new JsonObject();
		bl.add("centreUU", arr(ball.centre()));
		num(bl, "fittedRadiusUU", ball.fittedRadius());
		num(bl, "ratio", ball.fittedRadius() / MeshAssembler.BALL_RADIUS);
		num(bl, "scale", ball.scale());
		bl.addProperty("outwardFraction", ball.outwardFraction());
		a.add("ball", bl);

		a.add("problems", strings(problems));
		a.add("warnings", strings(warnings));
		return a;
	}

	private static void num(JsonObject o, String key, double v) {
		if (Double.isFinite(v)) {
			o.addProperty(key, v);
		} else {
			o.add(key, JsonNull.INSTANCE);
		}
	}

	private static JsonArray arr(double[] v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(Double.isFinite(d) ? new JsonPrimitive(d) : JsonNull.INSTANCE);
		}
		return a;
	}

	private static JsonArray strings(List<String> v) {
		JsonArray a = new JsonArray();
		v.forEach(a::add);
		return a;
	}

	static String rlVersion(Path cookedDir) {
		try (InputStream in = Files.newInputStream(cookedDir.resolve("Startup.upk"))) {
			byte[] h = in.readNBytes(8);
			if (h.length == 8) {
				ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
				if (b.getInt(0) == 0x9E2A83C1) {
					return "ue3 " + (b.getShort(4) & 0xFFFF) + "/" + (b.getShort(6) & 0xFFFF);
				}
			}
		} catch (IOException unknownVersion) {
		}
		return "unknown";
	}

	public static String lastLine(String s) {
		String t = s.strip();
		int i = t.lastIndexOf('\n');
		return i < 0 ? t : t.substring(i + 1);
	}

	public static void removeStaleWork(Path workDir) throws IOException {
		if (!Files.isDirectory(workDir)) {
			return;
		}
		List<Path> stale = new ArrayList<>();
		try (Stream<Path> s = Files.list(workDir)) {
			s.forEach(p -> {
				String n = p.getFileName().toString();
				if (n.matches("rl(convert|stadium)-\\d+|rlbody-\\d+-\\w+|bodyscan-\\d+|bodythumb-\\d+-\\w+")) {
					long pid = Long.parseLong(n.split("-")[1]);
					if (!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
						stale.add(p);
					}
				}
			});
		}
		for (Path p : stale) {
			CacheWriter.deleteTree(p);
		}
	}

	public static final String STADIUM = "Stadium_P";

	public static String stadiumFingerprint(LocateResult loc) {
		LinkedHashSet<String> files = new LinkedHashSet<>();
		files.add(STADIUM + ".upk");
		for (String p : UNPACK) {
			files.add(p + ".upk");
		}
		files.removeAll(LocateResult.FINGERPRINT_FILES);
		return loc.fingerprint(files);
	}

	public static ConvertResult convertStadium(LocateResult loc, UmodelRunner umodel, Path cacheDir, boolean force,
			Consumer<String> progress) {
		Path install = loc.installDir() != null ? loc.installDir() : loc.cookedDir();
		return convertStadium(loc, umodel, cacheDir, force, progress, CacheWriter.Guard.standard(install));
	}

	static ConvertResult convertStadium(LocateResult loc, UmodelRunner umodel, Path cacheDir, boolean force,
			Consumer<String> progress, CacheWriter.Guard guard) {
		long start = System.nanoTime();
		if (loc.state() != LocateResult.State.FOUND || loc.cookedDir() == null) {
			return ConvertResult.fail(Failure.RL_NOT_FOUND, loc.hint());
		}
		try {
			CacheWriter.checkTarget(cacheDir, guard);
		} catch (CacheWriter.TargetRefusedException e) {
			return ConvertResult.fail(Failure.REFUSED, e.getMessage());
		} catch (IOException e) {
			return ConvertResult.fail(Failure.IO, "cannot check the cache dir: " + e.getMessage());
		}
		if (CacheWriter.fingerprintOf(cacheDir) == null) {
			return ConvertResult.fail(Failure.REFUSED, "no RL cache at " + cacheDir + " to add the stadium to: convert the "
					+ "base cache first");
		}
		String files = stadiumFingerprint(loc);
		String arenaPrint = files + ";arena=" + ArenaCache.ARENA_VERSION;
		String stadiumPrint = files + ";stadium=" + StadiumCache.STADIUM_VERSION;
		if (!force && ArenaCache.isCurrent(cacheDir, arenaPrint) && StadiumCache.isCurrent(cacheDir, stadiumPrint)) {
			return ConvertResult.success("the stadium in " + cacheDir + " is up to date (fingerprint matches)");
		}
		Path work = umodel.workDir().resolve("rlstadium-" + ProcessHandle.current().pid());
		Path unpacked = work.resolve("unpacked");
		ConvertResult r;
		try {
			removeStaleWork(umodel.workDir());
			CacheWriter.deleteTree(work);
			List<String> packages = new ArrayList<>(new LinkedHashSet<>(UNPACK));
			if (!packages.contains(STADIUM)) {
				packages.add(STADIUM);
			}
			progress.accept("Unpacking " + packages.size() + " packages for the stadium");
			ToolRunner.ToolResult t;
			try {
				t = umodel.unpack(packages, unpacked);
			} catch (IllegalArgumentException refused) {
				return ConvertResult.fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage());
			}
			if (!Files.isRegularFile(unpacked.resolve(STADIUM + ".bin"))) {
				String out = lastLine(t.stderrTail().isBlank() ? t.stdoutTail() : t.stderrTail());
				return ConvertResult.fail(couldNotRun(t) || t.exit() == 0 ? Failure.TOOL_MISSING : Failure.ASSETS_CHANGED,
						"umodel did not unpack " + STADIUM + " (exit " + t.exit() + (out.isEmpty() ? "" : ": " + out) + ")");
			}
			progress.accept("Reading the arena collision meshes");
			UpkWorld world = new UpkWorld(unpacked, List.of(STADIUM));
			ArenaExtractor.Arena arena = ArenaExtractor.extract(world, STADIUM);
			ArenaExtractor.Acceptance acc = ArenaExtractor.accept(arena.cmf());
			for (String line : acc.perMesh()) {
				progress.accept("arena " + line);
			}
			List<String> problems = new ArrayList<>(arena.problems());
			problems.addAll(acc.problems());
			if (!problems.isEmpty()) {
				r = ConvertResult.fail(Failure.VALIDATION, "the arena collision meshes failed RocketSim's acceptance test ("
						+ Integer.bitCount(acc.knownMask()) + " of " + ArenaExtractor.MESHES + " known hashes, "
						+ acc.triangles() + " triangles): " + String.join("; ", problems) + "; per mesh: "
						+ String.join("; ", acc.perMesh()));
			} else {
				progress.accept("Writing the arena");
				List<String> late = new ArrayList<>(ArenaCache.write(cacheDir, arena.cmf(), arenaPrint, guard));
				String arenaLine = String.format(Locale.ROOT, "%d arena meshes, %d triangles, all %d RocketSim hashes",
						arena.meshes().size(), acc.triangles(), ArenaExtractor.MESHES);
				progress.accept("Reading the stadium's look");
				StadiumExtractor.StadiumData look = StadiumExtractor.extract(umodel, world, work);
				look.warnings().forEach(w -> progress.accept("warning: stadium " + w));
				String lookLine = String.format(Locale.ROOT, "%d instances (%d near, %d far), %d + %d triangles, %d of %d "
						+ "pads, %d key lights", look.instances().size(),
						look.instances().stream().filter(i -> i.tier() == StadiumExtractor.Tier.NEAR).count(),
						look.instances().stream().filter(i -> i.tier() == StadiumExtractor.Tier.FAR).count(),
						look.triangles(StadiumExtractor.Tier.NEAR), look.triangles(StadiumExtractor.Tier.FAR),
						look.padsMatched(), StadiumExtractor.PAD_COUNT, look.keys().size());
				progress.accept("stadium " + lookLine);
				if (!look.problems().isEmpty()) {
					r = ConvertResult.fail(Failure.VALIDATION, "the arena was written (" + arenaLine + "), but the stadium's "
							+ "look failed its checks (" + lookLine + "): " + String.join("; ", look.problems()));
				} else {
					progress.accept("Writing the stadium");
					late.addAll(StadiumCache.write(cacheDir, look, stadiumPrint, guard));
					r = ConvertResult.success(String.format(Locale.ROOT, "Stadium converted into %s: %s; %s (%.1f s)",
							cacheDir, arenaLine, lookLine, (System.nanoTime() - start) / 1e9));
				}
				late.forEach(w -> progress.accept("warning: " + w));
			}
		} catch (CacheWriter.TargetRefusedException e) {
			r = ConvertResult.fail(Failure.REFUSED, e.getMessage());
		} catch (IOException e) {
			r = ConvertResult.fail(Failure.IO, "stadium conversion failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			r = ConvertResult.fail(Failure.IO, "stadium conversion interrupted (" + e.getMessage() + ")");
		} catch (RuntimeException e) {
			r = ConvertResult.fail(Failure.IO, "stadium conversion failed: " + e);
		}
		try {
			CacheWriter.deleteTree(unpacked);
			if (r.ok()) {
				CacheWriter.deleteTree(work);
			}
		} catch (IOException leftForNextRun) {
		}
		return r;
	}

	public record Exports(Map<String, List<Path>> byName) {
		public static Exports index(Path root) throws IOException {
			Map<String, List<Path>> m = new HashMap<>();
			if (Files.isDirectory(root)) {
				try (Stream<Path> s = Files.walk(root)) {
					s.filter(Files::isRegularFile).sorted(Comparator.naturalOrder())
							.forEach(p -> m.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p));
				}
			}
			return new Exports(m);
		}

		public @Nullable Path file(String name) {
			List<Path> l = byName.get(name);
			return l == null ? null : l.getFirst();
		}

		public @Nullable Path mesh(String object) {
			Path p = file(object + ".pskx");
			return p != null ? p : file(object + ".psk");
		}

		public @Nullable Path meshIn(String object, @Nullable String pkg) {
			Path p = in(object + ".pskx", pkg);
			return p != null ? p : in(object + ".psk", pkg);
		}

		private @Nullable Path in(String file, @Nullable String pkg) {
			List<Path> l = byName.get(file);
			if (l == null) {
				return null;
			}
			if (pkg != null) {
				for (Path p : l) {
					for (Path part : p) {
						if (part.toString().equals(pkg)) {
							return p;
						}
					}
				}
			}
			return l.getFirst();
		}

		public @Nullable Path texture(String object, @Nullable String pkg) {
			return in(object + ".png", pkg);
		}
	}
}
