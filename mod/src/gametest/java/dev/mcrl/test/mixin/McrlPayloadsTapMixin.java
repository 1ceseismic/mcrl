package dev.mcrl.test.mixin;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import dev.mcrl.math.Pose;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.net.McrlPayloads.FxEventS2C;
import dev.mcrl.test.Taps;

@Mixin(McrlPayloads.class)
public abstract class McrlPayloadsTapMixin {
	@Inject(method = "sendFxEvent(Lnet/minecraft/world/entity/Entity;Ldev/mcrl/net/McrlPayloads$FxEventS2C$Kind;"
			+ "Ldev/mcrl/math/Pose;Lnet/minecraft/server/level/ServerPlayer;I)V", at = @At("HEAD"))
	private static void mcrlTest$fxEvent(Entity entity, FxEventS2C.Kind kind, Pose pose, @Nullable ServerPlayer actor,
			int attackerId, CallbackInfo ci) {
		Consumer<FxEventS2C> tap = Taps.fxEvents;
		if (tap != null) {
			tap.accept(new FxEventS2C(entity.getId(), kind, pose, attackerId));
		}
	}
}
