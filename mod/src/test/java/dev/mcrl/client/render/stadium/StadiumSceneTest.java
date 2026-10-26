package dev.mcrl.client.render.stadium;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.client.render.stadium.StadiumScene.Instance;
import dev.mcrl.client.render.stadium.StadiumScene.Pass;
import dev.mcrl.client.render.stadium.StadiumScene.Tier;
import dev.mcrl.rlassets.MeshData;
import dev.mcrl.rlassets.stadium.StadiumExtractor;

class StadiumSceneTest {
	static Path fixtureDir() throws URISyntaxException {
		return Path.of(StadiumSceneTest.class.getResource("/dev/mcrl/client/stadium/stadium.json").toURI()).getParent();
	}

	@Test
	void aMeshOutsideTheDirIsRefused(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve("stadium.json"), "{\"version\": " + StadiumScene.VERSION + ", \"meshes\": {\"a\": \"../a.mcrm\"}, "
				+ "\"instances\": []}");
		assertNull(StadiumScene.load(dir));
	}

	@Test
	void convertersSceneReads(@TempDir Path dir) throws IOException, URISyntaxException {
		MeshData tri = new MeshData(new float[] {0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1, 1},
				new int[] {0, 1, 2});
		float[] id = {1, 0, 0, 0, 0, 1, 0, 64, 0, 0, 1, 0};
		String mat = "Stadium_P:M.Wall_MIC";
		StadiumExtractor.StadiumData d = new StadiumExtractor.StadiumData(Map.of("wall_0", tri), List.of(
				new StadiumExtractor.Instance("wall_0", mat, id, StadiumExtractor.Tier.NEAR, StadiumExtractor.Pass.GLASS, -1, -1),
				new StadiumExtractor.Instance("wall_0", mat, id, StadiumExtractor.Tier.FAR, StadiumExtractor.Pass.GLASS, 1, 5)),
				Map.of(mat, new StadiumExtractor.Look(StadiumExtractor.Pass.GLASS, new float[] {0.1f, 0.2f, 0.3f, 0.35f}, "t")),
				Map.of(), List.of(new StadiumExtractor.Light(new float[] {0, -1, 0}, new float[] {1, 0.9f, 0.6f}, 1)),
				new float[] {1.2f, 1.3f, 0.5f}, new StadiumExtractor.Fog(new float[] {0.5f, 0.2f, 0.05f}, 204.8f, 1004.8f, 0.9f),
				34, 0, List.of(), List.of());
		com.google.gson.JsonObject meshes = new com.google.gson.JsonObject();
		meshes.addProperty("wall_0", "meshes/wall_0.mcrm");
		Files.writeString(dir.resolve("stadium.json"), dev.mcrl.rlassets.stadium.StadiumCache.sceneJson(d, "fp", meshes));
		StadiumScene s = StadiumScene.load(dir);
		assertNotNull(s);
		assertEquals(2, s.instances().size());
		Instance plain = s.instances().get(0), team = s.instances().get(1);
		assertEquals(Pass.GLASS, plain.pass());
		assertEquals(Tier.FAR, team.tier());
		assertEquals(5, team.pad());
		assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 0.35f}, s.color(plain));
		assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f, 0.35f}, StadiumRenderer.color(s, plain));
		float[] orange = StadiumRenderer.color(s, team);
		assertEquals(0.35f, orange[3], "a team glass keeps its material's alpha");
		assertTrue(orange[0] > orange[2], "orange: red over blue");
		assertEquals(0.9f, s.fog().maxOpacity());
		assertEquals(1, s.light().key().size());
		assertArrayEquals(new float[] {1.2f, 1.3f, 0.5f}, s.light().skyColor());
		float[] lights = StadiumRenderer.lights(s);
		assertEquals(0.9f, lights[34], "FogRange.z: the most opacity");
		StadiumScene fixture = StadiumScene.load(fixtureDir());
		assertNull(fixture.color(fixture.instances().get(0)));
	}
}
