package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RlStateTest {
	@TempDir
	Path tmp;

	@Test
	void anEmptyOrBrokenFileRemembersNothing() throws Exception {
		Path f = tmp.resolve("state/rl-state.json");
		assertNull(RlState.lastInstall(f));
		assertNull(RlState.failed(f));
		Files.createDirectories(f.getParent());
		Files.writeString(f, "{not json");
		assertNull(RlState.lastInstall(f));
		assertNull(RlState.failed(f));
	}

	@Test
	void remembersTheInstallAndTheFailureSideBySide() throws Exception {
		Path f = tmp.resolve("state/rl-state.json");
		Path install = Files.createDirectories(tmp.resolve("games/rocketleague"));
		RlState.rememberInstall(f, install, false);
		assertEquals(install, RlState.lastInstall(f));
		RlState.rememberFailed(f, new RlState.Failed("Startup.upk=1@2;converter=1", "umodel exit 1 exporting X"));
		assertEquals(new RlState.Failed("Startup.upk=1@2;converter=1", "umodel exit 1 exporting X"), RlState.failed(f));
		assertEquals(install, RlState.lastInstall(f), "one does not clear the other");
		RlState.rememberFailed(f, null);
		assertNull(RlState.failed(f));
		assertEquals(install, RlState.lastInstall(f));
		try (var s = Files.list(f.getParent())) {
			assertEquals(1, s.count(), "no temp file left");
		}
	}

	@Test
	void neverWritesOnTheInstallsVolume() throws Exception {
		Path f = tmp.resolve("state/rl-state.json");
		Path install = Files.createDirectories(tmp.resolve("games/rocketleague"));
		RlState.rememberInstall(f, install);
		assertFalse(Files.exists(f));
	}
}
