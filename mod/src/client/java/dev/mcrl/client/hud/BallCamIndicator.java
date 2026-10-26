package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.BALL_CAM_RGB;
import static dev.mcrl.client.hud.MeterStyle.BALL_CAM_SIZE;
import static dev.mcrl.client.hud.MeterStyle.BALL_CAM_TEXT_MIDDLE;
import static dev.mcrl.client.hud.MeterStyle.BALL_CAM_TEXT_X;
import static dev.mcrl.client.hud.MeterStyle.CAP_RATIO;
import static dev.mcrl.client.hud.MeterStyle.DOT_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.DOT_FPS;
import static dev.mcrl.client.hud.MeterStyle.DOT_SIZE;
import static dev.mcrl.client.hud.MeterStyle.FADE_H;
import static dev.mcrl.client.hud.MeterStyle.FADE_W;
import static dev.mcrl.client.hud.MeterStyle.GLOW_ORIGIN_X;
import static dev.mcrl.client.hud.MeterStyle.GLOW_ORIGIN_Y;
import static dev.mcrl.client.hud.MeterStyle.GLOW_SCALE;
import static dev.mcrl.client.hud.MeterStyle.GLOW_TRANSFORM;
import static dev.mcrl.client.hud.MeterStyle.HIDE_SLIDE;
import static dev.mcrl.client.hud.MeterStyle.HIDE_TWEEN;
import static dev.mcrl.client.hud.MeterStyle.HINT_SIZE;
import static dev.mcrl.client.hud.MeterStyle.HINT_TOP;
import static dev.mcrl.client.hud.MeterStyle.MC_GLYPH_HEIGHT;
import static dev.mcrl.client.hud.MeterStyle.SHOW_TWEEN;

import java.util.Locale;

import org.joml.Matrix3x2f;

import com.mojang.blaze3d.platform.Window;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.camera.CameraSettings.BallCamMode;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.hud.MeterTextures.Layer;
import dev.mcrl.client.input.McrlKeys;
import dev.mcrl.client.mixin.GuiGraphicsExtractorAccessor;

final class BallCamIndicator {
	private boolean shown;
	private double tweenStart = Double.NEGATIVE_INFINITY, tweenTime = SHOW_TWEEN, shownAt;
	private double fromAlpha, toAlpha, fromX = -HIDE_SLIDE, toX = -HIDE_SLIDE;

	void update(boolean on, double now) {
		if (on == shown) {
			return;
		}
		fromAlpha = alpha(now);
		fromX = slide(now);
		tweenStart = now;
		if (on) {
			shownAt = now;
			tweenTime = SHOW_TWEEN;
			toAlpha = 1;
			toX = 0;
		} else {
			tweenTime = HIDE_TWEEN;
			toAlpha = 0;
			toX = -HIDE_SLIDE;
		}
		shown = on;
	}

	private float alpha(double now) {
		return (float) (fromAlpha + (toAlpha - fromAlpha) * progress(now));
	}

	private double slide(double now) {
		return fromX + (toX - fromX) * progress(now);
	}

	private float dotAlpha(double now) {
		int frame = (int) Math.floor(Math.max(0, now - shownAt) * DOT_FPS) % DOT_ALPHA.length;
		return (float) DOT_ALPHA[frame];
	}

	private double progress(double now) {
		return Ease.quadOut((now - tweenStart) / tweenTime);
	}

	void extract(GuiGraphicsExtractor g, Minecraft mc, MeterTextures textures, double now) {
		float alpha = alpha(now);
		if (alpha <= 0) {
			return;
		}
		Window w = mc.getWindow();
		double gui = w.getGuiScale();
		double[] o = HudLayout.ballCamIndicator(w.getWidth(), w.getHeight());
		double scale = HudLayout.contentScale(w.getWidth(), w.getHeight()) / gui;
		double ox = o[0] / gui + slide(now) * scale, oy = o[1] / gui;
		GuiRenderState rs = ((GuiGraphicsExtractorAccessor) g).mcrl$guiRenderState();
		int a = Math.round(alpha * 255);
		if (textures.fromCache(Layer.FADE)) {
			rs.addGuiElement(GuiTris.of(textures.setup(Layer.FADE, GLOW_TRANSFORM), g.pose(),
					rect(ox, oy, scale, GLOW_ORIGIN_X * GLOW_SCALE, GLOW_ORIGIN_Y * GLOW_SCALE, FADE_W * GLOW_SCALE,
							FADE_H * GLOW_SCALE),
					a << 24 | 0xFFFFFF));
		}
		int dot = Math.round(alpha * dotAlpha(now) * 255);
		rs.addGuiElement(GuiTris.of(textures.setup(Layer.DOT, ColorTransform.IDENTITY), g.pose(),
				rect(ox, oy, scale, -DOT_SIZE / 2, -DOT_SIZE / 2, DOT_SIZE, DOT_SIZE), dot << 24 | 0xFFFFFF));
		Font font = mc.font;
		String title = Component.translatable("mcrl.hud.ball_cam").getString().toUpperCase(Locale.ROOT);
		text(g, font, title, ox, oy, scale, BALL_CAM_TEXT_X, BALL_CAM_TEXT_MIDDLE, BALL_CAM_SIZE, true,
				a << 24 | BALL_CAM_RGB);
		if (McrlConfig.get().camera.ballCamMode() == BallCamMode.TOGGLE) {
			String key = McrlKeys.BALL_CAM.getTranslatedKeyMessage().getString();
			String hint = Component.translatable("mcrl.hud.ball_cam_hint", key).getString().toUpperCase(Locale.ROOT);
			text(g, font, hint, ox, oy, scale, BALL_CAM_TEXT_X, HINT_TOP, HINT_SIZE, false, a << 24 | 0xFFFFFF);
		}
	}

	private static MeterGeometry.Tris rect(double ox, double oy, double s, double x, double y, double w, double h) {
		float x0 = (float) (ox + x * s), y0 = (float) (oy + y * s), x1 = (float) (ox + (x + w) * s),
				y1 = (float) (oy + (y + h) * s);
		return new MeterGeometry.Tris(new float[] { x0, y0, x0, y1, x1, y1, x0, y0, x1, y1, x1, y0 },
				new float[] { 0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1, 0 });
	}

	private static void text(GuiGraphicsExtractor g, Font font, String s, double ox, double oy, double scale, double x,
			double y, double size, boolean middle, int argb) {
		float k = (float) (size * CAP_RATIO / MC_GLYPH_HEIGHT);
		Matrix3x2f m = new Matrix3x2f().translate((float) (ox + x * scale), (float) (oy + y * scale))
				.scale((float) (scale * k)).translate(0, middle ? (float) -MC_GLYPH_HEIGHT / 2 : 0);
		g.pose().pushMatrix();
		g.pose().mul(m);
		g.text(font, s, 0, 0, argb, false);
		g.pose().popMatrix();
	}
}
