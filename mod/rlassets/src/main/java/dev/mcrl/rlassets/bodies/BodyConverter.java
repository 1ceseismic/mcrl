package dev.mcrl.rlassets.bodies;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.Converter.ConvertResult;
import dev.mcrl.rlassets.Converter.Failure;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.MeshAssembler;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.PropsReader;
import dev.mcrl.rlassets.PskMesh;
import dev.mcrl.rlassets.PskReader;
import dev.mcrl.rlassets.ToolRunner;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.extract.FxExtractor;
import dev.mcrl.rlassets.extract.MaterialExtractor;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkWorld;

public final class BodyConverter {
	public static final List<String> UNPACK = BodyScanner.UNPACK;
	static final String WHEEL = "WHEEL_Star_SM";
	public static final Set<String> SKIN_PARAMETERS = Set.of("Diffuse", "CurvaturePack", "BodyMasks");
	public static final List<String> REQUIRED_SHELL = List.of("Diffuse", "Skin"), REQUIRED_CHASSIS = List.of("Diffuse",
			"Masks", "Normal");

	public record Read(BodySpec spec, String shellMaterial, MaterialExtractor.Result materials,
			FxExtractor.@Nullable Result fx) {
	}

	@FunctionalInterface
	public interface Reader {
		Read read(Path unpacked, BodyIndex.Entry entry, List<String> warnings) throws IOException;
	}

	public static final Reader UPK = BodyConverter::readUpk;

	private BodyConverter() {
	}

	public static String fingerprint(LocateResult loc, String pkg, @Nullable String baseFingerprint) {
		String f = loc.fingerprint(List.of(pkg, "TAGame.upk"));
		return f.isEmpty() ? f : f + ";body=" + BodyManifest.BODY_VERSION + ";base=" + digest(String.valueOf(
				baseFingerprint));
	}

