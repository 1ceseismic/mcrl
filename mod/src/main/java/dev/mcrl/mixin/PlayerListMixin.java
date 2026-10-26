package dev.mcrl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import dev.mcrl.net.McrlPayloads;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
	// Op, deop, join, respawn and LAN cheats end here.
	@Inject(method = "sendPlayerPermissionLevel(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("TAIL"))
	private void mcrl$permissionChanged(ServerPlayer player, CallbackInfo ci) {
		McrlPayloads.sendRules(player);
	}
}
