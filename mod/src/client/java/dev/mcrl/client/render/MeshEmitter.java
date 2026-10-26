package dev.mcrl.client.render;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.textures.FilterMode;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;

import dev.mcrl.Mcrl;
import dev.mcrl.client.render.look.CarShadow;

public final class MeshEmitter {
	public static final Identifier WHITE = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "textures/white");
	public static final Identifier BOOST_GRADIENT = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "textures/boost_gradient");

	private static @Nullable RenderType renderType;
	private static @Nullable RenderType flameRenderType;

	private MeshEmitter() {
	}

	public static void registerTextures(Minecraft mc) {
		NativeImage image = new NativeImage(1, 1, false);
		image.setPixel(0, 0, 0xFFFFFFFF);
		mc.getTextureManager().register(WHITE, new DynamicTexture(() -> "mcrl white", image));
		NativeImage gradient = new NativeImage(BoostFlame.GRADIENT_WIDTH, 1, false);
		for (int i = 0; i < BoostFlame.GRADIENT_WIDTH; i++) {
			gradient.setPixel(i, 0, BoostFlame.gradientArgb(i, BoostFlame.GRADIENT_WIDTH));
		}
		mc.getTextureManager().register(BOOST_GRADIENT, new ClampedTexture("mcrl boost gradient", gradient));
		mc.getTextureManager().register(CarShadow.TEXTURE, new ClampedTexture("mcrl car shadow", CarShadow.image()));
	}

	public static RenderType renderType() {
		RenderType t = renderType;
		if (t == null) {
			t = RenderTypes.entitySolid(WHITE);
			renderType = t;
		}
		return t;
	}

	public static RenderType flameRenderType() {
		RenderType t = flameRenderType;
		if (t == null) {
			t = RenderTypes.energySwirl(BOOST_GRADIENT, 0f, 0f);
			flameRenderType = t;
		}
		return t;
	}

	public static void emitFlame(PoseStack.Pose pose, VertexConsumer vc, float[] tris, float length, float lengthScale,
			int argb) {
		int v = SimpleModels.FLOATS_PER_VERTEX;
		for (int t = 0; t + 3 * v <= tris.length; t += 3 * v) {
			flameVertex(pose, vc, tris, t, length, lengthScale, argb);
			flameVertex(pose, vc, tris, t + v, length, lengthScale, argb);
			flameVertex(pose, vc, tris, t + 2 * v, length, lengthScale, argb);
			flameVertex(pose, vc, tris, t + 2 * v, length, lengthScale, argb);
		}
	}

	private static void flameVertex(PoseStack.Pose pose, VertexConsumer vc, float[] d, int i, float length,
			float lengthScale, int argb) {
		float u = Math.max(0f, Math.min(1f, -d[i] / length));
		vc.addVertex(pose, d[i] * lengthScale, d[i + 1], d[i + 2])
				.setColor(argb)
				.setUv(u, 0.5f)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(LightCoordsUtil.FULL_BRIGHT)
				.setNormal(pose, d[i + 3], d[i + 4], d[i + 5]);
	}

	public static void emitTriangles(PoseStack.Pose pose, VertexConsumer vc, float[] tris, int light) {
		int v = SimpleModels.FLOATS_PER_VERTEX;
		for (int t = 0; t + 3 * v <= tris.length; t += 3 * v) {
			vertex(pose, vc, tris, t, light);
			vertex(pose, vc, tris, t + v, light);
			vertex(pose, vc, tris, t + 2 * v, light);
			vertex(pose, vc, tris, t + 2 * v, light);
		}
	}

	private static void vertex(PoseStack.Pose pose, VertexConsumer vc, float[] d, int i, int light) {
		int argb = 0xFF000000 | channel(d[i + 6]) << 16 | channel(d[i + 7]) << 8 | channel(d[i + 8]);
		vc.addVertex(pose, d[i], d[i + 1], d[i + 2])
				.setColor(argb)
				.setUv(0f, 0f)
				.setOverlay(OverlayTexture.NO_OVERLAY)
				.setLight(light)
				.setNormal(pose, d[i + 3], d[i + 4], d[i + 5]);
	}

	private static int channel(float c) {
		return Math.round(Math.max(0f, Math.min(1f, c)) * 255f);
	}

	private static final class ClampedTexture extends DynamicTexture {
		ClampedTexture(String label, NativeImage image) {
			super(() -> label, image);
			sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}
}
