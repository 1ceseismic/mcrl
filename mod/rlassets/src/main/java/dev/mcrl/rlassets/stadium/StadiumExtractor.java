package dev.mcrl.rlassets.stadium;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.MeshAssembler;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.PskMesh;
import dev.mcrl.rlassets.PskReader;
import dev.mcrl.rlassets.TextureBaker;
import dev.mcrl.rlassets.ToolRunner;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.arena.ArenaExtractor;
import dev.mcrl.rlassets.extract.MaterialExtractor;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.TextureInfo;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkWorld;

public final class StadiumExtractor {
	public static final String PKG = "Stadium_P";
	public static final List<String> FAR_PREFIXES = List.of("City_", "SkyScrapers_", "SkyScraper_", "CityGround_", "OOB",
			"LeafyTrees", "Monorail", "Crowd", "Statue_");
	public static final double PAD_MATCH = 10;
	static final Set<String> COLLISION_GROUPS = Set.of(ArenaExtractor.COLLISION_GROUP, "CollisionMeshes");
	public static final double[] ORIGIN = {0, 64, 0};
	static final float[][] PADS = {
			{-3584, 0, 73}, {3584, 0, 73}, {-3072, 4096, 73}, {3072, 4096, 73}, {-3072, -4096, 73}, {3072, -4096, 73},
			{0, -4240, 70}, {-1792, -4184, 70}, {1792, -4184, 70}, {-940, -3308, 70}, {940, -3308, 70}, {0, -2816, 70},
			{-3584, -2484, 70}, {3584, -2484, 70}, {-1788, -2300, 70}, {1788, -2300, 70}, {-2048, -1036, 70},
			{0, -1024, 70}, {2048, -1036, 70}, {-1024, 0, 70}, {1024, 0, 70}, {-2048, 1036, 70}, {0, 1024, 70},
			{2048, 1036, 70}, {-1788, 2300, 70}, {1788, 2300, 70}, {-3584, 2484, 70}, {3584, 2484, 70}, {0, 2816, 70},
			{-940, 3308, 70}, {940, 3308, 70}, {-1792, 4184, 70}, {1792, 4184, 70}, {0, 4240, 70}};
	public static final int PAD_COUNT = 34;
	static final String PAINTED_LINE = "PaintedLine_Mat";
	static final List<String> TINTS = List.of("Color", "Color1", "BaseColor", "DiffuseColor", "Tint", "FloorColor");
	static final List<String> COLOUR_TEXTURES = List.of("Diffuse", "DiffuseTexture", "BaseColor", "Albedo", "FieldSkin",
			"AdTexture", "Texture");
	static final List<String> NOT_COLOUR = List.of("normal", "_n", "_nm", "_m", "mask", "lookup", "lightmap", "cube",
			"noise", "falloff", "env", "spec", "rough", "height", "distort", "pack", "curve");

	private StadiumExtractor() {
	}

	public enum Tier { NEAR, FAR }

	public enum Pass { OPAQUE, MASKED, GLASS, GLOW }

	public record Instance(String mesh, String material, float[] m, Tier tier, Pass pass, int team, int pad) {
	}

	public record Look(Pass pass, float[] color, String source, @Nullable String texture, float[] tint,
			float @Nullable [] mask) {
		public Look(Pass pass, float[] color, String source) {
			this(pass, color, source, null, new float[] {1, 1, 1}, null);
		}
	}

	public record TextureFile(Path png, TextureInfo info) {
	}

	public record Light(float[] dir, float[] color, float brightness) {
	}

	public record Fog(float[] color, float start, float end, float maxOpacity) {
	}

	public record StadiumData(Map<String, MeshData> meshes, List<Instance> instances, Map<String, Look> looks,
			Map<String, TextureFile> textures, List<Light> keys, float[] sky, @Nullable Fog fog, int padsMatched,
			int skipped, List<String> problems, List<String> warnings) {
		public long triangles(Tier tier) {
			long n = 0;
			for (Instance i : instances) {
				MeshData m = meshes.get(i.mesh());
				n += i.tier() == tier && m != null ? m.triangleCount() : 0;
			}
			return n;
		}
	}

	private record Placed(String path, String meshPath, List<@Nullable String> materials, double[] ue, int pad) {
	}

