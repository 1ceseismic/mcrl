package dev.mcrl.entity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import dev.mcrl.Mcrl;

public final class McrlEntities {
	public static final EntityType<CarEntity> CAR = register("car",
			EntityType.Builder.of(CarEntity::new, MobCategory.MISC).sized(2.41f, 0.77f).clientTrackingRange(10)
					.updateInterval(1));
	public static final EntityType<BallEntity> BALL = register("ball",
			EntityType.Builder.of(BallEntity::new, MobCategory.MISC).sized(3.65f, 3.65f).clientTrackingRange(16)
					.updateInterval(1));

	private McrlEntities() {
	}

	public static void register() {
	}

	private static <T extends net.minecraft.world.entity.Entity> EntityType<T> register(String name,
			EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE,
				Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}
}
