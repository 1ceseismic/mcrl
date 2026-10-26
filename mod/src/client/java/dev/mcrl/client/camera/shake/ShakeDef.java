package dev.mcrl.client.camera.shake;

public record ShakeDef(double duration, double blendIn, double blendOut, Osc pitch, Osc yaw, Osc roll,
		Osc x, Osc y, Osc z, Osc fov) {
}
