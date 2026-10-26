package dev.mcrl.entity;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import dev.mcrl.math.OrientedBox;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.HitboxPreset;

public final class SpawnPlacer {
	static final double[] AHEAD = {3.0, 2.0, 1.0};
	// RocketSim's car rest height, 17 UU.
	public static final double REST_HEIGHT = 0.34;
	static final double TOUCH = 1e-5;

	private SpawnPlacer() {
	}

	public static Optional<Pose> place(ServerLevel level, Player player, HitboxPreset preset) {
		float yaw = player.getYRot();
		double rad = Math.toRadians(yaw);
		double fx = -Math.sin(rad), fz = Math.cos(rad);
		for (double d : AHEAD) {
			for (int dy = 0; dy <= 1; dy++) {
				Pose p = Pose.upright(player.getX() + fx * d, player.getY() + dy + REST_HEIGHT, player.getZ() + fz * d, yaw);
				if (!overlapsBlocks(level, OrientedBox.car(p, preset))) {
					return Optional.of(p);
				}
			}
		}
		return Optional.empty();
	}

	public static boolean overlapsBlocks(BlockGetter level, OrientedBox box) {
		double[] bb = box.aabb();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int x = Mth.floor(bb[0]) - 1; x <= Mth.floor(bb[3]) + 1; x++) {
			for (int y = Mth.floor(bb[1]) - 1; y <= Mth.floor(bb[4]); y++) {
				for (int z = Mth.floor(bb[2]) - 1; z <= Mth.floor(bb[5]) + 1; z++) {
					m.set(x, y, z);
					BlockState state = level.getBlockState(m);
					if (state.isAir()) {
						continue;
					}
					VoxelShape shape = state.getCollisionShape(level, m);
					if (shape.isEmpty()) {
						continue;
					}
					for (AABB a : shape.toAabbs()) {
						if (box.intersects(x + a.minX + TOUCH, y + a.minY + TOUCH, z + a.minZ + TOUCH, x + a.maxX - TOUCH,
								y + a.maxY - TOUCH, z + a.maxZ - TOUCH)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
