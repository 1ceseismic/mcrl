package dev.mcrl.rlassets;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonParser;

import dev.mcrl.rlassets.bodies.BodyCacheWriter;
import dev.mcrl.rlassets.bodies.BodyConverter;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.rlassets.bodies.BodyIndex;
import dev.mcrl.rlassets.bodies.BodyScanner;
import dev.mcrl.rlassets.bodies.BodyThumbs;

public final class Cli {
	public static final int OK = 0, ERROR = 1, USAGE = 2, NO_RL = 3, TOOL = 4, INVALID = 5;

	private static final String USAGE_TEXT = """
			usage: rlassets convert [--rl-dir DIR] [--umodel PATH] [--vgmstream PATH] [--out DIR] [--force] [--stadium]
			                        [--bodies scan|all|ID[,ID...]]
			  --rl-dir DIR       the RL install or its TAGame/CookedPCConsole (default: Steam, Epic, Heroic, MCRL_RL_DIR)
			  --umodel PATH      UE Viewer binary (default ~/.local/share/mcrl/tools/umodel)
			  --vgmstream PATH   vgmstream-cli (default ~/.local/share/mcrl/tools/vgmstream/vgmstream-cli)
			  --out DIR          cache dir (default ~/.local/share/mcrl/rl-cache/v2, or -Dmcrl.rlCacheDir)
			  --force            convert even if the cache matches the install
			  --stadium          then convert the stadium too (arena collision meshes, look) into the cache
			  --bodies LIST      then the RL bodies: scan (list them into bodies/index.json), all or IDs
			  --out must be empty, missing or an RL cache (manifest.json), never under ~/.minecraft or on the RL drive
			exit: 0 ok, 2 usage or refused --out, 3 RL not found or unmounted, 4 tool missing or failed, 5 validation
			      failed, 1 other error
			""";

	private Cli() {
	}

	public static void main(String[] args) {
		System.exit(run(args, RlLocator.Env.system(null), System.out, System.err));
	}

