package dev.mcrl.client.render.look;

import org.joml.Matrix4dc;
import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;

public final class LightProbes {
	private LightProbes() {
	}

	private static void sample(BlockAndLightGetter level, BlockPos.MutableBlockPos p, double x, double y, double z,
			float[] out, int off) {
		int x0 = (int) Math.floor(x - 0.5);
		int y0 = (int) Math.floor(y - 0.5);
		int z0 = (int) Math.floor(z - 0.5);
		double fx = x - 0.5 - x0;
		double fy = y - 0.5 - y0;
		double fz = z - 0.5 - z0;
		double weight = 0, block = 0, sky = 0;
		for (int i = 0; i < 8; i++) {
			int cx = x0 + (i & 1);
			int cy = y0 + (i >> 1 & 1);
			int cz = z0 + (i >> 2 & 1);
			double w = ((i & 1) != 0 ? fx : 1 - fx) * ((i & 2) != 0 ? fy : 1 - fy) * ((i & 4) != 0 ? fz : 1 - fz);
			if (w <= 0 || level.getBlockState(p.set(cx, cy, cz)).isSolidRender()) {
				continue;
			}
			weight += w;
			block += w * level.getBrightness(LightLayer.BLOCK, p);
			sky += w * level.getBrightness(LightLayer.SKY, p);
		}
		if (weight > 0) {
			out[off] = (float) (block / weight);
			out[off + 1] = (float) (sky / weight);
		} else {
			int ax = (int) Math.floor(x);
			int ay = (int) Math.floor(y + 1);
			int az = (int) Math.floor(z);
			out[off] = level.getBrightness(LightLayer.BLOCK, p.set(ax, ay, az));
			out[off + 1] = level.getBrightness(LightLayer.SKY, p);
		}
	}

	public static void box(BlockAndLightGetter level, Matrix4dc localToWorld, AABB localBox, float[] out16) {
		BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
		Vector3d p = new Vector3d();
		for (int i = 0; i < 8; i++) {
			p.set((i & 1) != 0 ? localBox.maxX : localBox.minX, (i & 2) != 0 ? localBox.maxY : localBox.minY,
					(i & 4) != 0 ? localBox.maxZ : localBox.minZ);
			localToWorld.transformPosition(p);
			sample(level, at, p.x, p.y, p.z, out16, 2 * i);
		}
	}

	public static void point(BlockAndLightGetter level, double x, double y, double z, float[] out16) {
		sample(level, new BlockPos.MutableBlockPos(), x, y, z, out16, 0);
		for (int i = 1; i < 8; i++) {
			out16[2 * i] = out16[0];
			out16[2 * i + 1] = out16[1];
		}
	}
}
