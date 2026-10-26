package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.BARS_Z;
import static dev.mcrl.client.hud.MeterStyle.CAP_RATIO;
import static dev.mcrl.client.hud.MeterStyle.GLOW_SIZE;
import static dev.mcrl.client.hud.MeterStyle.LABEL_SIZE;
import static dev.mcrl.client.hud.MeterStyle.LABEL_TOP;
import static dev.mcrl.client.hud.MeterStyle.LABEL_Z;
import static dev.mcrl.client.hud.MeterStyle.MC_GLYPH_HEIGHT;
import static dev.mcrl.client.hud.MeterStyle.NUMBER_SIZE;
import static dev.mcrl.client.hud.MeterStyle.RING_OUTER;
import static dev.mcrl.client.hud.MeterStyle.RING_SIZE;
import static dev.mcrl.client.hud.MeterStyle.TEXT_BOX_CENTRE_X;
import static dev.mcrl.client.hud.MeterStyle.TEXT_BOX_CENTRE_Y;
import static dev.mcrl.client.hud.MeterStyle.TEXT_GUTTER;
import static dev.mcrl.client.hud.MeterStyle.TEXT_Z;

import org.joml.Matrix3x2f;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.hud.MeterTextures.Layer;
import dev.mcrl.client.mixin.GuiGraphicsExtractorAccessor;

final class BoostMeterHud {
	private static final BoostMeterModel MODEL = new BoostMeterModel();
	private static final MeterTextures TEXTURES = new MeterTextures();
	private static final int WHITE = 0xFFFFFFFF;
	private static final MeterGeometry.Tris RING = MeterGeometry.square(RING_SIZE);
	private static final double TOP = projectedTop();

	private BoostMeterHud() {
	}

	static void newRide() {
		MODEL.reset();
	}

	static MeterTextures textures() {
		return TEXTURES;
	}

	static double extract(GuiGraphicsExtractor g, Minecraft mc, float boost, MeterTeam team, double now) {
		MODEL.update(Math.round(boost), team, now);
		Window w = mc.getWindow();
		double gui = w.getGuiScale();
		double[] c = HudLayout.meterCentre(w.getWidth(), w.getHeight());
		double[] centre = { c[0] / gui, c[1] / gui };
		double scale = HudLayout.contentScale(w.getWidth(), w.getHeight()) / gui;
		GuiRenderState rs = ((GuiGraphicsExtractorAccessor) g).mcrl$guiRenderState();

		draw(rs, g, Layer.BACKGROUND, MODEL.background(), RING, 0, centre, scale);
		draw(rs, g, Layer.GLOW, MODEL.glow(now), MeterGeometry.square(GLOW_SIZE * MODEL.glowScale(now)), 0, centre,
				scale);
		MeterGeometry.Tris bars = MODEL.full() ? RING : MeterGeometry.wedge(MODEL.wedgeDeg());
		if (bars.count() > 0) {
			draw(rs, g, Layer.TINT, MODEL.tint(now), bars, BARS_Z, centre, scale);
			draw(rs, g, Layer.FILL, MODEL.fill(now), bars, BARS_Z, centre, scale);
		}
		Font font = mc.font;
		text(g, font, Component.literal(MODEL.text()), TEXT_BOX_CENTRE_X, TEXT_BOX_CENTRE_Y, TEXT_Z, NUMBER_SIZE, true,
				MODEL.textArgb(now), true, centre, scale);
		text(g, font, Component.translatable("mcrl.hud.boost"), 0, LABEL_TOP + TEXT_GUTTER, LABEL_Z, LABEL_SIZE, false,
				MODEL.labelArgb(now), false, centre, scale);
		return centre[1] + scale * TOP;
	}

	private static void draw(GuiRenderState rs, GuiGraphicsExtractor g, Layer l, ColorTransform ct,
			MeterGeometry.Tris t, double z, double[] centre, double scale) {
		rs.addGuiElement(GuiTris.of(TEXTURES.setup(l, ct), g.pose(), MeterGeometry.toScreen(t, z, centre, scale), WHITE));
	}

	private static void text(GuiGraphicsExtractor g, Font font, Component s, double ax, double ay, double z, double size,
			boolean middle, int argb, boolean shadow, double[] centre, double scale) {
		if (argb >>> 24 == 0) {
			return;
		}
		float[] a = MeterGeometry.textAffine(ax, ay, z);
		float k = (float) (size * CAP_RATIO / MC_GLYPH_HEIGHT);
		Matrix3x2f m = new Matrix3x2f().translate((float) centre[0], (float) centre[1]).scale((float) scale)
				.mul(new Matrix3x2f(a[0], a[3], a[1], a[4], a[2], a[5])).translate((float) ax, (float) ay).scale(k)
				.translate(-font.width(s) / 2f, middle ? (float) -MC_GLYPH_HEIGHT / 2 : 0);
		g.pose().pushMatrix();
		g.pose().mul(m);
		g.text(font, s, 0, 0, argb, shadow);
		g.pose().popMatrix();
	}

	private static double projectedTop() {
		double top = 0;
		for (int i = 0; i < 64; i++) {
			double th = i * Math.PI / 32;
			top = Math.min(top, MeterGeometry.project(RING_OUTER * Math.cos(th), RING_OUTER * Math.sin(th), 0)[1]);
		}
		return top;
	}
}
