package dev.mcrl.client.rl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class RlBodiesTest {
	@TempDir
	Path tmp;

	private Path cache() throws Exception {
		return RlCacheFixtures.writeCache(tmp.resolve("cache"), "fp", tmp);
	}

	@Test
	void brokenBodySkipped() throws Exception {
		Path dir = cache();
		Path body = RlCacheFixtures.writeBody(dir, "Body_Test", 1, "MIC_Body_Test", "Test_Chassis_MIC", List.of());
		JsonObject m = JsonParser.parseString(Files.readString(body.resolve("manifest.json"))).getAsJsonObject();
		m.getAsJsonObject("meshes").remove("chassis");
		Files.writeString(body.resolve("manifest.json"), m.toString());
		RlCache rl = RlCache.read(dir);
		assertNull(rl.bodies().look("Body_Test"));
		assertNotNull(rl.mesh("chassis"), "the base still reads");
	}
}