	public static StadiumData extract(UmodelRunner umodel, UpkWorld world, Path work) throws IOException,
			InterruptedException {
		UpkReader p = world.pkg(PKG);
		if (p == null) {
			throw new IOException("package " + PKG + " was not unpacked");
		}
		List<String> problems = new ArrayList<>(), warnings = new ArrayList<>();
		int level = levelExport(p);
		if (level == 0) {
			problems.add(PKG + " has no PersistentLevel");
			return new StadiumData(Map.of(), List.of(), Map.of(), Map.of(), List.of(), new float[3], null, 0, 0, problems,
					warnings);
		}
		String levelPath = p.objectPath(level) + ".";

		Map<String, Integer> padOfActor = new HashMap<>();
		boolean[] matched = new boolean[PAD_COUNT];
		for (int i = 1; i <= p.exports().size(); i++) {
			if (!classOf(p, i).equals("VehiclePickup_Boost_TA") || !p.objectPath(i).startsWith(levelPath)) {
				continue;
			}
			UObject o = world.obj(PKG, i);
			double[] at = vector(o.props().get("Location"), 0);
			int pad = matchPad(at);
			if (pad < 0) {
				problems.add(String.format(Locale.ROOT, "%s at (%.1f, %.1f, %.1f) UU matches no RocketSim pad", o.path(),
						at[0], at[1], at[2]));
				continue;
			}
			if (matched[pad]) {
				problems.add(o.path() + " matches pad " + pad + ", which another pickup matched");
			}
			matched[pad] = true;
			if (o.props().get("FXActor") instanceof UpkRef fx) {
				padOfActor.put(fx.path(), pad);
			}
		}
		int padsMatched = 0;
		for (int k = 0; k < PAD_COUNT; k++) {
			if (matched[k]) {
				padsMatched++;
			} else {
				problems.add("RocketSim pad " + k + " has no pickup in " + PKG);
			}
		}

		List<Placed> placed = new ArrayList<>();
		for (int i = 1; i <= p.exports().size(); i++) {
			String path = p.objectPath(i);
			if (!path.startsWith(levelPath) || !classOf(p, i).equals("StaticMeshComponent")) {
				continue;
			}
			UObject c = world.obj(PKG, i);
			Map<String, Object> cp = c.props();
			if (Boolean.TRUE.equals(cp.get("HiddenGame")) || !(cp.get("StaticMesh") instanceof UpkRef mesh)
					|| COLLISION_GROUPS.contains(ArenaExtractor.group(mesh.path()))) {
				continue;
			}
			int outer = p.exports().get(i - 1).outer();
			if (outer <= 0) {
				warnings.add(c.path() + " has no owner in " + PKG + "; skipped");
				continue;
			}
			UObject owner = world.obj(PKG, outer);
			if (Boolean.TRUE.equals(owner.props().get("bHidden"))) {
				continue;
			}
			List<@Nullable String> mats = new ArrayList<>();
			if (cp.get("Materials") instanceof List<?> l) {
				for (Object m : l) {
					mats.add(m instanceof UpkRef r ? r.path() : null);
				}
			}
			placed.add(new Placed(c.path(), mesh.path(), mats, matrix(owner.props(), cp),
					padOfActor.getOrDefault(owner.path(), -1)));
		}

		Map<String, String> objectOf = new LinkedHashMap<>();
		Map<String, Set<String>> meshesByPkg = new LinkedHashMap<>();
		for (Placed pl : placed) {
			objectOf.putIfAbsent(pl.meshPath(), lastName(pl.meshPath()));
			meshesByPkg.computeIfAbsent(packageOf(pl.meshPath()), k -> new LinkedHashSet<>())
					.add(lastName(pl.meshPath()));
		}
		Path export = work.resolve("stadium-export");
		for (Map.Entry<String, Set<String>> e : meshesByPkg.entrySet()) {
			exportObjects(umodel, e.getKey() + ".upk", List.copyOf(e.getValue()), export);
		}
		Map<String, List<Path>> files = index(export);
		Map<String, PskMesh> psk = new HashMap<>();
		for (Map.Entry<String, String> e : objectOf.entrySet()) {
			Path f = pick(files.get(e.getValue() + ".pskx"), topGroup(e.getKey()));
			if (f == null) {
				warnings.add("mesh " + e.getKey() + " not exported; its instances are left out");
				continue;
			}
			try {
				psk.put(e.getKey(), PskReader.read(f));
			} catch (IOException ex) {
				warnings.add("mesh " + e.getKey() + " unreadable (" + ex.getMessage() + "); its instances are left out");
			}
		}

		Set<String> refs = new LinkedHashSet<>();
		Map<String, MaterialExtractor.Named> named = new LinkedHashMap<>();
		for (Placed pl : placed) {
			PskMesh m = psk.get(pl.meshPath());
			for (int s = 0; m != null && s < m.materials().size(); s++) {
				String over = s < pl.materials().size() ? pl.materials().get(s) : null;
				if (over != null) {
					refs.add(over);
				} else if (!m.materials().get(s).matches("material_\\d+")) {
					String name = m.materials().get(s);
					named.putIfAbsent(name, new MaterialExtractor.Named(name, List.of(PKG)));
				}
			}
		}
		MaterialExtractor.Result mats = MaterialExtractor.extract(world, List.copyOf(named.values()), refs, warnings);
		Map<String, MaterialDef> byPath = new HashMap<>();
		mats.materials().values().forEach(d -> byPath.put(d.path(), d));

		Map<String, MaterialDef> roots = new LinkedHashMap<>();
		for (String r : refs) {
			UpkWorld.Loc l = world.locate(r, null);
			MaterialDef d = l == null ? null : byPath.get(l.pkg() + ":" + world.pkg(l.pkg()).objectPath(l.index()));
			if (d != null) {
				roots.put(r, d);
			}
		}
		for (MaterialExtractor.Named n : named.values()) {
			MaterialDef d = mats.materials().get(n.name());
			if (d != null) {
				roots.put(n.name(), d);
			}
		}
		Map<String, @Nullable String> colourTexture = new HashMap<>();
		Map<String, List<String>> texturesByPkg = new LinkedHashMap<>();
		for (MaterialDef d : roots.values()) {
			String t = colourTexture(d, mats);
			colourTexture.put(d.name(), t);
			MaterialExtractor.Texture tx = t == null ? null : mats.textures().get(t);
			if (tx != null && tx.info() != null) {
				texturesByPkg.computeIfAbsent(tx.info().pkg(), k -> new ArrayList<>()).add(lastName(tx.path()));
			}
		}
		Path raw = work.resolve("stadium-textures");
		for (Map.Entry<String, List<String>> e : texturesByPkg.entrySet()) {
			exportObjects(umodel, e.getKey() + ".upk", List.copyOf(new LinkedHashSet<>(e.getValue())), raw);
		}
		Map<String, List<Path>> rawFiles = index(raw);
		Map<String, @Nullable Exported> averages = new HashMap<>();
		Map<String, Look> looks = new LinkedHashMap<>();
		for (Map.Entry<String, MaterialDef> e : roots.entrySet()) {
			MaterialDef d = e.getValue();
			looks.put(e.getKey(), look(d, mats, colourTexture.get(d.name()), averages, rawFiles, warnings));
		}

		Map<String, MeshData> meshes = new LinkedHashMap<>();
		List<Instance> instances = new ArrayList<>();
		int skipped = 0;
		Map<String, List<String>> skippedMaterials = new TreeMap<>();
		Set<String> nearBallMaterials = new java.util.TreeSet<>();
		for (Placed pl : placed) {
			PskMesh m = psk.get(pl.meshPath());
			if (m == null) {
				continue;
			}
			String meshName = lastName(pl.meshPath());
			float[] world4 = toWorld(pl.ue());
			for (int s = 0; s < m.materials().size(); s++) {
				int slot = s;
				String key = key(meshName) + "_" + s;
				String over = s < pl.materials().size() ? pl.materials().get(s) : null;
				String material = over != null ? over : m.materials().get(s);
				Look look = looks.get(material);
				MaterialDef def = roots.get(material);
				if (look != null && (look.pass() == Pass.GLASS || look.pass() == Pass.GLOW) && def != null
						&& nearBall(def, mats)) {
					skipped++;
					nearBallMaterials.add(material);
					continue;
				}
				if (look != null && look.source().startsWith("default") && (look.pass() == Pass.GLASS
						|| look.pass() == Pass.GLOW)) {
					skipped++;
					MaterialDef d = roots.get(material);
					skippedMaterials.computeIfAbsent(material, k -> d == null ? List.of() : candidates(d, mats));
					continue;
				}
				if (!meshes.containsKey(key)) {
					MeshData md = MeshAssembler.mesh(m, f -> m.faceMat()[f] == slot, MeshAssembler.Placement.IDENTITY);
					if (md.triangleCount() == 0) {
						continue;
					}
					meshes.put(key, md);
				}
				if (look == null) {
					warnings.add(pl.path() + " slot " + s + ": material " + material + " not read; drawn plain");
					look = new Look(Pass.OPAQUE, new float[] {0.22f, 0.23f, 0.25f, 1}, "none");
					looks.put(material, look);
				}
				instances.add(new Instance(key, material, world4, tier(meshName), look.pass(),
						team(meshName, material, look), pl.pad()));
			}
		}
		for (String floor : List.of("Field_STD_Floor_Team1", "Field_STD_Floor_Team2")) {
			if (instances.stream().noneMatch(i -> i.mesh().startsWith(key(floor) + "_"))) {
				problems.add("no instance of " + floor);
			}
		}

		List<Light> keys = keyLights(world, p, levelPath, warnings);
		float[] sky = skyLight(world, p, levelPath);
		Fog fog = fog(world, p, levelPath);
		Map<String, TextureFile> textures = new TreeMap<>();
		for (Instance in : instances) {
			Look l = looks.get(in.material());
			Exported x = l == null || l.texture() == null ? null : averages.get(l.texture());
			MaterialExtractor.Texture t = x == null ? null : mats.textures().get(l.texture());
			if (x != null && t != null && t.info() != null) {
				textures.put(l.texture(), new TextureFile(x.png(), t.info().exported(x.png().getFileName().toString(),
						x.width(), x.height())));
			}
		}
		skippedMaterials.forEach((m, c) -> warnings.add("left out (no colour of its own): " + m + "; its textures: "
				+ (c.isEmpty() ? "none" : String.join(", ", c))));
		nearBallMaterials.forEach(m -> warnings.add("left out (shows only near the ball, BallPosition): " + m));
		return new StadiumData(meshes, List.copyOf(instances), looks, textures, keys, sky, fog, padsMatched, skipped,
				List.copyOf(problems), List.copyOf(warnings));
	}

