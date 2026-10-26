package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import dev.mcrl.client.input.McrlKeys;
import dev.mcrl.entity.CarEntity;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@WrapOperation(method = "handleSetEntityPassengersPacket", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;translatable(Ljava/lang/String;[Ljava/lang/Object;)"
					+ "Lnet/minecraft/network/chat/MutableComponent;"))
	private MutableComponent mcrl$exitHint(String key, Object[] args, Operation<MutableComponent> original) {
		var player = Minecraft.getInstance().player;
		if (player != null && player.getVehicle() instanceof CarEntity) {
			return Component.translatable("mcrl.mount.exit", McrlKeys.EXIT_CAR.getTranslatedKeyMessage());
		}
		return original.call(key, args);
	}
}
