package dev.mcrl.rlassets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RlLocator {
	private RlLocator() {
	}

	public record Env(Path home, Map<String, String> environment, Path mountRoot, @Nullable String rlCookedDirCfg, FileSystem fs,
			@Nullable Path lastFound) {
		public Env(Path home, Map<String, String> environment, Path mountRoot, @Nullable String rlCookedDirCfg, FileSystem fs) {
			this(home, environment, mountRoot, rlCookedDirCfg, fs, null);
		}

		public static Env system(@Nullable String rlCookedDirCfg) {
			String user = System.getProperty("user.name");
			return new Env(Path.of(System.getProperty("user.home")), System.getenv(),
					Path.of("/run/media", user), rlCookedDirCfg, java.nio.file.FileSystems.getDefault(),
					RlState.lastInstall(RlState.standardFile()));
		}
	}

	static final String UNMOUNTED_DRIVE_HINT = "if Rocket League is on a drive that is not mounted, mount it";

	private static final String COOKED = "TAGame/CookedPCConsole";
	private static final String STEAM_APP = "252950";

	private static final class Search {
		LocateResult unmounted;
		final List<String> notes = new ArrayList<>();
	}

	public static LocateResult locate(Env env) {
		Search s = new Search();
		LocateResult r;
		if ((r = override(env, s)) != null) {
			return r;
		}
		if ((r = steam(env, s)) != null) {
			return r;
		}
		if ((r = epic(env, s)) != null) {
			return r;
		}
		if ((r = heroic(env, s)) != null) {
			return r;
		}
		if (s.unmounted != null) {
			return s.unmounted;
		}
		Path last = env.lastFound();
		if (last != null && !Files.isDirectory(last)) {
			return new LocateResult(LocateResult.State.FOUND_UNMOUNTED, null, null, "last found",
					"Rocket League was last found at " + last + ", which is not available now; mount its drive");
		}
		String hint = "Rocket League was not found (looked at config rlCookedDir, MCRL_RL_DIR, Steam, Epic, Heroic)";
		if (!s.notes.isEmpty()) {
			hint += "; " + String.join("; ", s.notes);
		}
		hint += "; " + UNMOUNTED_DRIVE_HINT;
		return new LocateResult(LocateResult.State.NOT_FOUND, null, null, "none", hint);
	}

	private static boolean hasStartup(Path cooked) {
		return Files.isRegularFile(cooked.resolve("Startup.upk"));
	}

	private static Path cookedOf(Path install) {
		return install.resolve(COOKED);
	}

	private static LocateResult found(Path install, String source) {
		return new LocateResult(LocateResult.State.FOUND, cookedOf(install), install, source, "found via " + source);
	}

	private static LocateResult checkInstall(Path install, String source, Search s) {
		if (Files.isDirectory(install)) {
			if (hasStartup(cookedOf(install))) {
				return found(install, source);
			}
			s.notes.add(source + " install " + install + " has no " + COOKED + "/Startup.upk (stub?), skipped");
		} else {
			recordUnmounted(s, install.toString(), source);
		}
		return null;
	}

	private static void recordUnmounted(Search s, String path, String source) {
		if (s.unmounted == null) {
			s.unmounted = new LocateResult(LocateResult.State.FOUND_UNMOUNTED, null, null, source,
					"Rocket League is installed at " + path + " but that path is not available; mount its drive");
		}
	}

	private static LocateResult override(Env env, Search s) {
		String v = env.rlCookedDirCfg();
		String src = "config rlCookedDir";
		if (v == null || v.isBlank()) {
			v = env.environment().get("MCRL_RL_DIR");
			src = "MCRL_RL_DIR";
		}
		if (v == null || v.isBlank()) {
			return null;
		}
		Path p = env.fs().getPath(v);
		if (hasStartup(p)) {
			Path install = p.getParent() != null && p.getParent().getParent() != null ? p.getParent().getParent() : null;
			return new LocateResult(LocateResult.State.FOUND, p, install, src, "found via " + src);
		}
		if (hasStartup(cookedOf(p))) {
			return found(p, src);
		}
		s.notes.add(src + " " + v + " has no Startup.upk, ignored");
		return null;
	}

	private static final Pattern VDF_TOKEN = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|([{}])");

	private static LocateResult steam(Env env, Search s) {
		List<Path> steamapps = List.of(
				env.home().resolve(".local/share/Steam/steamapps"),
				env.home().resolve(".steam/steam/steamapps"));
		List<Path> libs = new ArrayList<>();
		for (Path sa : steamapps) {
			Path vdf = sa.resolve("libraryfolders.vdf");
			if (!Files.isRegularFile(vdf)) {
				continue;
			}
			try {
				libs.addAll(libraryPathsWithApp(Files.readString(vdf), env));
			} catch (IOException | RuntimeException e) {
				s.notes.add("unreadable " + vdf);
			}
		}
		for (Path lib : libs) {
			LocateResult r = checkInstall(lib.resolve("steamapps/common/rocketleague"), "steam", s);
			if (r != null) {
				return r;
			}
		}
		return null;
	}

	static List<Path> libraryPathsWithApp(String vdf, Env env) {
		List<Path> out = new ArrayList<>();
		Matcher m = VDF_TOKEN.matcher(vdf);
		List<String> toks = new ArrayList<>();
		while (m.find()) {
			toks.add(m.group(2) != null ? m.group(2) : m.group(1).replace("\\\\", "\\"));
		}
		int depth = 0;
		String path = null;
		boolean hasApp = false;
		int appsDepth = -1;
		String pendingKey = null;
		for (int i = 0; i < toks.size(); i++) {
			String t = toks.get(i);
			if (t.equals("{")) {
				depth++;
				if ("apps".equals(pendingKey)) {
					appsDepth = depth;
				}
				if (depth == 2) {
					path = null;
					hasApp = false;
				}
				pendingKey = null;
			} else if (t.equals("}")) {
				if (depth == 2 && path != null && hasApp) {
					out.add(env.fs().getPath(path));
				}
				if (depth == appsDepth) {
					appsDepth = -1;
				}
				depth--;
				pendingKey = null;
			} else {
				boolean nextIsBrace = i + 1 < toks.size() && toks.get(i + 1).equals("{");
				if (nextIsBrace) {
					pendingKey = t;
				} else if (depth == 2 && "path".equals(t) && i + 1 < toks.size()) {
					path = toks.get(++i);
				} else if (appsDepth > 0 && depth == appsDepth && STEAM_APP.equals(t)) {
					hasApp = true;
					i++;
				} else {
					i++;
				}
			}
		}
		return out;
	}

	private static LocateResult epic(Env env, Search s) {
		List<Path> volumes = listDir(env.mountRoot(), true);
		for (Path vol : volumes) {
			Path manifests = vol.resolve("ProgramData/Epic/EpicGamesLauncher/Data/Manifests");
			for (Path item : listDir(manifests, false)) {
				if (!item.getFileName().toString().endsWith(".item")) {
					continue;
				}
				String loc = rlInstallLocation(item);
				if (loc == null) {
					continue;
				}
				LocateResult r = epicResolve(loc, vol, volumes, env, s);
				if (r != null) {
					return r;
				}
			}
		}
		return null;
	}

	private static String rlInstallLocation(Path item) {
		try (Reader rd = Files.newBufferedReader(item)) {
			JsonElement e = JsonParser.parseReader(rd);
			if (!e.isJsonObject()) {
				return null;
			}
			JsonObject o = e.getAsJsonObject();
			boolean rl = "Sugar".equals(str(o, "AppName"))
					|| (str(o, "DisplayName") != null && str(o, "DisplayName").startsWith("Rocket League"));
			return rl ? str(o, "InstallLocation") : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static LocateResult epicResolve(String loc, Path manifestVol, List<Path> volumes, Env env, Search s) {
		String rest = loc.replace('\\', '/');
		if (rest.length() >= 2 && rest.charAt(1) == ':') {
			rest = rest.substring(2);
		}
		while (rest.startsWith("/")) {
			rest = rest.substring(1);
		}
		List<Path> order = new ArrayList<>();
		order.add(manifestVol);
		for (Path v : volumes) {
			if (!v.equals(manifestVol)) {
				order.add(v);
			}
		}
		boolean anyDir = false;
		for (Path v : order) {
			Path install = v.resolve(rest);
			if (Files.isDirectory(install)) {
				anyDir = true;
				if (hasStartup(cookedOf(install))) {
					return found(install, "epic");
				}
			}
		}
		if (anyDir) {
			s.notes.add("epic install " + loc + " has no " + COOKED + "/Startup.upk, skipped");
		} else {
			recordUnmounted(s, loc, "epic");
		}
		return null;
	}

	private static LocateResult heroic(Env env, Search s) {
		Path f = env.home().resolve(".config/heroic/legendaryConfig/legendary/installed.json");
		if (!Files.isRegularFile(f)) {
			return null;
		}
		try (Reader rd = Files.newBufferedReader(f)) {
			JsonElement e = JsonParser.parseReader(rd);
			if (!e.isJsonObject()) {
				return null;
			}
			for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
				if (!en.getValue().isJsonObject()) {
					continue;
				}
				JsonObject o = en.getValue().getAsJsonObject();
				String title = str(o, "title");
				String app = str(o, "app_name");
				boolean rl = "Sugar".equals(en.getKey()) || "Sugar".equals(app)
						|| (title != null && title.startsWith("Rocket League"));
				String ip = str(o, "install_path");
				if (rl && ip != null) {
					LocateResult r = checkInstall(env.fs().getPath(ip), "heroic", s);
					if (r != null) {
						return r;
					}
				}
			}
		} catch (IOException | RuntimeException ex) {
			s.notes.add("unreadable " + f);
		}
		return null;
	}

	private static List<Path> listDir(Path dir, boolean dirsOnly) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(dir)) {
			return out;
		}
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				if (!dirsOnly || Files.isDirectory(p)) {
					out.add(p);
				}
			}
		} catch (IOException unreadableAsEmpty) {
		}
		out.sort(null);
		return out;
	}
}