	public static double[] matrix(Map<String, Object> owner, Map<String, Object> comp) {
		double[] a = trs(vector(owner.get("Location"), 0), rotator(owner.get("Rotation")),
				scale(number(owner.get("DrawScale"), 1).doubleValue(), vector(owner.get("DrawScale3D"), 1)));
		double[] c = trs(vector(comp.get("Translation"), 0), rotator(comp.get("Rotation")),
				scale(number(comp.get("Scale"), 1).doubleValue(), vector(comp.get("Scale3D"), 1)));
		return mul(a, c);
	}

	public static double[] toMc(double[] ue) {
		int[] perm = {0, 2, 1};
		double[] out = new double[12];
		for (int r = 0; r < 3; r++) {
			for (int c = 0; c < 3; c++) {
				out[4 * r + c] = ue[4 * perm[r] + perm[c]];
			}
			out[4 * r + 3] = ue[4 * perm[r] + 3] / 50;
		}
		return out;
	}

	static float[] toWorld(double[] ue) {
		double[] mc = toMc(ue);
		float[] out = new float[12];
		for (int i = 0; i < 12; i++) {
			out[i] = (float) (mc[i] + (i % 4 == 3 ? ORIGIN[i / 4] : 0));
		}
		return out;
	}

	private static double[] scale(double s, double[] s3) {
		return new double[] {s * s3[0], s * s3[1], s * s3[2]};
	}

