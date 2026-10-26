package dev.mcrl.client.camera;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

public record CamInput(Vec3 carPos, Vec3 carFwd, Vec3 carRight, Vec3 carUp, Vec3 velocity, boolean onGround,
		boolean jumping, boolean supersonic, @Nullable Vec3 groundNormal, @Nullable Vec3 ball) {

	public boolean finite() {
		return carPos.isFinite() && carFwd.isFinite() && carRight.isFinite() && carUp.isFinite() && velocity.isFinite()
				&& (groundNormal == null || groundNormal.isFinite()) && (ball == null || ball.isFinite());
	}
}
