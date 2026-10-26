package dev.mcrl.client.fx.render;

import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

public final class FxView {
	private Vec3 position = Vec3.ZERO;
	private final Vector3f forward = new Vector3f(0, 0, 1), up = new Vector3f(0, 1, 0), left = new Vector3f(1, 0, 0);

	public static FxView of(Vec3 position, Vector3fc forward, Vector3fc up, Vector3fc left) {
		FxView v = new FxView();
		v.set(position, forward, up, left);
		return v;
	}

	public void set(Vec3 position, Vector3fc forward, Vector3fc up, Vector3fc left) {
		this.position = position;
		this.forward.set(forward);
		this.up.set(up);
		this.left.set(left);
	}

	public void set(Camera camera) {
		set(camera.position(), camera.forwardVector(), camera.upVector(), camera.leftVector());
	}

	public Vec3 position() {
		return position;
	}

	public Vector3fc forward() {
		return forward;
	}

	public Vector3fc up() {
		return up;
	}

	public Vector3fc left() {
		return left;
	}
}
