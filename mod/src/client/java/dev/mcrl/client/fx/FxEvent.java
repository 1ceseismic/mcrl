package dev.mcrl.client.fx;

import net.minecraft.world.phys.Vec3;

public sealed interface FxEvent {
	record Landed(int wheel, double normalSpeed) implements FxEvent {
	}

	record Jumped() implements FxEvent {
	}

	record DoubleJumped() implements FxEvent {
	}

	record Dodged() implements FxEvent {
	}

	record FlipReset() implements FxEvent {
	}

	record WorldHit(double speed, Vec3 point, Vec3 normal, RlSurface surface) implements FxEvent {
	}

	record BallHit(double speed, Vec3 point, Vec3 normal) implements FxEvent {
	}

	record BallBounce(double speed, Vec3 point, Vec3 normal, RlSurface surface) implements FxEvent {
	}

	record SupersonicStart() implements FxEvent {
	}

	record BoostStart() implements FxEvent {
	}

	record BoostStop() implements FxEvent {
	}

	record Splash(double speed, Vec3 point, int fluid, boolean ball) implements FxEvent {
	}
}
