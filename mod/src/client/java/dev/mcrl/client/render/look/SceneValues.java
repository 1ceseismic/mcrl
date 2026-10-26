package dev.mcrl.client.render.look;

import org.joml.Vector3f;

public record SceneValues(Vector3f sunDir, Vector3f sunColor, Vector3f upper, Vector3f lower, boolean lowerIsGround,
		Vector3f ambient, float skyFactor, Vector3f cubeSky, float timeFrac, float timeFrac8) {
}
