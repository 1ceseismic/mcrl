package dev.mcrl.client.fx.particle;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class BlockCollider implements FxCollider {
	private final BlockGetter level;

	public BlockCollider(BlockGetter level) {
		this.level = level;
	}

	@Override
	public @Nullable Hit clip(Vec3 from, Vec3 to) {
		BlockHitResult r = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				CollisionContext.empty()));
		if (r.getType() == HitResult.Type.MISS) {
			return null;
		}
		return new Hit(r.getLocation(), r.getDirection().getUnitVec3());
	}
}
