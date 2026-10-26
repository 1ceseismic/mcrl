package dev.mcrl.client.fx.render;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import net.minecraft.resources.Identifier;

import dev.mcrl.client.fx.particle.EmitterInstance;
import dev.mcrl.client.fx.particle.FxDefs;
import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;
import dev.mcrl.client.fx.particle.FxEmitterDef.Kind;
import dev.mcrl.client.fx.particle.MeshData;
import dev.mcrl.client.rl.MipChain;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlTextures;
import dev.mcrl.rlassets.model.MaterialDef;
import dev.mcrl.rlassets.model.Materials;
import dev.mcrl.rlassets.model.TextureInfo;

public final class FxMaterial {
	public record Texture(Identifier id, boolean colorSrgb) {
	}

	public record Bound(FxRecipe recipe, Identifier[] textures, float[] srgb, float[] params) {
	}

	private static final Map<String, Blend> BLENDS = new HashMap<>();
	private static @Nullable RlCache blendsFor;
	private static final Bound UNTEXTURED = new Bound(FxRecipe.UNTEXTURED, new Identifier[0], new float[4],
			new float[16]);
	private static final Map<String, Bound> BOUND = new HashMap<>();
	private static final Map<String, FxRecipe> RECIPES = new HashMap<>();
	private static @Nullable RlCache boundFor;

	private FxMaterial() {
	}

	public static @Nullable Texture texture(@Nullable RlCache rl, String material) {
		if (rl == null) {
			return null;
		}
		String name = sampledTexture(rl.materials(), material);
		TextureInfo info = name == null ? null : rl.textureInfo(name);
		if (info == null) {
			return null;
		}
		CompletableFuture<Identifier> f = RlTextures.request(name);
		if (!f.isDone() || f.isCompletedExceptionally()) {
			return null;
		}
		return new Texture(f.join(), RlTextures.kindOf(info) == MipChain.Kind.COLOR_SRGB);
	}

	public static String materialOf(@Nullable RlCache rl, EmitterInstance e) {
		if (e.def().typeData().kind() == Kind.MESH && rl != null) {
			String override = MeshData.material(e);
			if (override != null) {
				return override;
			}
		}
		return e.def().required().material();
	}

	public static Bound bind(@Nullable RlCache rl, String material) {
		if (rl == null) {
			return UNTEXTURED;
		}
		if (boundFor != rl) {
			boundFor = rl;
			BOUND.clear();
			RECIPES.clear();
		}
		Bound b = BOUND.get(material);
		if (b != null) {
			return b;
		}
		FxRecipe recipe = RECIPES.get(material);
		if (recipe == null) {
			recipe = FxRecipe.of(rl.materials(), material);
			RECIPES.put(material, recipe);
		}
		String[] names = recipe.textures;
		Identifier[] ids = new Identifier[names.length];
		float[] srgb = new float[4];
		for (int i = 0; i < names.length; i++) {
			String name = names[i].startsWith("param:") ? rl.materials().texture(material, names[i].substring(6))
					: names[i];
			TextureInfo info = name == null ? null : rl.textureInfo(name);
			if (info == null) {
				BOUND.put(material, UNTEXTURED);
				return UNTEXTURED;
			}
			CompletableFuture<Identifier> f = RlTextures.request(name);
			if (f.isCompletedExceptionally()) {
				BOUND.put(material, UNTEXTURED);
				return UNTEXTURED;
			}
			if (!f.isDone()) {
				return UNTEXTURED;
			}
			ids[i] = f.join();
			srgb[i] = RlTextures.kindOf(info) == MipChain.Kind.COLOR_SRGB ? 1 : 0;
		}
		b = recipe == FxRecipe.UNTEXTURED ? UNTEXTURED
				: new Bound(recipe, ids, srgb, FxRecipes.params(recipe, rl.materials(), material));
		BOUND.put(material, b);
		return b;
	}

	public static Blend blend(@Nullable RlCache rl, EmitterInstance e) {
		if (e.def().typeData().kind() == Kind.MESH && rl != null) {
			String override = MeshData.material(e);
			if (override != null) {
				if (blendsFor != rl) {
					blendsFor = rl;
					BLENDS.clear();
				}
				Blend b = BLENDS.get(override);
				if (b == null) {
					b = FxDefs.blend(rl.materials(), override);
					BLENDS.put(override, b);
				}
				return b;
			}
		}
		return e.def().required().blend();
	}

	private static @Nullable String sampledTexture(Materials materials, String material) {
		List<MaterialDef> chain = materials.chain(material);
		for (MaterialDef d : chain) {
			if (!d.textures().isEmpty()) {
				return d.textures().values().iterator().next();
			}
		}
		for (MaterialDef d : chain) {
			if (!d.shaderTextures().isEmpty()) {
				return d.shaderTextures().get(0);
			}
		}
		return null;
	}
}
