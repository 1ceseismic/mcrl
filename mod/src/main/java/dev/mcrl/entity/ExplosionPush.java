package dev.mcrl.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.mixin.ServerExplosionAccessor;

public final class ExplosionPush {
	private static final double TICKS_PER_SECOND = 20;

	private ExplosionPush() {
	}

	public static Vec3 velocity(Vec3 centre, float radius, double seen, float knockback, Vec3 body) {
		Vec3 off = body.subtract(centre);
		double d = off.length() / (2.0 * radius);
		if (!(d <= 1)) {
			return Vec3.ZERO;
		}
		return off.normalize().scale((1 - d) * seen * knockback * TICKS_PER_SECOND);
	}

	public static Vec3 of(Explosion explosion, Entity entity, Vec3 body) {
		return of(explosion, entity, body, ServerExplosion.getSeenPercent(explosion.center(), entity));
	}

	public static Vec3 of(Explosion explosion, Entity entity, Vec3 body, double seen) {
		float knockback = explosion instanceof ServerExplosion se
				? ((ServerExplosionAccessor) se).mcrl$damageCalculator().getKnockbackMultiplier(entity)
				: 1f;
		return velocity(explosion.center(), explosion.radius(), seen, knockback, body);
	}
}
