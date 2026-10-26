package dev.mcrl.client.fx.render;

import java.util.List;

import net.minecraft.client.renderer.feature.FeatureRendererType;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.resources.Identifier;

import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;

public record FxSubmit(FxVertices vertices, List<Draw> draws, int frameDraws) implements SubmitNode {
	public static final class Draw {
		static final int MAX_TEXTURES = maxTextures() + 1;

		FxPipelines.Key key = FxPipelines.Key.of(FxRecipe.UNTEXTURED, Blend.ADDITIVE, false);
		final Identifier[] textures = new Identifier[MAX_TEXTURES];
		final float[] block = new float[FxPipelines.BLOCK_SIZE / 4];
		int first, count;
		int vertexOffset, blockOffset;

		public FxPipelines.Key key() {
			return key;
		}

		public Identifier[] textures() {
			return textures;
		}

		public float[] block() {
			return block;
		}

		public int first() {
			return first;
		}

		public int count() {
			return count;
		}

		private static int maxTextures() {
			int n = 0;
			for (FxRecipe r : FxRecipe.values()) {
				n = Math.max(n, r.textures.length);
			}
			return n;
		}
	}

	@Override
	public FeatureRendererType<FxSubmit> featureType() {
		return FxFeatureRenderer.TYPE;
	}
}
