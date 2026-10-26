package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import dev.mcrl.net.InputMailbox;
import dev.mcrl.net.NetPayloads.InputC2S;

@Mixin(ServerboundCustomPayloadPacket.class)
public abstract class ServerboundCustomPayloadPacketMixin {
	// Netty thread, before Fabric queues it to the server.
	@Inject(method = "handle(Lnet/minecraft/network/protocol/common/ServerCommonPacketListener;)V", at = @At("HEAD"),
			cancellable = true)
	private void mcrl$inputOnNetty(ServerCommonPacketListener listener, CallbackInfo ci) {
		if (((ServerboundCustomPayloadPacket) (Object) this).payload() instanceof InputC2S in
				&& listener instanceof ServerGamePacketListenerImpl game) {
			InputMailbox.SERVER.receive(game.player.getUUID(), in);
			ci.cancel();
		}
	}
}
