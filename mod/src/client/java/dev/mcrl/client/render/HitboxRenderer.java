package dev.mcrl.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;

import dev.mcrl.sim.HitboxPreset;

final class HitboxRenderer {
	private static final int BOX_ARGB = 0xFFFFFFFF;
	private static final int CONTACT_ARGB = 0xFF40FF60;
	private static final int AIR_ARGB = 0xFFFF5030;
	private static final int CIRCLE_SEGMENTS = 24;

	private HitboxRenderer() {
	}

	private static float[] box(HitboxPreset h) {
		return new float[] {(float) h.offsetForward(), (float) h.offsetUp(), 0f, (float) h.halfForward(),
			(float) h.halfUp(), (float) h.halfRight()};
	}

	static void submitCar(CarRenderer.CarRenderState s, PoseStack ps, SubmitNodeCollector collector) {
		float width = Minecraft.getInstance().getWindow().getAppropriateLineWidth();
		float[] box = box(s.preset);
		collector.submitCustomGeometry(ps, RenderTypes.lines(), (pose, vc) -> box(pose, vc, box, width));
		for (int i = 0; i < 4; i++) {
			float r = (float) (i < 2 ? s.preset.front() : s.preset.back()).wheelRadius();
			int argb = (s.wheelContact & 1 << i) != 0 ? CONTACT_ARGB : AIR_ARGB;
			ps.pushPose();
			ps.translate(s.wheelLocal[3 * i], s.wheelLocal[3 * i + 1], s.wheelLocal[3 * i + 2]);
			ps.rotate(CarRenderer.wheelRotation(i, s));
			collector.submitCustomGeometry(ps, RenderTypes.lines(), (pose, vc) -> wheel(pose, vc, r, argb, width));
			ps.popPose();
		}
	}

	private static void box(PoseStack.Pose pose, VertexConsumer vc, float[] box, float width) {
		for (int axis = 0; axis < 3; axis++) {
			int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
			for (int s1 = -1; s1 <= 1; s1 += 2) {
				for (int s2 = -1; s2 <= 1; s2 += 2) {
					float[] p = {box[0], box[1], box[2]}, q = {box[0], box[1], box[2]};
					p[a1] += s1 * box[3 + a1];
					p[a2] += s2 * box[3 + a2];
					q[a1] = p[a1];
					q[a2] = p[a2];
					p[axis] -= box[3 + axis];
					q[axis] += box[3 + axis];
					line(pose, vc, p[0], p[1], p[2], q[0], q[1], q[2], BOX_ARGB, width);
				}
			}
		}
	}

	private static void wheel(PoseStack.Pose pose, VertexConsumer vc, float r, int argb, float width) {
		for (int k = 0; k < CIRCLE_SEGMENTS; k++) {
			double a0 = 2 * Math.PI * k / CIRCLE_SEGMENTS, a1 = 2 * Math.PI * (k + 1) / CIRCLE_SEGMENTS;
			line(pose, vc, (float) (r * Math.cos(a0)), (float) (r * Math.sin(a0)), 0f,
					(float) (r * Math.cos(a1)), (float) (r * Math.sin(a1)), 0f, argb, width);
		}
		line(pose, vc, 0f, 0f, 0f, r, 0f, 0f, argb, width);
	}

	private static void line(PoseStack.Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1,
			float z1, int argb, float width) {
		float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
		float n = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		dx /= n;
		dy /= n;
		dz /= n;
		vc.addVertex(pose, x0, y0, z0).setColor(argb).setNormal(pose, dx, dy, dz).setLineWidth(width);
		vc.addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, dx, dy, dz).setLineWidth(width);
	}
}
