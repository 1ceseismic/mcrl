package dev.mcrl.client.fx.effects;

import java.util.ArrayList;
import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.fx.render.FxGeometry;
import dev.mcrl.rlassets.model.AttachmentDef;

public final class GroundReticle implements FxSinks.ActorSink {
	private static final double HALF = FxConstants.RETICLE_SIZE_UU / 2 / 50;
	private static final double LIFT = 0.002;

	public record Quad(double x0, double z0, double x1, double z1, double y, float u0, float v0, float u1, float v1,
			float distUu) {
	}

	private final ActorEffects.Registry effects;

	public GroundReticle(ActorEffects.Registry effects) {
		this.effects = effects;
	}

	@Override
	public void activate(FxActorRef r, AttachmentDef a) {
		effects.get(r).attachStatic(r, a, Group.BALL, new Vector3f(), new Quaternionf());
	}

	@Override
	public boolean deactivate(FxActorRef r, AttachmentDef a) {
		ActorEffects fx = effects.existing(r);
		if (fx != null) {
			fx.detach(r, a);
		}
		return true;
	}

	public static List<Quad> place(double bx, double by, double bz, BlockGetter level) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		List<Quad> out = new ArrayList<>();
		double size = 2 * HALF;
		double x0 = bx - HALF, z0 = bz - HALF, x1 = bx + HALF, z1 = bz + HALF;
		double near = by - FxConstants.RETICLE_NEAR_UU / 50, far = by - FxConstants.RETICLE_FAR_UU / 50;
		for (int cx = (int) Math.floor(x0); cx < x1; cx++) {
			for (int cz = (int) Math.floor(z0); cz < z1; cz++) {
				for (int y = (int) Math.floor(near); y >= Math.floor(far); y--) {
					VoxelShape shape = level.getBlockState(pos.set(cx, y, cz)).getCollisionShape(level, pos);
					if (shape.isEmpty()) {
						continue;
					}
					double top = y + shape.max(Direction.Axis.Y);
					if (top > near) {
						continue;
					}
					if (top < far) {
						break;
					}
					double qx0 = Math.max(x0, cx), qz0 = Math.max(z0, cz);
					double qx1 = Math.min(x1, cx + 1), qz1 = Math.min(z1, cz + 1);
					out.add(new Quad(qx0, qz0, qx1, qz1, top, (float) ((qx0 - x0) / size), (float) ((qz0 - z0) / size),
							(float) ((qx1 - x0) / size), (float) ((qz1 - z0) / size), (float) ((by - top) * 50)));
					break;
				}
			}
		}
		return out;
	}

	public static float altitude(double bx, double bz, List<Quad> quads) {
		for (Quad q : quads) {
			if (bx >= q.x0() && bx < q.x1() && bz >= q.z0() && bz < q.z1()) {
				return q.distUu();
			}
		}
		return FxConstants.RETICLE_DEFAULT_ALTITUDE;
	}

	public static void vertices(List<Quad> quads, float altitude, double cx, double cy, double cz,
			FxGeometry.Sink out) {
		int s = byteOf(0.125f + 0.8375f * sat(altitude * 0.000977f));
		for (Quad q : quads) {
			int argb = 0xFF000000 | s << 16 | byteOf(fade(q.distUu())) << 8;
			float y = (float) (q.y() + LIFT - cy);
			float x0 = (float) (q.x0() - cx), x1 = (float) (q.x1() - cx), z0 = (float) (q.z0() - cz),
					z1 = (float) (q.z1() - cz);
			out.vertex(x0, y, z0, q.u0(), q.v0(), argb, FxGeometry.FULL_BRIGHT, 0, 1, 0);
			out.vertex(x0, y, z1, q.u0(), q.v1(), argb, FxGeometry.FULL_BRIGHT, 0, 1, 0);
			out.vertex(x1, y, z1, q.u1(), q.v1(), argb, FxGeometry.FULL_BRIGHT, 0, 1, 0);
			out.vertex(x1, y, z0, q.u1(), q.v0(), argb, FxGeometry.FULL_BRIGHT, 0, 1, 0);
		}
	}

	private static float fade(float distUu) {
		float far = (float) FxConstants.RETICLE_FAR_UU;
		return sat(5 * (far - Math.abs(distUu)) / far);
	}

	private static float sat(float x) {
		return x < 0 ? 0 : x > 1 ? 1 : x;
	}

	private static int byteOf(float v) {
		return Math.round(Math.clamp(v, 0, 1) * 255);
	}
}
