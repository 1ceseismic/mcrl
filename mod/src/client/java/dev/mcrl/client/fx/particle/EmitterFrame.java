package dev.mcrl.client.fx.particle;

import net.minecraft.world.phys.Vec3;

public record EmitterFrame(double dt, double spawnFactor, Vec3 camera, FxCollider collider) {
}
