package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class BodyIndexTest {
	static BodyIndex twoEntries() {
		return new BodyIndex("fp1;index=1", List.of(
				new BodyIndex.Entry("Body_Octane", "Octane", "Body_Octane_SF.upk", 0, "Octane", true, null),
				new BodyIndex.Entry("Body_Weird", "Weird", "Body_Weird_SF.upk", 3, null, false,
						"materials: Body_Paintable_Mat, MasterChassis_MAT, Glass_Mat")));
	}

	@Test
	void roundTrip() {
		BodyIndex i = twoEntries();
		JsonObject j = i.toJson();
		assertEquals(BodyIndex.INDEX_VERSION, j.get("indexVersion").getAsInt());
		assertEquals(i, BodyIndex.fromJson(JsonParser.parseString(j.toString())));
		assertEquals(3, i.get("Body_Weird").preset());
		assertNull(i.get("Body_Nope"));
	}

	@Test
	void refusals() {
		JsonObject j = twoEntries().toJson();
		j.addProperty("indexVersion", BodyIndex.INDEX_VERSION + 1);
		IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> BodyIndex.fromJson(j));
		assertTrue(x.getMessage().contains("index version " + (BodyIndex.INDEX_VERSION + 1)), x.getMessage());
		JsonObject bad = twoEntries().toJson();
		bad.getAsJsonArray("bodies").get(0).getAsJsonObject().addProperty("id", "../x");
		assertThrows(IllegalArgumentException.class, () -> BodyIndex.fromJson(bad));
	}
}
