package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import dev.mcrl.rlassets.LocateResult.State;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RlLocatorTest {
	@TempDir
	Path tmp;
	Path home;
	Path mounts;

	@BeforeEach
	void setUp() throws IOException {
		home = Files.createDirectories(tmp.resolve("home"));
		mounts = Files.createDirectories(tmp.resolve("run/media/user"));
	}

	RlLocator.Env env(String cfg, Map<String, String> environment) {
		return new RlLocator.Env(home, environment, mounts, cfg, FileSystems.getDefault());
	}

	static Path touch(Path f) throws IOException {
		Files.createDirectories(f.getParent());
		Files.writeString(f, "x");
		return f;
	}

	Path installWithCooked(Path install) throws IOException {
		touch(install.resolve("TAGame/CookedPCConsole/Startup.upk"));
		return install;
	}

	void epicManifest(Path volume, String loc) throws IOException {
		touch(volume.resolve("ProgramData/Epic/EpicGamesLauncher/Data/Manifests/ABC123.item"));
		Files.writeString(volume.resolve("ProgramData/Epic/EpicGamesLauncher/Data/Manifests/ABC123.item"),
				"{\"AppName\": \"Sugar\", \"DisplayName\": \"Rocket League\u00ae\", \"InstallLocation\": \""
						+ loc.replace("\\", "\\\\") + "\"}");
	}

	@Test
	void epicTreeMirrorsUserLayout() throws IOException {
		Path vol = Files.createDirectories(mounts.resolve("VOL"));
		epicManifest(vol, "C:\\Program Files\\Epic Games\\rocketleague");
		Path install = installWithCooked(vol.resolve("Program Files/Epic Games/rocketleague"));
		LocateResult r = RlLocator.locate(env(null, Map.of()));
		assertEquals(State.FOUND, r.state());
		assertEquals("epic", r.source());
		assertEquals(install, r.installDir());
		assertEquals(install.resolve("TAGame/CookedPCConsole"), r.cookedDir());
		assertTrue(r.fingerprint().startsWith("Startup.upk=1@"), r.fingerprint());
		assertTrue(r.fingerprint().endsWith(";MENU_Main_p.upk=missing;Boost_Standard_SF.upk=missing"), r.fingerprint());
	}

	@Test
	void steamFoundWithCooked() throws IOException {
		Path lib = Files.createDirectories(tmp.resolve("lib"));
		Path install = installWithCooked(lib.resolve("steamapps/common/rocketleague"));
		touch(home.resolve(".steam/steam/steamapps/libraryfolders.vdf"));
		Files.writeString(home.resolve(".steam/steam/steamapps/libraryfolders.vdf"),
				"\"libraryfolders\" { \"0\" { \"path\" \"" + lib + "\" \"apps\" { \"1\" \"2\" \"252950\" \"3\" } }"
						+ " \"1\" { \"path\" \"/nowhere\" \"apps\" { \"7\" \"8\" } } }");
		LocateResult r = RlLocator.locate(env(null, Map.of()));
		assertEquals(State.FOUND, r.state());
		assertEquals("steam", r.source());
		assertEquals(install, r.installDir());
	}

	@Test
	void heroicFound() throws IOException {
		Path install = installWithCooked(tmp.resolve("games/rl"));
		touch(home.resolve(".config/heroic/legendaryConfig/legendary/installed.json"));
		Files.writeString(home.resolve(".config/heroic/legendaryConfig/legendary/installed.json"),
				"{\"Sugar\": {\"title\": \"Rocket League\", \"install_path\": \"" + install + "\"}}");
		LocateResult r = RlLocator.locate(env(null, Map.of()));
		assertEquals(State.FOUND, r.state());
		assertEquals("heroic", r.source());
	}
}
