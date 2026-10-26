package dev.mcrl;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import dev.mcrl.natives.Breadcrumb;

public final class SafeBoot {
	public static final String KEY = "nativeEnabled", PROPERTY = "mcrl.nativeEnabled";

	private static volatile boolean physicsOn = true;
	private static volatile Breadcrumb.@Nullable Previous lastCrash;

	private SafeBoot() {
	}

	public static boolean physicsOn() {
		return physicsOn;
	}

	public static void setPhysicsOn(boolean on) {
		physicsOn = on;
	}

	public static Breadcrumb.@Nullable Previous lastCrash() {
		return lastCrash;
	}

	static void init(Path configFile, Path gameDir) {
		physicsOn = configured(configFile, System.getProperty(PROPERTY));
		lastCrash = Breadcrumb.open(gameDir);
		Runtime.getRuntime().addShutdownHook(new Thread(Breadcrumb::markClean, "mcrl-breadcrumb-clean"));
	}

	public static boolean configured(Path configFile, @Nullable String property) {
		if (property != null) {
			String p = property.trim().toLowerCase(Locale.ROOT);
			if (p.equals("true") || p.equals("false")) {
				return p.equals("true");
			}
		}
		if (!Files.isRegularFile(configFile)) {
			return true;
		}
		try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (root.isJsonObject()) {
				JsonElement e = root.getAsJsonObject().get(KEY);
				if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
					return e.getAsBoolean();
				}
			}
		} catch (IOException | RuntimeException e) {
		}
		return true;
	}
}
