package dev.mcrl.client.render.look;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import dev.mcrl.Mcrl;
import dev.mcrl.math.Pose;

public final class CarShadow {
	public static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "textures/car_shadow");
	public static final int TEXTURE_WIDTH = 64, TEXTURE_HEIGHT = 32;
	private static final double EDGE = 0.12;
	private static final double MIN_AXIS = 0.1;
	// Vanilla shadow reach: 16 blocks.
	private static final double FADE_DISTANCE_SQ = 256;

	private static @Nullable RenderType renderType;

	private CarShadow() {
	}

	public record Piece(int x, int y, int z, float alpha) {
	}

	public record Footprint(double originX, double originZ, double fx, double fz, double rx, double rz, double minA,
			double maxA, double minB, double maxB) {
		public void uv(double x, double z, float[] out) {
			double dx = x - originX, dz = z - originZ;
			double det = fx * rz - rx * fz;
			double a = (dx * rz - rx * dz) / det;
			double b = (fx * dz - dx * fz) / det;
			double s = (a - minA) / (maxA - minA), t = (b - minB) / (maxB - minB);
			out[0] = (float) ((0.5 + s * (TEXTURE_WIDTH - 1)) / TEXTURE_WIDTH);
			out[1] = (float) ((0.5 + t * (TEXTURE_HEIGHT - 1)) / TEXTURE_HEIGHT);
		}
	}

	public static @Nullable Footprint footprint(Pose pose, AABB localBox) {
		Vector3d f = pose.forward(), r = pose.right();
		boolean flat = Math.abs(f.x * r.z - r.x * f.z) < 1e-3;
		if (Math.hypot(f.x, f.z) < MIN_AXIS || Math.hypot(r.x, r.z) < MIN_AXIS || flat) {
			return null;
		}
		return new Footprint(pose.x(), pose.z(), f.x, f.z, r.x, r.z, localBox.minX, localBox.maxX, localBox.minZ,
				localBox.maxZ);
	}

	public static void pieces(Level level, Pose pose, AABB localBox, double distanceToCameraSq, List<Piece> out) {
		out.clear();
		double strength = 1 - distanceToCameraSq / FADE_DISTANCE_SQ;
		Footprint fp = footprint(pose, localBox);
		if (strength <= 0 || fp == null) {
			return;
		}
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
		for (int i = 0; i < 4; i++) {
			double a = (i & 1) != 0 ? fp.maxA : fp.minA, b = (i & 2) != 0 ? fp.maxB : fp.minB;
			double x = fp.originX + fp.fx * a + fp.rx * b, z = fp.originZ + fp.fz * a + fp.rz * b;
			minX = Math.min(minX, x);
			maxX = Math.max(maxX, x);
			minZ = Math.min(minZ, z);
			maxZ = Math.max(maxZ, z);
		}
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		double y = pose.y();
		int y0 = (int) Math.floor(y - Math.min(strength / 0.5 - 1, 1));
		int y1 = (int) Math.floor(y);
		for (int z = (int) Math.floor(minZ); z <= (int) Math.floor(maxZ); z++) {
			for (int x = (int) Math.floor(minX); x <= (int) Math.floor(maxX); x++) {
				for (int py = y0; py <= y1; py++) {
					BlockState below = level.getBlockState(p.set(x, py - 1, z));
					if (below.getRenderShape() == RenderShape.INVISIBLE || !below.isCollisionShapeFullBlock(level, p)
							|| below.getShape(level, p).isEmpty()) {
						continue;
					}
					int raw = level.getMaxLocalRawBrightness(p.set(x, py, z));
					if (raw <= 3) {
						continue;
					}
					float alpha = (float) ((strength - (y - py) * 0.5) * 0.5
							* Lightmap.getBrightness(level.dimensionType(), raw));
					out.add(new Piece(x, py, z, Math.clamp(alpha, 0f, 1f)));
				}
			}
		}
	}

	public static int alpha(int i, int j) {
		double s = (double) i / (TEXTURE_WIDTH - 1), t = (double) j / (TEXTURE_HEIGHT - 1);
		double a = sat(Math.min(s, 1 - s) / EDGE) * sat(Math.min(t, 1 - t) / EDGE);
		return (int) Math.round(255 * a);
	}

	public static NativeImage image() {
		NativeImage image = new NativeImage(TEXTURE_WIDTH, TEXTURE_HEIGHT, false);
		for (int j = 0; j < TEXTURE_HEIGHT; j++) {
			for (int i = 0; i < TEXTURE_WIDTH; i++) {
				image.setPixel(i, j, ARGB.color(alpha(i, j), 0, 0, 0));
			}
		}
		return image;
	}

	public static void submit(List<Piece> pieces, Footprint fp, double originX, double originY, double originZ,
			PoseStack ps, SubmitNodeCollector collector) {
		if (pieces.isEmpty()) {
			return;
		}
		RenderType type = renderType;
		if (type == null) {
			type = RenderTypes.entityShadow(TEXTURE);
			renderType = type;
		}
		collector.submitCustomGeometry(ps, type, (pose, vc) -> {
			float[] uv = new float[2];
			Vector3f at = new Vector3f();
			for (Piece p : pieces) {
				int color = ARGB.white(p.alpha());
				float y = (float) (p.y() - originY);
				vertex(pose.pose(), vc, color, fp, p.x(), y, p.z(), originX, originZ, uv, at);
				vertex(pose.pose(), vc, color, fp, p.x(), y, p.z() + 1, originX, originZ, uv, at);
				vertex(pose.pose(), vc, color, fp, p.x() + 1, y, p.z() + 1, originX, originZ, uv, at);
				vertex(pose.pose(), vc, color, fp, p.x() + 1, y, p.z(), originX, originZ, uv, at);
			}
		});
	}

	private static void vertex(Matrix4f m, VertexConsumer vc, int color, Footprint fp, int x, float y, int z,
			double originX, double originZ, float[] uv, Vector3f at) {
		fp.uv(x, z, uv);
		m.transformPosition((float) (x - originX), y, (float) (z - originZ), at);
		vc.addVertex(at.x(), at.y(), at.z(), color, uv[0], uv[1], OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT,
				0, 1, 0);
	}

	private static double sat(double x) {
		return Math.min(Math.max(x, 0), 1);
	}
}
