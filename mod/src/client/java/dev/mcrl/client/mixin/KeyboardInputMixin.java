package dev.mcrl.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

import dev.mcrl.client.link.LocalSimLink;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
	// a shift would dismount, and LShift is powerslide
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void mcrl$noVanillaMovementWhileDriving(CallbackInfo ci) {
		if (LocalSimLink.ridingCar(Minecraft.getInstance())) {
			this.keyPresses = Input.EMPTY;
			this.moveVector = Vec2.ZERO;
		}
	}
}
