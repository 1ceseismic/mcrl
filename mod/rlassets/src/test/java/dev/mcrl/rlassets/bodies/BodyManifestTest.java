package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mcrl.rlassets.WheelRig;

class BodyManifestTest {
	static WheelRig rig() {
		return new WheelRig(new float[][] {{1, -0.1f, 0.5f}, {1, -0.1f, -0.5f}, {-0.7f, -0.08f, 0.6f},
			{-0.7f, -0.08f, -0.6f}}, new float[][] {{1, -0.1f, 0.48f}, {1, -0.1f, -0.48f}, null, null},
				new double[] {-6, -6, -4.3, -4.3}, 0.02, 0, 8, 10);
	}

	static BodyManifest full() {
		Map<String, String> meshes = new LinkedHashMap<>();
		for (String m : BodyManifest.MESHES) {
			meshes.put(m, "meshes/" + m + ".mcrm");
		}
		meshes.put("fx_test_sheet", "meshes/fx_test_sheet.mcrm");
		JsonObject report = new JsonObject();
		report.addProperty("discToRestMaxUu", 0.4);
		return new BodyManifest("Body_Test", "Test", 1, "fp;body=1", meshes, "materials.json", "MIC_Body_Test",
				"Test_Chassis_MIC", "Body_Test_SF:Body_Test.FXActor", "fxactors.json", "effects.json",
				Map.of("RocketBoost", new float[] {-1, 0.2f, 0}), rig(), report);
	}

	private static IllegalArgumentException refused(Consumer<JsonObject> change) {
		JsonObject j = full().toJson();
		change.accept(j);
		return assertThrows(IllegalArgumentException.class, () -> BodyManifest.fromJson(j));
	}

	@Test
	void roundTrip() {
		BodyManifest m = full();
		JsonObject j = m.toJson();
		assertEquals(BodyManifest.BODY_VERSION, j.get("bodyVersion").getAsInt());
		BodyManifest back = BodyManifest.fromJson(JsonParser.parseString(j.toString()));
		assertEquals(m, back, "arrays by value");
		assertEquals(11, back.meshes().size());
		assertNotEquals(m, new BodyManifest("Body_Test", "Test", 1, "fp;body=1", m.meshes(), "materials.json",
				"MIC_Body_Test", "Test_Chassis_MIC", null, null, null, Map.of("RocketBoost", new float[] {-1, 0.3f, 0}),
				rig(), new JsonObject()));
	}

	@Test
	void refusals() {
		IllegalArgumentException x = refused(j -> j.getAsJsonObject("meshes").remove("chassis"));
		assertEquals("no mesh chassis", x.getMessage());
		x = refused(j -> j.addProperty("bodyVersion", 2));
		assertEquals("body version 2, this mcrl reads 1", x.getMessage());
		x = refused(j -> j.getAsJsonObject("meshes").addProperty("body_shell", "../x.mcrm"));
		assertTrue(x.getMessage().contains("leaves the body dir"), x.getMessage());
		x = refused(j -> j.addProperty("materials", "/etc/passwd"));
		assertTrue(x.getMessage().contains("leaves the body dir"), x.getMessage());
		x = refused(j -> j.addProperty("effects", "a/../../b.json"));
		assertTrue(x.getMessage().contains("leaves the body dir"), x.getMessage());
		refused(j -> j.addProperty("id", "../x"));
		refused(j -> j.addProperty("preset", 7));
		refused(j -> j.remove("wheels"));
	}
}
