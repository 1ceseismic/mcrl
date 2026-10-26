package dev.mcrl.client.render.look;

import org.joml.Matrix4f;

import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

public record RlMeshSubmit(RlLookAssets.Part part, Matrix4f model, Matrix4f probeMat, float[] probes, float[] dyn,
		float[] ground) implements SubmitNode {
	@Override
	public FeatureRendererType<RlMeshSubmit> featureType() {
		return RlMeshFeatureRenderer.TYPE;
	}
}
