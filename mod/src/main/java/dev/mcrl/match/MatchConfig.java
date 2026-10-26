package dev.mcrl.match;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import net.fabricmc.loader.api.FabricLoader;

public record MatchConfig(String arenaMeshesDir, MatchSize defaultSize, boolean bots, String botPolicy,
		float botTemperature) {
	public static final float MAX_BOT_TEMPERATURE = 1.5f;
	public static final MatchConfig DEFAULTS = new MatchConfig("", MatchSize.DUEL);

	public MatchConfig {
		botTemperature = clampTemperature(botTemperature);
	}

	public MatchConfig(String arenaMeshesDir, MatchSize defaultSize) {
		this(arenaMeshesDir, defaultSize, true, "", 0f);
	}

	public static float clampTemperature(float t) {
		return Float.isNaN(t) ? 0f : Math.max(0f, Math.min(MAX_BOT_TEMPERATURE, t));
	}

	public static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcrl.json");
	}

	public static MatchConfig read(Path configFile) {
		if (!Files.isRegularFile(configFile)) {
			return DEFAULTS;
		}
		try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (root.isJsonObject()) {
				JsonObject o = root.getAsJsonObject();
				MatchSize size = MatchSize.DUEL;
				boolean bots = true;
				float temperature = 0f;
				if (o.get("match") instanceof JsonObject match) {
					MatchSize parsed = MatchSize.parse(string(match, "defaultSize"));
					size = parsed != null ? parsed : MatchSize.DUEL;
					if (match.get("bots") instanceof JsonPrimitive b && b.isBoolean()) {
						bots = b.getAsBoolean();
					}
					if (match.get("botTemperature") instanceof JsonPrimitive t && t.isNumber()) {
						temperature = t.getAsFloat();
					}
				}
				return new MatchConfig(string(o, "arenaMeshesDir"), size, bots, string(o, "botPolicy"), temperature);
			}
		} catch (IOException | RuntimeException e) {
		}
		return DEFAULTS;
	}

	public static String rlCacheDir(Path configFile) {
		if (!Files.isRegularFile(configFile)) {
			return "";
		}
		try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			return root.isJsonObject() ? string(root.getAsJsonObject(), "rlCacheDir") : "";
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString().trim() : "";
	}
}
