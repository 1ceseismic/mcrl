package dev.mcrl.client.camera;

import net.minecraft.world.phys.Vec3;

public record Pov(Vec3 focus, Rot rot, double distance, double fov) {
	public Vec3 location() {
		return focus.subtract(rot.vector().scale(distance));
	}
}
