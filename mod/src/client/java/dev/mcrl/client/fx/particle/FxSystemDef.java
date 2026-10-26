package dev.mcrl.client.fx.particle;

import java.util.List;

public record FxSystemDef(String path, List<FxEmitterDef> emitters) {
	public FxSystemDef {
		emitters = List.copyOf(emitters);
	}
}
