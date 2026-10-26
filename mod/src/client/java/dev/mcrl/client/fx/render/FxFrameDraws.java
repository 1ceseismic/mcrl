package dev.mcrl.client.fx.render;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;

import dev.mcrl.client.fx.particle.FxEmitterDef.Blend;
import dev.mcrl.client.render.look.RlDisplay;

public final class FxFrameDraws {
	private final FxVertices vertices = new FxVertices();
	private final List<FxSubmit.Draw> blended = new ArrayList<>(), masked = new ArrayList<>();
	private final List<FxSubmit.Draw> pool = new ArrayList<>();
	private int used;
	private final EnumSet<FxRecipe> recipes = EnumSet.noneOf(FxRecipe.class);
	private float time;
	private final float[] sky = new float[3];

	public void begin(double simSeconds, float skyR, float skyG, float skyB) {
		clear();
		time = (float) (simSeconds - 3600 * Math.floor(simSeconds / 3600));
		sky[0] = skyR;
		sky[1] = skyG;
		sky[2] = skyB;
	}

	public void clear() {
		vertices.clear();
		blended.clear();
		masked.clear();
		recipes.clear();
		used = 0;
	}

	public FxVertices vertices() {
		return vertices;
	}

	public void add(FxMaterial.Bound bound, Blend blend, @Nullable FxSkin skin, int first, float originUu) {
		int n = vertices.count() - first;
		if (n <= 0) {
			return;
		}
		if (used == pool.size()) {
			pool.add(new FxSubmit.Draw());
		}
		FxSubmit.Draw d = pool.get(used++);
		d.key = FxPipelines.Key.of(bound.recipe(), blend, skin != null);
		Identifier[] textures = bound.textures();
		System.arraycopy(textures, 0, d.textures, 0, textures.length);
		if (skin != null) {
			d.textures[textures.length] = skin.texture();
		}
		float[] block = d.block;
		System.arraycopy(bound.params(), 0, block, 0, 16);
		int rgb = skin == null ? 0 : skin.rgb();
		block[16] = skin == null ? 0 : (float) RlDisplay.srgbDecode((rgb >> 16 & 0xFF) / 255.0);
		block[17] = skin == null ? 0 : (float) RlDisplay.srgbDecode((rgb >> 8 & 0xFF) / 255.0);
		block[18] = skin == null ? 0 : (float) RlDisplay.srgbDecode((rgb & 0xFF) / 255.0);
		block[19] = 0;
		System.arraycopy(bound.srgb(), 0, block, 20, 4);
		block[24] = time;
		block[25] = originUu;
		block[26] = 0;
		block[27] = 0;
		System.arraycopy(sky, 0, block, 28, 3);
		block[31] = 0;
		d.first = first;
		d.count = n;
		(blend == Blend.MASKED ? masked : blended).add(d);
		recipes.add(bound.recipe());
	}

	public Set<FxRecipe> recipes() {
		return recipes.clone();
	}

	public int draws() {
		return blended.size() + masked.size();
	}

	public void submit(SubmitNodeCollector c) {
		int all = draws();
		if (!masked.isEmpty()) {
			c.submitCustom(SubmitRenderPhases.SOLID, new FxSubmit(vertices, masked, all));
		}
		if (!blended.isEmpty()) {
			c.submitCustom(SubmitRenderPhases.AFTER_TERRAIN, new FxSubmit(vertices, blended, all));
		}
	}
}