	static int run(String[] args, RlLocator.Env env, PrintStream out, PrintStream err) {
		if (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help"))) {
			out.print(USAGE_TEXT);
			return OK;
		}
		if (args.length == 0 || !args[0].equals("convert")) {
			return usage(err, args.length == 0 ? "missing command" : "unknown command '" + args[0] + "'");
		}
		String rlDir = null, umodelOpt = null, vgmOpt = null, outOpt = null, bodiesOpt = null;
		boolean force = false, stadium = false;
		for (int i = 1; i < args.length; i++) {
			String a = args[i], val = null;
			int eq = a.indexOf('=');
			if (a.startsWith("--") && eq > 0) {
				val = a.substring(eq + 1);
				a = a.substring(0, eq);
			}
			switch (a) {
				case "-h", "--help" -> {
					out.print(USAGE_TEXT);
					return OK;
				}
				case "--force" -> {
					if (val != null) {
						return usage(err, "--force takes no value");
					}
					force = true;
				}
				case "--stadium" -> {
					if (val != null) {
						return usage(err, "--stadium takes no value");
					}
					stadium = true;
				}
				case "--rl-dir", "--umodel", "--vgmstream", "--out", "--bodies" -> {
					if (val == null) {
						if (i + 1 >= args.length) {
							return usage(err, a + " needs a value");
						}
						val = args[++i];
					}
					if (val.isBlank()) {
						return usage(err, a + " needs a non-empty value");
					}
					switch (a) {
						case "--rl-dir" -> rlDir = val;
						case "--umodel" -> umodelOpt = val;
						case "--vgmstream" -> vgmOpt = val;
						case "--bodies" -> bodiesOpt = val;
						default -> outOpt = val;
					}
				}
				default -> {
					return usage(err, (a.startsWith("-") ? "unknown option '" : "unexpected word '") + args[i] + "'");
				}
			}
		}

		if (bodiesOpt != null && !bodiesValid(bodiesOpt)) {
			return usage(err, "--bodies takes scan, all or body ids ([A-Za-z0-9_], at most 64, comma separated): '"
					+ bodiesOpt + "'");
		}
		if (rlDir != null) {
			Path d = env.fs().getPath(rlDir);
			if (!Files.isRegularFile(d.resolve("Startup.upk")) && !Files.isRegularFile(d.resolve("TAGame/CookedPCConsole/Startup.upk"))) {
				return exit(err, NO_RL, "--rl-dir " + rlDir + " holds neither Startup.upk nor TAGame/CookedPCConsole/Startup.upk"
						+ " (not mounted?)");
			}
		}
		RlLocator.Env e = rlDir == null ? env
				: new RlLocator.Env(env.home(), env.environment(), env.mountRoot(), rlDir, env.fs(), env.lastFound());
		LocateResult loc = RlLocator.locate(e);
		if (loc.state() != LocateResult.State.FOUND || loc.cookedDir() == null) {
			return exit(err, NO_RL, "Rocket League not found or not mounted: " + loc.hint());
		}
		out.println("rlassets: Rocket League " + loc.hint() + ": " + loc.cookedDir());
		Path cache = outOpt != null ? Path.of(outOpt) : McrlPaths.cacheDir();
		VgmstreamRunner vgm = null;
		Path vp = VgmstreamRunner.binary(vgmOpt);
		if (Files.isRegularFile(vp) && Files.isExecutable(vp) && loc.installDir() != null) {
			vgm = new VgmstreamRunner(vp, loc.installDir());
		} else if (vgmOpt != null) {
			err.println("rlassets: warning: vgmstream not usable at " + vp + "; sounds are skipped");
		}
		boolean current = !force && Converter.isCurrent(loc, cache, vgm != null ? VgmstreamRunner.identity(vp) : null);
		if (current) {
			out.println("rlassets: the cache at " + cache + " is up to date (fingerprint matches); --force rebuilds it");
			if (!stadium && bodiesOpt == null) {
				return OK;
			}
		}
		Path umodel = UmodelRunner.binary(umodelOpt);
		if (!Files.isRegularFile(umodel) || !Files.isExecutable(umodel)) {
			return exit(err, TOOL, "umodel not found or not executable at " + umodel
					+ " (build it with scripts/setup-tools.sh, or pass --umodel)");
		}
		UmodelRunner runner = new UmodelRunner(umodel, loc.cookedDir());
		if (!current) {
			Converter.ConvertResult r = Converter.convert(loc, runner, vgm, cache, s -> out.println("rlassets: " + s));
			if (!r.ok()) {
				return exit(err, code(r), r.message());
			}
			out.println("rlassets: " + r.message());
		}
		if (stadium) {
			Converter.ConvertResult r = Converter.convertStadium(loc, runner, cache, force, s -> out.println("rlassets: " + s));
			if (!r.ok()) {
				return exit(err, code(r), r.message());
			}
			out.println("rlassets: " + r.message());
		}
		if (bodiesOpt == null) {
			return OK;
		}
		BodyIndex index = bodiesOpt.equals("scan") ? null : index(cache);
		if (index == null || !index.fingerprint().equals(BodyScanner.fingerprint(loc))) {
			try {
				long t0 = System.nanoTime();
				index = BodyScanner.scan(loc, runner, runner.workDir().resolve("bodyscan-" + ProcessHandle.current()
						.pid()), s -> out.println("rlassets: " + s));
				BodyCacheWriter.writeIndex(cache, index, guard(loc));
				long supported = index.bodies().stream().filter(BodyIndex.Entry::supported).count();
				out.println("rlassets: " + index.bodies().size() + " bodies listed (" + supported + " supported) in "
						+ (System.nanoTime() - t0) / 1_000_000 + " ms into " + cache.resolve("bodies/index.json"));
			} catch (BodyScanner.ToolFailedException x) {
				return exit(err, TOOL, "the body scan failed: " + x.getMessage());
			} catch (IOException x) {
				return exit(err, ERROR, "the body scan failed: " + x.getMessage());
			} catch (InterruptedException x) {
				Thread.currentThread().interrupt();
				return exit(err, ERROR, "the body scan was interrupted");
			}
		}
		if (bodiesOpt.equals("scan")) {
			return OK;
		}
		List<String> ids = new ArrayList<>();
		if (bodiesOpt.equals("all")) {
			ids.add(BodyIds.OCTANE);
			index.bodies().stream().filter(BodyIndex.Entry::supported).map(BodyIndex.Entry::id)
					.filter(id -> !BodyIds.isDefault(id)).forEach(ids::add);
		} else {
			ids.addAll(List.of(bodiesOpt.split(",")));
		}
		int code = OK;
		for (String id : ids) {
			if (!BodyIds.isDefault(id)) {
				if (!force && BodyConverter.isCurrent(loc, cache, id)) {
					out.println("rlassets: body " + id + " is up to date; --force converts it again");
				} else {
					Converter.ConvertResult r = BodyConverter.convert(loc, runner, id, cache,
							s -> out.println("rlassets: " + s));
					if (r.ok()) {
						out.println("rlassets: " + r.message());
					} else {
						int c = exit(err, code(r), "body " + id + ": " + r.message());
						code = code == OK ? c : code;
						continue;
					}
				}
			}
			try {
				Path thumb = BodyThumbs.export(runner, loc.cookedDir(), id, cache, guard(loc));
				out.println("rlassets: " + (thumb != null ? "thumbnail " + thumb : "no thumbnail for " + id
						+ " (no " + id + "_T_SF.upk, or umodel exported none)"));
			} catch (IOException x) {
				int c = exit(err, ERROR, "the thumbnail of " + id + " failed: " + x.getMessage());
				code = code == OK ? c : code;
			} catch (InterruptedException x) {
				Thread.currentThread().interrupt();
				return exit(err, ERROR, "interrupted");
			}
		}
		return code;
	}

	private static CacheWriter.Guard guard(LocateResult loc) {
		return CacheWriter.Guard.standard(loc.installDir() != null ? loc.installDir() : loc.cookedDir());
	}

	private static @Nullable BodyIndex index(Path cache) {
		try {
			return BodyIndex.fromJson(JsonParser.parseString(Files.readString(cache.resolve("bodies/index.json"),
					StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException x) {
			return null;
		}
	}

	private static int code(Converter.ConvertResult r) {
		return switch (r.failure()) {
			case RL_NOT_FOUND -> NO_RL;
			case TOOL_MISSING, ASSETS_CHANGED -> TOOL;
			case VALIDATION -> INVALID;
			case REFUSED -> USAGE;
			default -> ERROR;
		};
	}

	static boolean bodiesValid(String v) {
		if (v.equals("scan") || v.equals("all")) {
			return true;
		}
		for (String id : v.split(",", -1)) {
			if (!BodyIds.valid(id)) {
				return false;
			}
		}
		return true;
	}

	private static int usage(PrintStream err, String why) {
		err.println("rlassets: " + why);
		err.print(USAGE_TEXT);
		return USAGE;
	}

	private static int exit(PrintStream err, int code, String why) {
		err.println("rlassets: exit " + code + ": " + why);
		return code;
	}
}
