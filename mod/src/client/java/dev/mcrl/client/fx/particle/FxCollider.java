package dev.mcrl.client.fx.particle;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

public interface FxCollider {
	FxCollider NONE = (from, to) -> null;

	@Nullable Hit clip(Vec3 from, Vec3 to);

	record Hit(Vec3 point, Vec3 normal) {
	}
}
