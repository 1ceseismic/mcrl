package dev.mcrl.client.render.look;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;

public final class RlMaterials {
	private RlMaterials() {
	}

	public static RlMaterial resolve(Materials m, String name) {
		List<MaterialDef> chain = m.chain(name);
		if (chain.isEmpty()) {
			throw new IllegalArgumentException("unknown material " + name);
		}
		MaterialDef last = chain.getLast();
		if (last.parent() != null) {
			String names = chain.stream().map(MaterialDef::name).collect(Collectors.joining(" -> "));
			String why = m.materials().containsKey(last.parent()) ? "a cycle" : "a missing parent";
			throw new IllegalArgumentException("material " + name + ": " + why + " in its chain " + names + " -> "
					+ last.parent());
		}
		Map<String, Float> scalars = new LinkedHashMap<>();
		Map<String, float[]> vectors = new LinkedHashMap<>();
		Map<String, String> textures = new LinkedHashMap<>();
		Map<String, Boolean> switches = new LinkedHashMap<>();
		for (MaterialDef d : chain) {
			d.scalars().forEach(scalars::putIfAbsent);
			d.vectors().forEach((k, v) -> vectors.putIfAbsent(k, v.clone()));
			d.textures().forEach(textures::putIfAbsent);
			d.switches().forEach(switches::putIfAbsent);
		}
		MaterialDef.Defaults defaults = last.defaults();
		if (defaults != null) {
			defaults.scalars().forEach(scalars::putIfAbsent);
			defaults.vectors().forEach((k, v) -> vectors.putIfAbsent(k, v.clone()));
			defaults.textures().forEach(textures::putIfAbsent);
			defaults.switches().forEach(switches::putIfAbsent);
		}
		return new RlMaterial(chain.getFirst().name(), Map.copyOf(scalars), Map.copyOf(vectors), Map.copyOf(textures),
				Map.copyOf(switches));
	}

	public static RlMaterial withFinish(RlMaterial body) {
		Map<String, Float> scalars = new HashMap<>(body.scalars());
		scalars.put("F1Type", RlLook.FINISH_TYPE);
		scalars.put("F2Type", RlLook.FINISH_TYPE);
		Map<String, float[]> vectors = new HashMap<>(body.vectors());
		vectors.put("F1ControlA", RlLook.FINISH_CONTROL_A.clone());
		vectors.put("F2ControlA", RlLook.FINISH_CONTROL_A.clone());
		vectors.put("F1ControlB", RlLook.FINISH_CONTROL_B.clone());
		vectors.put("F2ControlB", RlLook.FINISH_CONTROL_B.clone());
		Map<String, String> textures = new HashMap<>(body.textures());
		textures.put("F1DetailNormal", RlLook.FINISH_DETAIL_NORMAL);
		textures.put("F2DetailNormal", RlLook.FINISH_DETAIL_NORMAL);
		return new RlMaterial(body.name(), Map.copyOf(scalars), Map.copyOf(vectors), Map.copyOf(textures),
				body.switches());
	}
}
