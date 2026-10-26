package dev.mcrl.test.mixin;

import java.util.function.BiConsumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import dev.mcrl.net.MatchPayloads;
import dev.mcrl.test.Taps;

@Mixin(MatchPayloads.class)
public abstract class MatchPayloadsTapMixin {
	@Inject(method = "send", at = @At("HEAD"))
	private static void mcrlTest$send(ServerPlayer p, CustomPacketPayload payload, CallbackInfo ci) {
		BiConsumer<ServerPlayer, CustomPacketPayload> tap = Taps.matchPayloads;
		if (tap != null) {
			tap.accept(p, payload);
		}
	}
}
