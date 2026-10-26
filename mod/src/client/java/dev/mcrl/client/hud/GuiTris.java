package dev.mcrl.client.hud;

import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fc;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

record GuiTris(TextureSetup textureSetup, Matrix3x2fc pose, float[] xy, float[] uv, int color,
		ScreenRectangle bounds) implements GuiElementRenderState {

	static GuiTris of(TextureSetup tex, Matrix3x2fc pose, MeterGeometry.Tris t, int color) {
		float[] xy = t.xy();
		float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
		for (int i = 0; i < xy.length; i += 2) {
			minX = Math.min(minX, xy[i]);
			maxX = Math.max(maxX, xy[i]);
			minY = Math.min(minY, xy[i + 1]);
			maxY = Math.max(maxY, xy[i + 1]);
		}
		Matrix3x2f p = new Matrix3x2f(pose);
		ScreenRectangle b = xy.length == 0 ? ScreenRectangle.empty()
				: new ScreenRectangle((int) Math.floor(minX), (int) Math.floor(minY),
						(int) Math.ceil(maxX - Math.floor(minX)) + 1, (int) Math.ceil(maxY - Math.floor(minY)) + 1)
						.transformMaxBounds(p);
		return new GuiTris(tex, p, xy, t.uv(), color, b);
	}

	@Override
	public void buildVertices(VertexConsumer vc) {
		for (int v = 0; v < xy.length / 2; v += 3) {
			vertex(vc, v);
			vertex(vc, v + 1);
			vertex(vc, v + 2);
			vertex(vc, v + 2);
		}
	}

	private void vertex(VertexConsumer vc, int v) {
		vc.addVertexWith2DPose(pose, xy[2 * v], xy[2 * v + 1]).setUv(uv[2 * v], uv[2 * v + 1]).setColor(color);
	}

	@Override
	public RenderPipeline pipeline() {
		return RenderPipelines.GUI_TEXTURED;
	}

	@Override
	public @Nullable ScreenRectangle scissorArea() {
		return null;
	}
}
