package dev.mcrl.rlassets.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public record MaterialDef(String name, String path, String className, @Nullable String parent, Map<String, Float> scalars,
		Map<String, float[]> vectors, Map<String, String> textures, Map<String, Boolean> switches,
		@Nullable String blendMode, @Nullable Boolean twoSided, @Nullable String lightingModel, @Nullable Defaults defaults,
		List<String> shaderTextures) {
	public record Defaults(Map<String, Float> scalars, Map<String, float[]> vectors, Map<String, String> textures,
			Map<String, Boolean> switches) {
		public Defaults {
			scalars = copy(scalars);
			vectors = copy(vectors);
			textures = copy(textures);
			switches = copy(switches);
		}
	}

	public MaterialDef {
		scalars = copy(scalars);
		vectors = copy(vectors);
		textures = copy(textures);
		switches = copy(switches);
		shaderTextures = List.copyOf(shaderTextures);
	}

	public boolean base() {
		return parent == null;
	}

	private static <V> Map<String, V> copy(Map<String, V> m) {
		return Collections.unmodifiableMap(new LinkedHashMap<>(m));
	}
}
