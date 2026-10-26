package dev.mcrl.rlassets.upk;

import java.util.Map;

public record UObject(String classPath, String path, String archetype, Map<String, Object> props, int tagStart, int tagEnd,
		int size) {
}
