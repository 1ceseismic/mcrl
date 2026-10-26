package dev.mcrl.client.fx.render;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.model.Materials;

public final class FxRecipes {
	public static final float FLARE_K = 1;

	private FxRecipes() {
	}

	public static float[] params(FxRecipe recipe, @Nullable Materials materials, String material) {
		float[] p = new float[16];
		switch (recipe) {
			case BOOST_SHEET -> {
				p[0] = scalar(materials, material, "Outer_Speed", 1.5f);
				p[1] = scalar(materials, material, "Inner_Speed", 1);
				p[2] = scalar(materials, material, "Outer_Sparks", 0.2f);
				p[3] = scalar(materials, material, "Inner_Sparks", 0.1f);
				p[4] = scalar(materials, material, "TileX", 2);
				p[5] = scalar(materials, material, "TileY", 2);
				p[6] = scalar(materials, material, "FresnelBase", 3);
				p[7] = scalar(materials, material, "FresnelEnd", 4);
				p[8] = scalar(materials, material, "GradientAmount", 6);
				p[9] = scalar(materials, material, "GradientSharpness", 2);
				p[10] = scalar(materials, material, "Opacity", 1);
				p[11] = scalar(materials, material, "Brightness", 1);
				vector(materials, material, "CustomColor", new float[] {1, 0.20867f, 0.069725f}, p, 12);
			}
			case BOOST_CONE -> {
				p[0] = scalar(materials, material, "PanSpeed", 0.25f);
				p[1] = scalar(materials, material, "UVDistort", 0.1f);
				vector(materials, material, "Color", new float[] {0.0031621f, 0.38717f, 1}, p, 4);
			}
			case DUSTY_FLARE, STANDARD_FLARE -> p[0] = FLARE_K;
			case DIRT_PUFF -> {
				p[0] = scalar(materials, material, "Noise_Scale", 2);
				p[1] = scalar(materials, material, "Pan_Speed", 3);
			}
			default -> {
			}
		}
		return p;
	}

	private static float scalar(@Nullable Materials m, String material, String name, float fallback) {
		Float v = m == null ? null : m.scalar(material, name);
		return v == null ? fallback : v;
	}

	private static void vector(@Nullable Materials m, String material, String name, float[] fallback, float[] out,
			int at) {
		float[] v = m == null ? null : m.vector(material, name);
		System.arraycopy(v == null || v.length < 3 ? fallback : v, 0, out, at, 3);
	}
}