	private static String digest(String s) {
		try {
			byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(d, 0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static boolean isCurrent(LocateResult loc, Path cacheDir, String id) {
		String want = fingerprint(loc, id + "_SF.upk", CacheWriter.fingerprintOf(cacheDir));
		if (want.isEmpty() || !BodyIds.valid(id)) {
			return false;
		}
		try {
			BodyManifest m = BodyManifest.fromJson(JsonParser.parseString(Files.readString(
					BodyIds.dir(cacheDir, id).resolve("manifest.json"), StandardCharsets.UTF_8)));
			return want.equals(m.fingerprint());
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	public static ConvertResult convert(LocateResult loc, UmodelRunner umodel, String id, Path cacheDir,
			Consumer<String> progress) {
		Path install = loc.installDir() != null ? loc.installDir() : loc.cookedDir();
		return convert(loc, umodel, id, cacheDir, progress, CacheWriter.Guard.standard(install), UPK);
	}

	static ConvertResult convert(LocateResult loc, UmodelRunner umodel, String id, Path cacheDir,
			Consumer<String> progress, CacheWriter.Guard guard, Reader reader) {
		long start = System.nanoTime();
		if (loc.state() != LocateResult.State.FOUND || loc.cookedDir() == null) {
			return fail(Failure.RL_NOT_FOUND, loc.hint());
		}
		if (!BodyIds.valid(id) || id.isEmpty() || BodyIds.isDefault(id)) {
			return fail(Failure.VALIDATION, "not a body to convert: '" + id + "' (the Octane is the base cache's)");
		}
		try {
			CacheWriter.checkTarget(cacheDir, guard);
		} catch (CacheWriter.TargetRefusedException e) {
			return fail(Failure.REFUSED, e.getMessage());
		} catch (IOException e) {
			return fail(Failure.IO, "cannot check the cache dir: " + e.getMessage());
		}
		Base base;
		try {
			base = Base.read(cacheDir);
		} catch (IOException | RuntimeException e) {
			return fail(Failure.REFUSED, "no readable RL cache at " + cacheDir + " (convert the base cache first): "
					+ e.getMessage());
		}
		BodyIndex.Entry entry = entry(cacheDir, id);
		if (entry == null) {
			return fail(Failure.VALIDATION, id + " is not in the body index (scan first)");
		}
		if (!entry.supported()) {
			return fail(Failure.VALIDATION, "unsupported body " + id + ": " + entry.why());
		}
		Path work = umodel.workDir().resolve("rlbody-" + ProcessHandle.current().pid() + "-" + id);
		List<String> warnings = new ArrayList<>();
		ConvertResult r;
		try {
			Converter.removeStaleWork(umodel.workDir());
			CacheWriter.deleteTree(work);
			r = run(loc, umodel, entry, cacheDir, guard, reader, base, work, warnings, progress, start);
		} catch (IOException e) {
			r = fail(Failure.IO, "body conversion failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			r = fail(Failure.IO, "body conversion interrupted (" + e.getMessage() + ")");
		} catch (RuntimeException e) {
			r = fail(Failure.IO, "body conversion failed: " + e);
		}
		try {
			CacheWriter.deleteTree(work.resolve("unpacked"));
			if (r.ok()) {
				CacheWriter.deleteTree(work);
			}
		} catch (IOException leftForNextRun) {
		}
		return r;
	}

	private static ConvertResult run(LocateResult loc, UmodelRunner umodel, BodyIndex.Entry entry, Path cacheDir,
			CacheWriter.Guard guard, Reader reader, Base base, Path work, List<String> warnings, Consumer<String> progress,
			long start) throws IOException, InterruptedException {
		String id = entry.id(), stem = BodyScanner.stem(entry.pkg());
		Path unpacked = work.resolve("unpacked"), export = work.resolve("export"), raw = work.resolve("raw");
		Path out = Files.createDirectories(work.resolve("out"));

		List<String> pkgs = new ArrayList<>(UNPACK);
		pkgs.add(stem);
		progress.accept("Unpacking " + pkgs.size() + " packages for " + id);
		ToolRunner.ToolResult t;
		try {
			t = umodel.unpack(pkgs, unpacked);
		} catch (IllegalArgumentException refused) {
			return fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage());
		}
		List<String> missing = pkgs.stream().filter(p -> !Files.isRegularFile(unpacked.resolve(p + ".bin"))).toList();
		if (!missing.isEmpty()) {
			String why = "umodel did not unpack " + String.join(", ", missing) + " (exit " + t.exit()
					+ (t.timedOut() ? ", timed out" : "") + ")";
			return fail(Converter.couldNotRun(t) || t.exit() != 0 || t.timedOut() ? Failure.TOOL_MISSING
					: Failure.ASSETS_CHANGED, why);
		}

		progress.accept("Reading " + id + "'s product, handling preset and materials");
		Read rd;
		try {
			rd = reader.read(unpacked, entry, warnings);
		} catch (IOException | IllegalArgumentException e) {
			return fail(Failure.ASSETS_CHANGED, id + " does not read: " + e.getMessage());
		}
		BodySpec spec = rd.spec();

		for (String[] e : new String[][] {{entry.pkg(), spec.mesh()}, {"Startup.upk", WHEEL}}) {
			progress.accept("Exporting " + e[0] + " " + e[1]);
			ConvertResult r = export(umodel, e[0], e[1], export);
			if (r != null) {
				return r;
			}
		}
		Converter.Exports ex = Converter.Exports.index(export);
		Path meshFile = ex.meshIn(spec.mesh(), stem), wheelFile = ex.mesh(WHEEL);
		if (meshFile == null || wheelFile == null) {
			return fail(Failure.ASSETS_CHANGED, "umodel exported no mesh for " + (meshFile == null ? spec.mesh() : WHEEL));
		}
		PskMesh psk, wheelPsk;
		try {
			psk = PskReader.read(meshFile);
			wheelPsk = PskReader.read(wheelFile);
		} catch (IOException e) {
			return fail(Failure.VALIDATION, "unreadable mesh: " + e.getMessage());
		}
		Path propsFile = ex.file(spec.mesh() + ".props.txt");
		PropsReader.Props props = propsFile != null ? PropsReader.read(propsFile) : PropsReader.parse("");
		if (propsFile == null) {
			warnings.add(spec.mesh() + ".props.txt not exported: no sockets");
		}

		progress.accept("Placing " + id + " and its wheels as RL does");
		List<String> problems = new ArrayList<>();
		MeshAssembler.Body body = MeshAssembler.body(psk, props, spec);
		problems.addAll(body.problems());
		warnings.addAll(body.warnings());
		MeshAssembler.Wheels wheels = MeshAssembler.wheels(wheelPsk, spec);
		warnings.addAll(wheels.warnings());
		Map<String, MeshData> meshes = new LinkedHashMap<>();
		meshes.put("body_shell", body.shell());
		meshes.put("chassis", body.chassis());
		meshes.putAll(wheels.meshes());

		Map<String, Path> files = new LinkedHashMap<>();
		JsonObject effects = null, fxActors = null;
		if (rd.fx() != null) {
			fxActors = rd.fx().fxActors().deepCopy();
			effects = fxMeshes(umodel, rd.fx(), base, stem, export, meshes, warnings, progress);
		}

		progress.accept("Exporting the textures " + id + " adds");
		Materials own = materials(umodel, rd.materials(), base, raw, files, problems, warnings);
		if (own == null) {
			return fail(Failure.TOOL_MISSING, "umodel not run for the textures");
		}
		problems.addAll(requiredTextures(base.all(), own, rd.shellMaterial(), spec.chassisMaterial()));
		files.put("materials.json", json(out.resolve("materials.json"), CacheJson.materials(own)));
		if (fxActors != null) {
			files.put("fxactors.json", json(out.resolve("fxactors.json"), fxActors));
		}
		if (effects != null) {
			files.put("effects.json", json(out.resolve("effects.json"), effects));
		}

		Map<String, String> meshFiles = new LinkedHashMap<>();
		for (String name : meshes.keySet()) {
			meshFiles.put(name, "meshes/" + name + ".mcrm");
		}
		JsonObject report = report(spec, rd, body, wheels, own, problems, warnings);
		report.addProperty("conversionSeconds", Math.round((System.nanoTime() - start) / 1e7) / 100.0);
		if (body.rig() == null || !problems.isEmpty()) {
			Files.writeString(work.resolve("report.json"), report.toString(), StandardCharsets.UTF_8);
			return fail(Failure.VALIDATION, id + ": " + String.join("; ", problems) + " (report in " + work + ")");
		}
		BodyManifest m = new BodyManifest(id, entry.label(), entry.preset(), fingerprint(loc, entry.pkg(),
				base.fingerprint()), meshFiles, "materials.json", rd.shellMaterial(), spec.chassisMaterial(), spec.fxActor(),
				fxActors != null ? "fxactors.json" : null, effects != null ? "effects.json" : null, body.sockets(),
				body.rig(), report);
		progress.accept("Writing bodies/" + id);
		try {
			for (String w : BodyCacheWriter.write(cacheDir, m, meshes, files, guard)) {
				warnings.add(w);
			}
		} catch (CacheWriter.ValidationException e) {
			return fail(Failure.VALIDATION, e.getMessage());
		}
		for (String w : warnings) {
			progress.accept("warning: " + w);
		}
		long bytes = 0;
		try (var s = Files.walk(BodyIds.dir(cacheDir, id))) {
			for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
				bytes += Files.size(p);
			}
		}
		return new ConvertResult(true, String.format(Locale.ROOT,
				"body %s converted into %s: preset %d, Tz %.4f UU, %d meshes, %d raw textures, %.1f MB, %.1f s%s", id,
				BodyIds.dir(cacheDir, id), entry.preset(), body.tz(), meshes.size(), own.textures().size(), bytes / 1e6,
				(System.nanoTime() - start) / 1e9, warnings.isEmpty() ? "" : "; warnings: " + String.join("; ", warnings)),
				Failure.NONE);
	}

	private static @Nullable ConvertResult export(UmodelRunner umodel, String pkg, String object, Path out)
			throws IOException, InterruptedException {
		ToolRunner.ToolResult t;
		try {
			t = umodel.export(pkg, object, out);
		} catch (IllegalArgumentException refused) {
			return fail(Failure.TOOL_MISSING, "umodel not run: " + refused.getMessage());
		}
		if (t.exit() == 0 && !t.timedOut()) {
			return null;
		}
		String said = Converter.lastLine(t.stderrTail().isBlank() ? t.stdoutTail() : t.stderrTail());
		if (Converter.couldNotRun(t)) {
			return fail(Failure.TOOL_MISSING, "umodel could not run (exit " + t.exit() + "): " + said);
		}
		return fail(Failure.ASSETS_CHANGED, "umodel " + (t.timedOut() ? "timed out" : "exit " + t.exit()) + " exporting "
				+ pkg + " " + object + ": " + said);
	}

	private static @Nullable JsonObject fxMeshes(UmodelRunner umodel, FxExtractor.Result fx, Base base, String stem,
			Path export, Map<String, MeshData> meshes, List<String> warnings, Consumer<String> progress)
			throws IOException, InterruptedException {
		JsonObject effects = fx.effects().deepCopy();
		JsonObject systems = effects.has("systems") ? effects.getAsJsonObject("systems") : new JsonObject();
		for (String s : List.copyOf(systems.keySet())) {
			if (base.systems().contains(s)) {
				systems.remove(s);
			}
		}
		JsonObject meshMap = effects.has("meshes") ? effects.getAsJsonObject("meshes") : new JsonObject();
		for (String ref : fx.meshRefs()) {
			if (!meshMap.has(ref)) {
				continue;
			}
			String name = meshMap.get(ref).getAsString();
			if (base.meshes().contains(name)) {
				continue;
			}
			int colon = ref.indexOf(':');
			String pkg = colon > 0 ? ref.substring(0, colon) : stem;
			String object = ref.substring(Math.max(ref.lastIndexOf(':'), ref.lastIndexOf('.')) + 1);
			progress.accept("Exporting " + pkg + ".upk " + object);
			ConvertResult r = export(umodel, pkg + ".upk", object, export);
			Path f = r == null ? Converter.Exports.index(export).meshIn(object, ref.substring(colon + 1).split("\\.")[0])
					: null;
			if (f == null || !name.matches("[a-z0-9_]+")) {
				warnings.add("FX mesh " + ref + " not exported" + (r != null ? " (" + r.message() + ")" : "")
						+ ", so effects.json has no mesh for it");
				meshMap.remove(ref);
				continue;
			}
			try {
				meshes.put(name, MeshAssembler.mesh(PskReader.read(f), x -> true, MeshAssembler.Placement.IDENTITY));
			} catch (IOException e) {
				warnings.add("FX mesh " + ref + " unreadable: " + e.getMessage());
				meshMap.remove(ref);
			}
		}
		return systems.isEmpty() && meshMap.isEmpty() ? null : effects;
	}

	private static @Nullable Materials materials(UmodelRunner umodel, MaterialExtractor.Result read, Base base, Path raw,
			Map<String, Path> files, List<String> problems, List<String> warnings) throws IOException, InterruptedException {
		Map<String, MaterialDef> mats = new LinkedHashMap<>();
		read.materials().forEach((name, d) -> {
			if (!base.materials().contains(name)) {
				mats.put(name, d);
			}
		});
		Map<String, LinkedHashSet<String>> batches = new LinkedHashMap<>();
		List<MaterialExtractor.Texture> wanted = new ArrayList<>();
		for (MaterialExtractor.Texture t : read.textures().values()) {
			if (base.textures().contains(t.name())) {
				continue;
			}
			if (t.info() == null) {
				warnings.add("runtime texture, not exported: " + t.name() + " (" + t.className() + ")");
				continue;
			}
			wanted.add(t);
			batches.computeIfAbsent(t.info().pkg(), k -> new LinkedHashSet<>()).add(lastName(t.path()));
		}
		for (Map.Entry<String, LinkedHashSet<String>> b : batches.entrySet()) {
			if (Converter.exportRaw(umodel, b.getKey() + ".upk", List.copyOf(b.getValue()), raw) != null) {
				return null;
			}
		}
		Converter.Exports rx = Converter.Exports.index(raw);
		Map<String, TextureInfo> infos = new LinkedHashMap<>();
		Map<String, String> stems = new HashMap<>();
		for (MaterialExtractor.Texture t : wanted) {
			Path f = rx.texture(lastName(t.path()), t.path().substring(t.path().indexOf(':') + 1).split("\\.")[0]);
			if (f == null) {
				warnings.add("texture " + t.name() + " (" + t.path() + ") not exported");
				continue;
			}
			String stem = Converter.stem(t.name());
			String other = stems.putIfAbsent(stem, t.name());
			if (other != null) {
				problems.add("textures " + other + " and " + t.name() + " are both textures/" + stem + ".png");
				continue;
			}
			int[] size = Converter.pngSize(f);
			String file = "textures/" + stem + ".png";
			infos.put(t.name(), t.info().exported(file, size[0], size[1]));
			files.put(file, f);
		}
		return new Materials(mats, infos);
	}

	static List<String> requiredTextures(Materials base, Materials own, String shell, String chassis) {
		Map<String, MaterialDef> mats = new LinkedHashMap<>(base.materials());
		own.materials().forEach(mats::putIfAbsent);
		Map<String, TextureInfo> tex = new LinkedHashMap<>(base.textures());
		own.textures().forEach(tex::putIfAbsent);
		Materials all = new Materials(mats, tex);
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : List.of(Map.entry(shell, REQUIRED_SHELL), Map.entry(chassis,
				REQUIRED_CHASSIS))) {
			for (String param : e.getValue()) {
				String t = all.texture(e.getKey(), param);
				if (t == null) {
					problems.add("the look needs " + e.getKey() + "." + param + ", which names no texture");
				} else if (!tex.containsKey(t)) {
					problems.add("the look needs " + e.getKey() + "." + param + " = " + t
							+ ", which is neither in the base nor exported");
				}
			}
		}
		return problems;
	}

	private static JsonObject report(BodySpec spec, Read rd, MeshAssembler.Body body, MeshAssembler.Wheels wheels,
			Materials own, List<String> problems, List<String> warnings) {
		JsonObject r = new JsonObject();
		r.addProperty("mesh", spec.pkg() + " " + spec.mesh());
		r.addProperty("TzUU", body.tz());
		r.addProperty("TzRule", spec.legacyOffsets() ? "CalculatePresetSuspensionOffset (legacy)"
				: "CalculatePresetSuspensionOffset (WheelMeshRadius, + SuspensionTravelMin)");
		r.addProperty("shellSlot", spec.shellMaterial());
		r.addProperty("shellMaterial", rd.shellMaterial());
		r.addProperty("chassisMaterial", spec.chassisMaterial());
		JsonObject wl = new JsonObject();
		Map<String, double[]> preset = MeshAssembler.presetRest(spec);
		for (String k : MeshAssembler.WHEELS) {
			JsonObject w = new JsonObject();
			if (body.discs().containsKey(k)) {
				w.add("discUU", arr(body.discs().get(k)));
			}
			w.add("presetRestUU", arr(preset.get(k)));
			double[] o = body.boneOffsets().get(k);
			if (o != null) {
				w.add("discMinusPresetUU", arr(o));
				w.addProperty("discMinusPresetLengthUU", Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
			}
			wl.add(k, w);
		}
		r.add("wheels", wl);
		JsonObject ax = new JsonObject();
		for (boolean front : new boolean[] {true, false}) {
			JsonObject a = new JsonObject();
			a.add("scale", arr(new double[] {spec.radialScale(front), spec.axialScale(front), spec.radialScale(front)}));
			a.addProperty("meshOffsetSideUU", spec.meshOffsetSide(front));
			a.addProperty("visualRadiusUU", wheels.measuredRadius() * spec.radialScale(front));
			ax.add(front ? "front" : "back", a);
		}
		r.add("axles", ax);
		r.addProperty("materials", own.materials().size());
		r.addProperty("rawTextures", own.textures().size());
		JsonArray p = new JsonArray(), w = new JsonArray();
		problems.forEach(p::add);
		warnings.forEach(w::add);
		r.add("problems", p);
		r.add("warnings", w);
		return r;
	}

	private static JsonArray arr(double[] v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}

	private static Path json(Path file, JsonObject o) throws IOException {
		Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
				.toJson(o) + "\n", StandardCharsets.UTF_8);
		return file;
	}

	private static BodyIndex.@Nullable Entry entry(Path cacheDir, String id) {
		try {
			BodyIndex index = BodyIndex.fromJson(JsonParser.parseString(Files.readString(
					cacheDir.resolve("bodies").resolve("index.json"), StandardCharsets.UTF_8)));
			return index.bodies().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static ConvertResult fail(Failure f, String message) {
		return new ConvertResult(false, message, f);
	}

	private static String lastName(String path) {
		return path.substring(Math.max(path.lastIndexOf(':'), path.lastIndexOf('.')) + 1);
	}

	record Base(Set<String> materials, Set<String> textures, Set<String> meshes, Set<String> systems, Materials all,
			@Nullable String fingerprint) {
		static Base read(Path cacheDir) throws IOException {
			JsonObject m = object(cacheDir.resolve("manifest.json"));
			Set<String> meshes = new LinkedHashSet<>();
			for (String k : new String[] {"meshes", "extras"}) {
				if (m.has(k) && m.get(k).isJsonObject()) {
					meshes.addAll(m.getAsJsonObject(k).keySet());
				}
			}
			Materials mats = m.has("materials")
					? CacheJson.materials(object(cacheDir.resolve(m.get("materials").getAsString())))
					: Materials.EMPTY;
			Set<String> systems = new LinkedHashSet<>();
			if (m.has("effects")) {
				try {
					JsonObject e = object(cacheDir.resolve(m.get("effects").getAsString()));
					if (e.has("systems")) {
						systems.addAll(e.getAsJsonObject("systems").keySet());
					}
				} catch (NoSuchFileException noBaseFx) {
				}
			}
			return new Base(mats.materials().keySet(), mats.textures().keySet(), meshes, systems, mats,
					CacheWriter.fingerprintOf(cacheDir));
		}

		private static JsonObject object(Path f) throws IOException {
			JsonElement e = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8));
			if (!e.isJsonObject()) {
				throw new IOException(f + " is not a JSON object");
			}
			return e.getAsJsonObject();
		}
	}

	private static Read readUpk(Path unpacked, BodyIndex.Entry entry, List<String> warnings) throws IOException {
		UpkWorld w = new UpkWorld(unpacked);
		String stem = BodyScanner.stem(entry.pkg());
		UpkReader p = w.pkg(stem);
		if (p == null) {
			throw new IOException(entry.pkg() + " did not unpack");
		}
		int pi = BodyScanner.product(p, entry.id());
		if (pi <= 0) {
			throw new IOException("no " + BodyScanner.PRODUCT_CLASS);
		}
		UObject product = w.obj(stem, pi);
		if (!(product.props().get("HandlingPreset") instanceof UpkRef hr)) {
			throw new IOException("the product has no HandlingPreset");
		}
		UpkWorld.Loc hl = w.locate(hr.path(), stem);
		if (hl == null) {
			throw new IOException("its handling preset " + hr.path() + " is not in the unpacked packages");
		}
		List<List<String>> chains = BodyScanner.slotChains(w, product, stem);
		String shell = slot(chains, List.of(BodyScanner.BODY_ROOT)), chassis = slot(chains, BodyScanner.CHASSIS_ROOTS);
		BodySpec spec = BodySpec.of(entry.pkg(), product, w.obj(hl.pkg(), hl.index()), entry.preset(), shell, chassis);
		FxExtractor.Result fx = null;
		if (spec.fxActor() != null) {
			fx = FxExtractor.extract(w, Map.of("body", spec.fxActor()), Map.of(), warnings);
			if (!fx.fxActors().getAsJsonObject("actors").has("body")) {
				fx = null;
			}
		}
		MaterialExtractor.Named named = new MaterialExtractor.Named(shell, List.of(stem));
		List<MaterialExtractor.Overlay> overlays = new ArrayList<>();
		String drawn = shell;
		Map<String, Object> skin = skinParameters(product.props().get("SkinParameters"));
		if (skin != null) {
			drawn = entry.id() + "_Skin";
			overlays.add(new MaterialExtractor.Overlay(drawn, stem + ":" + product.path() + ".SkinParameters", named,
					skin));
		}
		MaterialExtractor.Result mats = MaterialExtractor.extract(w, List.of(named,
				new MaterialExtractor.Named(chassis, List.of(stem))), fx != null ? fx.materialRefs() : List.of(), overlays,
				warnings);
		return new Read(spec, drawn, mats, fx);
	}

	private static String slot(List<List<String>> chains, List<String> roots) throws IOException {
		for (List<String> c : chains) {
			if (!c.isEmpty() && roots.contains(c.getLast())) {
				return c.getFirst();
			}
		}
		throw new IOException("no material slot rooted at " + String.join(" or ", roots));
	}

	static @Nullable Map<String, Object> skinParameters(@Nullable Object value) {
		if (!(value instanceof Map<?, ?> m) || !(m.get("TextureParameters") instanceof List<?> list)) {
			return null;
		}
		List<Map<String, Object>> tex = new ArrayList<>();
		for (Object x : list) {
			if (x instanceof Map<?, ?> e && e.get("Name") instanceof String n && SKIN_PARAMETERS.contains(n)
					&& e.get("Value") instanceof UpkRef) {
				tex.add(Map.of("Name", n, "Value", e.get("Value")));
			}
		}
		return tex.isEmpty() ? null : Map.<String, Object>of("TextureParameters", tex);
	}
}