	private static double[] trs(double[] t, int[] rot, double[] s) {
		double[] r = ArenaExtractor.rotation(rot[0], rot[1], rot[2]);
		double[] out = new double[12];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				out[4 * i + j] = r[3 * i + j] * s[j];
			}
			out[4 * i + 3] = t[i];
		}
		return out;
	}

	static double[] mul(double[] a, double[] b) {
		double[] out = new double[12];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 4; j++) {
				double v = j == 3 ? a[4 * i + 3] : 0;
				for (int k = 0; k < 3; k++) {
					v += a[4 * i + k] * b[4 * k + j];
				}
				out[4 * i + j] = v;
			}
		}
		return out;
	}

	static int matchPad(double[] at) {
		for (int k = 0; k < PAD_COUNT; k++) {
			double dx = at[0] - PADS[k][0], dy = at[1] - PADS[k][1], dz = at[2] - PADS[k][2];
			if (dx * dx + dy * dy + dz * dz <= PAD_MATCH * PAD_MATCH) {
				return k;
			}
		}
		return -1;
	}

	static int team(String mesh, String material, Look look) {
		int t = teamOf(mesh);
		boolean textured = look.source().startsWith("texture");
		return t >= 0 || (textured && (look.pass() == Pass.OPAQUE || look.pass() == Pass.MASKED)) ? t
				: teamOf(lastName(material));
	}

	private static int teamOf(String name) {
		String l = name.toLowerCase(Locale.ROOT);
		return l.contains("team1") ? 0 : l.contains("team2") ? 1 : -1;
	}

	public static Tier tier(String mesh) {
		return FAR_PREFIXES.stream().anyMatch(mesh::startsWith) ? Tier.FAR : Tier.NEAR;
	}

	static Pass pass(@Nullable String blendMode) {
		if (blendMode == null) {
			return Pass.OPAQUE;
		}
		return switch (blendMode) {
			case "BLEND_Masked", "BLEND_SoftMasked", "BLEND_DitheredTranslucent" -> Pass.MASKED;
			case "BLEND_Translucent", "BLEND_AlphaComposite", "BLEND_Modulate", "BLEND_ModulateAndAdd" -> Pass.GLASS;
			case "BLEND_Additive" -> Pass.GLOW;
			default -> Pass.OPAQUE;
		};
	}

	static List<MaterialDef> chain(MaterialDef d, MaterialExtractor.Result mats) {
		List<MaterialDef> out = new ArrayList<>();
		for (MaterialDef c = d; c != null && out.size() < 16;
				c = c.parent() == null ? null : mats.materials().get(c.parent())) {
			out.add(c);
		}
		return out;
	}

	static @Nullable String colourTexture(MaterialDef d, MaterialExtractor.Result mats) {
		List<MaterialDef> chain = chain(d, mats);
		if (chain.getLast().name().equals(PAINTED_LINE)) {
			for (MaterialDef c : chain) {
				if (!c.shaderTextures().isEmpty()) {
					return c.shaderTextures().getFirst();
				}
			}
		}
		for (String k : COLOUR_TEXTURES) {
			for (MaterialDef c : chain) {
				String t = c.textures().get(k);
				if (t == null && c.defaults() != null) {
					t = c.defaults().textures().get(k);
				}
				if (t != null && isColour(mats.textures().get(t))) {
					return t;
				}
			}
		}
		for (MaterialDef c : chain) {
			for (String t : c.shaderTextures()) {
				if (isColour(mats.textures().get(t))) {
					return t;
				}
			}
		}
		return null;
	}

	static boolean nearBall(MaterialDef d, MaterialExtractor.Result mats) {
		for (MaterialDef c : chain(d, mats)) {
			if (c.vectors().containsKey("BallPosition") || c.defaults() != null
					&& c.defaults().vectors().containsKey("BallPosition")) {
				return true;
			}
		}
		return false;
	}

	static List<String> candidates(MaterialDef d, MaterialExtractor.Result mats) {
		Set<String> names = new LinkedHashSet<>();
		for (MaterialDef c : chain(d, mats)) {
			names.addAll(c.textures().values());
			if (c.defaults() != null) {
				names.addAll(c.defaults().textures().values());
			}
			names.addAll(c.shaderTextures());
		}
		List<String> out = new ArrayList<>();
		for (String n : names) {
			MaterialExtractor.Texture t = mats.textures().get(n);
			TextureInfo i = t == null ? null : t.info();
			out.add(i == null ? n + " (no info)" : n + " (" + i.lodGroup() + ", " + (i.srgb() ? "srgb" : "linear") + ", "
					+ (isColour(t) ? "colour" : "not colour") + ")");
		}
		return out;
	}

	static boolean isColour(MaterialExtractor.@Nullable Texture t) {
		if (t == null || t.info() == null || !t.info().srgb() || t.info().compression().startsWith("TC_Normalmap")
				|| t.info().lodGroup().contains("Normal") || t.info().lodGroup().contains("ColorLookup")) {
			return false;
		}
		String n = t.name().toLowerCase(Locale.ROOT);
		return NOT_COLOUR.stream().noneMatch(x -> x.startsWith("_") ? n.endsWith(x) || n.contains(x + "_") : n.contains(x));
	}

	static Look look(MaterialDef d, MaterialExtractor.Result mats, @Nullable String texture,
			Map<String, @Nullable Exported> averages, Map<String, List<Path>> rawFiles, List<String> warnings) {
		List<MaterialDef> chain = chain(d, mats);
		MaterialDef base = chain.getLast();
		Pass pass = pass(base.blendMode());
		float[] tint = null;
		String tintName = null;
		for (String k : TINTS) {
			for (MaterialDef c : chain) {
				float[] v = c.vectors().get(k);
				if (v == null && c.defaults() != null) {
					v = c.defaults().vectors().get(k);
				}
				if (v != null && tint == null) {
					tint = v;
					tintName = k;
				}
			}
		}
		float[] colour = null;
		String source = null;
		float[] mask = null;
		boolean textured = false;
		if (texture != null && base.name().equals(PAINTED_LINE)) {
			Exported x = averages.computeIfAbsent(texture, t -> average(mats.textures().get(t), rawFiles, warnings));
			if (x != null) {
				float r = x.stats().mean()[0];
				return new Look(pass, new float[] {r, r, r, 1}, "painted line: white x R of " + texture + " ("
						+ base.name() + ", " + base.blendMode() + ")", texture, new float[] {1, 1, 1}, new float[] {1, 0, 0});
			}
		}
		if (texture != null) {
			Exported x = averages.computeIfAbsent(texture, t -> average(mats.textures().get(t), rawFiles, warnings));
			TexelStats st = x == null ? null : x.stats();
			if (st != null && st.packed() != null) {
				float g = st.intensity();
				colour = new float[] {g, g, g, 1};
				source = "mask " + texture + " (" + st.packed() + ")";
				mask = st.weights();
				textured = true;
			} else if (st != null) {
				colour = st.mean().clone();
				source = "texture " + texture;
				textured = true;
			}
		}
		if (colour != null) {
			if (tint != null) {
				for (int i = 0; i < 3; i++) {
					colour[i] *= tint[i];
				}
				source += " x " + tintName;
			}
		} else if (tint != null) {
			colour = new float[] {tint[0], tint[1], tint[2], 1};
			source = "vector " + tintName;
		} else {
			colour = switch (pass) {
				case GLASS -> new float[] {0.55f, 0.65f, 0.75f, 1};
				case GLOW -> new float[] {1.2f, 1.15f, 1.05f, 1};
				default -> new float[] {0.22f, 0.23f, 0.25f, 1};
			};
			source = "default";
		}
		colour[3] = pass == Pass.GLASS ? 0.35f : 1f;
		String from = source + " (" + base.name() + ", " + base.blendMode() + ")";
		return textured ? new Look(pass, colour, from, texture, tint == null ? new float[] {1, 1, 1}
				: new float[] {tint[0], tint[1], tint[2]}, mask) : new Look(pass, colour, from);
	}

	record Exported(Path png, int width, int height, TexelStats stats) {
	}

	public record TexelStats(float[] mean, @Nullable String packed, float intensity, float[] weights) {
	}

	public static TexelStats stats(int[] argb) {
		double[] sum = new double[3];
		int[] lo = {255, 255, 255}, hi = new int[3];
		int step = Math.max(1, argb.length / 65536), n = 0;
		for (int i = 0; i < argb.length; i += step) {
			for (int c = 0; c < 3; c++) {
				int v = (argb[i] >> (16 - 8 * c)) & 0xFF;
				sum[c] += srgbToLinear(v / 255.0);
				lo[c] = Math.min(lo[c], v);
				hi[c] = Math.max(hi[c], v);
			}
			n++;
		}
		float[] mean = {(float) (sum[0] / n), (float) (sum[1] / n), (float) (sum[2] / n), 1};
		int varies = 0;
		for (int c = 0; c < 3; c++) {
			varies = Math.max(varies, hi[c] - lo[c]);
		}
		String packed = null;
		double intensity = 0;
		int k = 0;
		boolean[] vary = new boolean[3];
		for (int c = 0; c < 3; c++) {
			boolean constant = hi[c] - lo[c] <= 12;
			if (constant && lo[c] >= 8 && hi[c] <= 247 && varies >= 128 && packed == null) {
				packed = "RGB".charAt(c) + " is " + lo[c] + " to " + hi[c] + " on every texel";
			}
			if (!constant) {
				intensity += mean[c];
				vary[c] = true;
				k++;
			}
		}
		float[] weights = new float[3];
		for (int c = 0; c < 3; c++) {
			weights[c] = vary[c] ? 1f / k : 0;
		}
		return new TexelStats(mean, packed, k == 0 ? 0 : (float) (intensity / k), weights);
	}

	private static @Nullable Exported average(MaterialExtractor.@Nullable Texture t, Map<String, List<Path>> rawFiles,
			List<String> warnings) {
		if (t == null) {
			return null;
		}
		Path f = pick(rawFiles.get(lastName(t.path()) + ".png"), topGroup(t.path()));
		if (f == null) {
			warnings.add("texture " + t.name() + " not exported; its materials take their tint or a grey");
			return null;
		}
		try {
			TextureBaker.Texels x = TextureBaker.Texels.read(f);
			return new Exported(f, x.width(), x.height(), stats(x.argb()));
		} catch (IOException | RuntimeException e) {
			warnings.add("texture " + t.name() + " unreadable (" + e.getMessage() + ")");
			return null;
		}
	}

	static double srgbToLinear(double c) {
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	static List<Light> keyLights(UpkWorld world, UpkReader p, String levelPath, List<String> warnings) throws IOException {
		List<Light> out = new ArrayList<>();
		for (int i = 1; i <= p.exports().size(); i++) {
			if (!classOf(p, i).equals("StaticLightCollectionActor") || !p.objectPath(i).startsWith(levelPath)) {
				continue;
			}
			UObject o = world.obj(PKG, i);
			if (!(o.props().get("LightComponents") instanceof List<?> lights)) {
				continue;
			}
			var body = p.body(i);
			if (o.tagEnd() + 64L * lights.size() > body.limit()) {
				warnings.add(o.path() + ": its light matrices do not fit its body; its lights are skipped");
				continue;
			}
			for (int k = 0; k < lights.size(); k++) {
				if (!(lights.get(k) instanceof UpkRef r)) {
					continue;
				}
				UpkWorld.Loc l = world.locate(r.path(), PKG);
				UObject light = l == null ? null : world.obj(l.pkg(), l.index());
				if (light == null || !light.classPath().endsWith(".DirectionalLightComponent")
						|| Boolean.FALSE.equals(light.props().get("bEnabled")) || !worldChannels(light.props())) {
					continue;
				}
				int at = o.tagEnd() + 64 * k;
				double[] x = {body.getFloat(at), body.getFloat(at + 4), body.getFloat(at + 8)};
				double len = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
				if (len == 0) {
					continue;
				}
				float[] dir = {(float) (x[0] / len), (float) (x[2] / len), (float) (x[1] / len)};
				out.add(new Light(dir, colour(light.props().get("LightColor")), number(light.props().get("Brightness"), 1)
						.floatValue()));
			}
		}
		return out;
	}

	private static boolean worldChannels(Map<String, Object> light) {
		if (!(light.get("LightingChannels") instanceof Map<?, ?> ch)) {
			return true;
		}
		return Boolean.TRUE.equals(ch.get("Static")) || Boolean.TRUE.equals(ch.get("Dynamic"))
				|| Boolean.TRUE.equals(ch.get("CompositeDynamic"));
	}

	static float[] skyLight(UpkWorld world, UpkReader p, String levelPath) {
		for (int i = 1; i <= p.exports().size(); i++) {
			if (classOf(p, i).equals("SkyLightComponent") && p.objectPath(i).startsWith(levelPath)) {
				Map<String, Object> s = world.obj(PKG, i).props();
				float[] up = colour(s.get("LightColor")), down = colour(s.get("LowerColor"));
				float b = number(s.get("Brightness"), 1).floatValue();
				float lb = number(s.get("LowerBrightness"), 0).floatValue();
				return new float[] {(up[0] * b + down[0] * lb) / 2, (up[1] * b + down[1] * lb) / 2,
						(up[2] * b + down[2] * lb) / 2};
			}
		}
		return new float[3];
	}

	static @Nullable Fog fog(UpkWorld world, UpkReader p, String levelPath) {
		for (int i = 1; i <= p.exports().size(); i++) {
			if (classOf(p, i).equals("ExponentialHeightFogComponent") && p.objectPath(i).startsWith(levelPath)) {
				Map<String, Object> f = world.obj(PKG, i).props();
				if (Boolean.FALSE.equals(f.get("bEnabled"))) {
					return null;
				}
				float[] in = colour(f.get("LightInscatteringColor")), op = colour(f.get("OppositeLightColor"));
				float ib = number(f.get("LightInscatteringBrightness"), 1).floatValue();
				float ob = number(f.get("OppositeLightBrightness"), 1).floatValue();
				float start = number(f.get("StartDistance"), 0).floatValue() / 50;
				float density = Math.max(number(f.get("FogDensity"), 0.02).floatValue(), 1e-4f);
				float end = start + 3 / density * 1000 / 50;
				return new Fog(new float[] {(in[0] * ib + op[0] * ob) / 2, (in[1] * ib + op[1] * ob) / 2,
						(in[2] * ib + op[2] * ob) / 2}, start, end, number(f.get("FogMaxOpacity"), 1).floatValue());
			}
		}
		return null;
	}

	static float[] colour(@Nullable Object v) {
		if (!(v instanceof Map<?, ?> m)) {
			return new float[] {1, 1, 1};
		}
		float[] out = new float[3];
		String[] keys = {"R", "G", "B"};
		for (int i = 0; i < 3; i++) {
			out[i] = (float) srgbToLinear(number(m.get(keys[i]), 255).doubleValue() / 255);
		}
		return out;
	}

	private static void exportObjects(UmodelRunner umodel, String pkg, List<String> objects, Path out)
			throws IOException, InterruptedException {
		if (objects.isEmpty()) {
			return;
		}
		ToolRunner.ToolResult t = umodel.exportObjects(pkg, objects, out);
		if ((t.exit() != 0 || t.timedOut()) && objects.size() > 1) {
			for (String o : objects) {
				umodel.exportObjects(pkg, List.of(o), out);
			}
		}
	}

	static Map<String, List<Path>> index(Path root) throws IOException {
		Map<String, List<Path>> m = new HashMap<>();
		if (Files.isDirectory(root)) {
			try (Stream<Path> s = Files.walk(root)) {
				s.filter(Files::isRegularFile).sorted(Comparator.naturalOrder())
						.forEach(f -> m.computeIfAbsent(f.getFileName().toString(), k -> new ArrayList<>()).add(f));
			}
		}
		return m;
	}

	private static @Nullable Path pick(@Nullable List<Path> files, String group) {
		if (files == null || files.isEmpty()) {
			return null;
		}
		for (Path f : files) {
			for (Path part : f) {
				if (part.toString().equals(group)) {
					return f;
				}
			}
		}
		return files.getFirst();
	}

	static String packageOf(String path) {
		int colon = path.indexOf(':');
		return colon < 0 ? PKG : path.substring(0, colon);
	}

	private static String topGroup(String path) {
		String s = path.substring(path.indexOf(':') + 1);
		int dot = s.indexOf('.');
		return dot < 0 ? "" : s.substring(0, dot);
	}

	static String lastName(String path) {
		return path.substring(Math.max(path.lastIndexOf(':'), path.lastIndexOf('.')) + 1);
	}

	static String key(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
	}

	static String classOf(UpkReader p, int i) {
		return p.className(p.exports().get(i - 1).classIndex());
	}

	static int levelExport(UpkReader p) {
		for (int i = 1; i <= p.exports().size(); i++) {
			if (classOf(p, i).equals("Level") && p.exports().get(i - 1).objectName().equals("PersistentLevel")) {
				return i;
			}
		}
		return 0;
	}

	private static double[] vector(@Nullable Object v, double dflt) {
		if (v instanceof Map<?, ?> m) {
			return new double[] {number(m.get("X"), dflt).doubleValue(), number(m.get("Y"), dflt).doubleValue(),
					number(m.get("Z"), dflt).doubleValue()};
		}
		return new double[] {dflt, dflt, dflt};
	}

	private static int[] rotator(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) {
			return new int[] {number(m.get("Pitch"), 0).intValue(), number(m.get("Yaw"), 0).intValue(),
					number(m.get("Roll"), 0).intValue()};
		}
		return new int[3];
	}

	private static Number number(@Nullable Object v, double dflt) {
		return v instanceof Number n ? n : dflt;
	}
}
