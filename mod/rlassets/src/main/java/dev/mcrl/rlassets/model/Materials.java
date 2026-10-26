package dev.mcrl.rlassets.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record Materials(Map<String, MaterialDef> materials, Map<String, TextureInfo> textures) {
	public static final Materials EMPTY = new Materials(Map.of(), Map.of());

	public Materials {
		materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
		textures = Collections.unmodifiableMap(new LinkedHashMap<>(textures));
	}

	public List<MaterialDef> chain(String name) {
		List<MaterialDef> out = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (MaterialDef d = find(name); d != null && seen.add(d.name()); d = d.parent() == null ? null
				: materials.get(d.parent())) {
			out.add(d);
		}
		return out;
	}

	public @Nullable String texture(String material, String param) {
		List<MaterialDef> chain = chain(material);
		for (MaterialDef d : chain) {
			String t = d.textures().get(param);
			if (t != null) {
				return t;
			}
		}
		for (MaterialDef d : chain) {
			if (d.defaults() != null && d.defaults().textures().containsKey(param)) {
				return d.defaults().textures().get(param);
			}
		}
		return null;
	}

	public @Nullable Float scalar(String material, String param) {
		List<MaterialDef> chain = chain(material);
		for (MaterialDef d : chain) {
			Float v = d.scalars().get(param);
			if (v != null) {
				return v;
			}
		}
		for (MaterialDef d : chain) {
			if (d.defaults() != null && d.defaults().scalars().containsKey(param)) {
				return d.defaults().scalars().get(param);
			}
		}
		return null;
	}

	public float @Nullable [] vector(String material, String param) {
		List<MaterialDef> chain = chain(material);
		for (MaterialDef d : chain) {
			float[] v = d.vectors().get(param);
			if (v != null) {
				return v.clone();
			}
		}
		for (MaterialDef d : chain) {
			if (d.defaults() != null && d.defaults().vectors().containsKey(param)) {
				return d.defaults().vectors().get(param).clone();
			}
		}
		return null;
	}

	public @Nullable MaterialDef find(String name) {
		MaterialDef d = materials.get(name);
		if (d != null) {
			return d;
		}
		String objectPath = name.substring(name.indexOf(':') + 1);
		d = materials.get(objectPath);
		if (d != null) {
			return d;
		}
		for (MaterialDef m : materials.values()) {
			if (m.path().substring(m.path().indexOf(':') + 1).equals(objectPath)) {
				return m;
			}
		}
		return materials.get(name.substring(Math.max(name.lastIndexOf(':'), name.lastIndexOf('.')) + 1));
	}
}
