package dev.mcrl.client.render;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import org.joml.Quaternionf;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.mcrl.rlassets.WheelRig;

public final class RlModels {
	private RlModels() {
	}

	public static void wheelTransform(PoseStack ps, WheelRig rig, int i, float offsetUu, float steer, float spin) {
		float dy = offsetUu / (float) UU_PER_BLOCK;
		float[] d = rig.disc()[i], p = rig.pivot()[i];
		if (p != null) {
			ps.translate(p[0], p[1] + dy, p[2]);
			ps.rotate(new Quaternionf().rotationY(steer));
			ps.translate(d[0] - p[0], d[1] - p[1], d[2] - p[2]);
		} else {
			ps.translate(d[0], d[1] + dy, d[2]);
		}
		ps.rotate(new Quaternionf().rotationZ(-spin));
	}
}
