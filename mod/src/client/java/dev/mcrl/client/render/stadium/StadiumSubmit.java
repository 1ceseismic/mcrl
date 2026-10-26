package dev.mcrl.client.render.stadium;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.renderer.texture.AbstractTexture;

import dev.mcrl.client.render.look.RlGpuMesh;
import dev.mcrl.client.render.stadium.StadiumScene.Pass;

public record StadiumSubmit(float[] lights, List<Draw> draws) implements SubmitNode {
	private static final float[] NONE = new float[4];

	public record Draw(Pass pass, RlGpuMesh mesh, float[] model, float[] color, @Nullable AbstractTexture texture,
			float[] mode, float[] mask) {
		public Draw(Pass pass, RlGpuMesh mesh, float[] model, float[] color) {
			this(pass, mesh, model, color, null, NONE, NONE);
		}
	}

	@Override
	public FeatureRendererType<StadiumSubmit> featureType() {
		return StadiumRenderer.TYPE;
	}
}
