package dev.mcrl.client.rl;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import dev.mcrl.client.render.look.RlLook;
import dev.mcrl.rlassets.WheelRig;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.rlassets.bodies.BodyManifest;
import dev.mcrl.rlassets.model.FxActorDef;

public record BodyLook(String id, int preset, Map<String, RlCache.Mesh> meshes, String shellMaterial,
		String chassisMaterial, WheelRig wheels, Map<String, Vector3f> sockets, @Nullable String fxActorPath,
		@Nullable FxActorDef bodyFx, Path dir) {
	private static final String OCTANE_FX_ACTOR = "Body_Octane_SF:Body_Octane.FXActor";

	public BodyLook {
		meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
		Map<String, Vector3f> s = new LinkedHashMap<>();
		sockets.forEach((k, v) -> s.put(k, new Vector3f(v)));
		sockets = Collections.unmodifiableMap(s);
	}

	public RlCache.@Nullable Mesh mesh(String name) {
		return meshes.get(name);
	}

	public @Nullable Vector3f socket(String name) {
		Vector3f v = sockets.get(name);
		return v == null ? null : new Vector3f(v);
	}

	public static BodyLook octane(RlCache rl) {
		Map<String, RlCache.Mesh> meshes = new LinkedHashMap<>();
		for (String name : BodyManifest.MESHES) {
			RlCache.Mesh m = rl.mesh(name);
			if (m != null) {
				meshes.put(name, m);
			}
		}
		RlCache.BaseBody b = rl.baseBody();
		return new BodyLook(BodyIds.OCTANE, b != null ? b.preset() : 0, meshes,
				b != null ? b.shellMaterial() : RlLook.BODY, b != null ? b.chassisMaterial() : RlLook.CHASSIS,
				rl.wheels(), rl.sockets(), b != null ? b.fxActor() : OCTANE_FX_ACTOR, rl.fxActors().get("body"), rl.dir());
	}
}
