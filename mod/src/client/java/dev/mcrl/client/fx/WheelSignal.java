package dev.mcrl.client.fx;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

public record WheelSignal(boolean contact, Vec3 point, Vec3 normal, double forwardSpeed, double sideSpeed,
		double normalSpeed, double spinRadPerSec, @Nullable RlSurface surface) {
}
