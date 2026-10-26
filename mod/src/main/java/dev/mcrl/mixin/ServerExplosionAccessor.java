package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;

@Mixin(ServerExplosion.class)
public interface ServerExplosionAccessor {
	@Accessor("damageCalculator")
	ExplosionDamageCalculator mcrl$damageCalculator();
}
