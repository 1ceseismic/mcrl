package dev.mcrl.client.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.client.camera.CameraSettings;
import dev.mcrl.client.camera.CameraSettings.BallCamMode;
import dev.mcrl.client.input.InputMapper.MapperSettings;

class McrlConfigTest {
	@TempDir
	Path dir;

	@Test
	void save_then_load_round_trips() {
		Path f = dir.resolve("mcrl.json");
		McrlConfig c = McrlConfig.load(f);
		c.camera = new CameraSettings(100, 300, 120, -5, 0.7f, 4, 1.5f, true, BallCamMode.HOLD);
		c.controller = new MapperSettings(0.2f, 0.1f, 1.5f);
		c.save();
		McrlConfig r = McrlConfig.load(f);
		assertEquals(c.camera, r.camera);
		assertEquals(c.controller, r.controller);
	}

	@Test
	void corrupt_file_loads_defaults_and_is_renamed() throws IOException {
		Path f = dir.resolve("mcrl.json");
		Files.writeString(f, "{ not json");
		McrlConfig c = McrlConfig.load(f);
		assertEquals(CameraSettings.RL_DEFAULTS, c.camera);
		assertTrue(Files.exists(dir.resolve("mcrl.json.bad")));
		assertTrue(!Files.exists(f));
	}
}
