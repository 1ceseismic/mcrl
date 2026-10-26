package dev.mcrl.rlassets.bodies;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.ToolRunner;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkWorld;

public final class BodyScanner {
	public static final int SCAN_BATCH = 16;
	public static final String BODY_ROOT = "Body_Paintable_Mat", CHASSIS_ROOT = "MasterChassis_MAT";
	public static final List<String> CHASSIS_ROOTS = List.of(CHASSIS_ROOT, "MasterChassis_GoodENV_MAT",
			"MasterChassis_OldCar_MAT");
	public static final List<String> UNPACK = List.of("Core", "Engine", "IpDrv", "ProjectX", "TAGame", "Startup");
	static final String PRODUCT_CLASS = "ProductAsset_Body_TA";
	private static final int BOUNDS_BYTES = 28; // UE3 FBoxSphereBounds
	private static final int MAX_SLOTS = 64;
	private static final int MAX_CHAIN = 16;

	private BodyScanner() {
	}

	public static List<String> bodyPackages(Path cookedDir) {
		try (Stream<Path> s = Files.list(cookedDir)) {
			return s.map(p -> p.getFileName().toString())
					.filter(n -> n.startsWith("Body_") && n.endsWith("_SF.upk") && !n.endsWith("_T_SF.upk"))
					.sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public static String fingerprint(LocateResult loc) {
		if (loc.cookedDir() == null) {
			return "";
		}
		String f = loc.fingerprint(bodyPackages(loc.cookedDir()));
		return f.isEmpty() ? f : f + ";index=" + BodyIndex.INDEX_VERSION;
	}

	public static BodyIndex.Entry classify(String pkg, @Nullable UObject product, @Nullable UObject handlingPreset,
			List<List<String>> slotChains) {
		String id = BodyIds.id(pkg), label = BodyIds.label(id);
		if (product == null) {
			return new BodyIndex.Entry(id, label, pkg, 0, null, false, "no " + PRODUCT_CLASS);
		}
		String presetName = handlingPreset != null ? lastName(handlingPreset.path())
				: product.props().get("HandlingPreset") instanceof UpkRef r ? lastName(r.path()) : null;
		double[] front = handlingPreset == null ? null : rest(handlingPreset, "FrontAxle");
		double[] back = handlingPreset == null ? null : rest(handlingPreset, "BackAxle");
		int preset = PresetMatch.resolve(front, back, presetName);
		List<String> roots = slotChains.stream().map(c -> c.isEmpty() ? "?" : c.getLast()).toList();
		Map<String, String> materials = new LinkedHashMap<>();
		slotChains.forEach(c -> materials.putIfAbsent(c.isEmpty() ? "?" : c.getFirst(), c.isEmpty() ? "?" : c.getLast()));
		boolean supported = materials.size() == 2 && materials.containsValue(BODY_ROOT)
				&& materials.values().stream().anyMatch(CHASSIS_ROOTS::contains);
		return new BodyIndex.Entry(id, label, pkg, preset, presetName, supported,
				supported ? null : "materials: " + String.join(", ", roots));
	}

	public static BodyIndex scan(LocateResult loc, UmodelRunner umodel, Path work, Consumer<String> progress)
			throws IOException, InterruptedException {
		Path cooked = loc.cookedDir();
		if (loc.state() != LocateResult.State.FOUND || cooked == null) {
			throw new IOException("Rocket League not found: " + loc.hint());
		}
		List<String> pkgs = bodyPackages(cooked);
		Path unpacked = work.resolve("unpacked");
		List<BodyIndex.Entry> entries = new ArrayList<>();
		Converter.removeStaleWork(umodel.workDir());
		try {
			CacheWriter.deleteTree(unpacked);
			Files.createDirectories(unpacked);
			progress.accept("Unpacking " + UNPACK.size() + " packages for the body index");
			ToolRunner.ToolResult t = umodel.unpack(UNPACK, unpacked);
			for (String p : UNPACK) {
				if (!Files.isRegularFile(unpacked.resolve(p + ".bin"))) {
					throw new ToolFailedException("umodel did not unpack " + p + " (exit " + t.exit() + ")");
				}
			}
			for (int i = 0; i < pkgs.size(); i += SCAN_BATCH) {
				List<String> batch = pkgs.subList(i, Math.min(i + SCAN_BATCH, pkgs.size()));
				progress.accept("Reading bodies " + (i + 1) + "-" + (i + batch.size()) + " of " + pkgs.size());
				List<String> stems = batch.stream().map(BodyScanner::stem).toList();
				umodel.unpack(stems, unpacked);
				UpkWorld world = new UpkWorld(unpacked);
				for (String pkg : batch) {
					BodyIndex.Entry e = entry(world, pkg, progress);
					if (e != null) {
						entries.add(e);
					}
				}
				for (String s : stems) {
					Files.deleteIfExists(unpacked.resolve(s + ".bin"));
				}
			}
		} finally {
			CacheWriter.deleteTree(work);
		}
		return new BodyIndex(fingerprint(loc), entries);
	}

	public static final class ToolFailedException extends IOException {
		public ToolFailedException(String message) {
			super(message);
		}
	}

	static BodyIndex.@Nullable Entry entry(UpkWorld world, String pkg, Consumer<String> progress) {
		String stem = stem(pkg), id = BodyIds.id(pkg);
		UpkReader p = world.pkg(stem);
		if (p == null) {
			return new BodyIndex.Entry(id, BodyIds.label(id), pkg, 0, null, false, "package did not unpack");
		}
		try {
			int productIndex = product(p, id);
			if (productIndex <= 0) {
				progress.accept(id + ": no " + PRODUCT_CLASS + ", not a body");
				return null;
			}
			UObject product = world.obj(stem, productIndex);
			UObject preset = null;
			if (product.props().get("HandlingPreset") instanceof UpkRef r) {
				UpkWorld.Loc l = world.locate(r.path(), stem);
				preset = l == null ? null : world.obj(l.pkg(), l.index());
			}
			double[] front = preset == null ? null : rest(preset, "FrontAxle");
			double[] back = preset == null ? null : rest(preset, "BackAxle");
			if (front == null || back == null || PresetMatch.byRest(front, back) < 0) {
				progress.accept(id + ": its handling preset's rest positions match no RocketSim preset; preset by name");
			}
			return classify(pkg, product, preset, slotChains(world, product, stem));
		} catch (IOException | RuntimeException e) {
			return new BodyIndex.Entry(id, BodyIds.label(id), pkg, 0, null, false, "unreadable: " + e.getMessage());
		}
	}

	static int product(UpkReader p, String id) {
		int first = 0;
		for (int i = 1; i <= p.exports().size(); i++) {
			UpkReader.Export e = p.exports().get(i - 1);
			if (PRODUCT_CLASS.equals(p.className(e.classIndex()))) {
				if (e.objectName().equals(id)) {
					return i;
				}
				first = first == 0 ? i : first;
			}
		}
		return first;
	}

	static List<List<String>> slotChains(UpkWorld world, UObject product, String stem) throws IOException {
		if (!(product.props().get("Mesh") instanceof UpkRef meshRef)) {
			throw new IOException("the product has no Mesh");
		}
		UpkWorld.Loc l = world.locate(meshRef.path(), stem);
		if (l == null) {
			throw new IOException("its mesh " + meshRef.path() + " is not in the unpacked packages");
		}
		UpkReader p = world.pkg(l.pkg());
		UObject mesh = world.obj(l.pkg(), l.index());
		ByteBuffer b = p.body(l.index());
		int at = mesh.tagEnd() + BOUNDS_BYTES;
		if (at + 4 > b.limit()) {
			throw new IOException("mesh " + mesh.path() + " ends before its materials");
		}
		int n = b.getInt(at);
		int refs = p.imports().size() + p.exports().size();
		if (n < 1 || n > MAX_SLOTS || at + 4 + 4L * n > b.limit()) {
			throw new IOException("mesh " + mesh.path() + ": " + n + " material slots");
		}
		List<List<String>> chains = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int k = b.getInt(at + 4 + 4 * i);
			if (k == 0 || k < -p.imports().size() || k > p.exports().size()) {
				throw new IOException("mesh " + mesh.path() + ": slot " + i + " is not an object (" + k + " of " + refs
						+ ")");
			}
			chains.add(chain(world, p, k));
		}
		return chains;
	}

	private static List<String> chain(UpkWorld world, UpkReader p, int k) {
		List<String> out = new ArrayList<>();
		UpkRef ref = world.ref(p, k);
		String path = ref == null ? p.objectPath(k) : ref.path();
		UpkWorld.Loc l = world.resolve(p, k);
		for (int depth = 0; depth < MAX_CHAIN; depth++) {
			out.add(lastName(path));
			if (l == null || !(world.obj(l.pkg(), l.index()).props().get("Parent") instanceof UpkRef parent)) {
				break;
			}
			path = parent.path();
			l = world.locate(path, l.pkg());
		}
		return out;
	}

	private static double @Nullable [] rest(UObject preset, String axle) {
		if (preset.props().get(axle) instanceof Map<?, ?> a && a.get("LocalRestPosition") instanceof Map<?, ?> v
				&& v.get("X") instanceof Number x && v.get("Y") instanceof Number y && v.get("Z") instanceof Number z) {
			return new double[] {x.doubleValue(), y.doubleValue(), z.doubleValue()};
		}
		return null;
	}

	static String lastName(String path) {
		return path.substring(Math.max(path.lastIndexOf(':'), path.lastIndexOf('.')) + 1);
	}

	static String stem(String pkg) {
		return pkg.endsWith(".upk") ? pkg.substring(0, pkg.length() - 4) : pkg;
	}
}
