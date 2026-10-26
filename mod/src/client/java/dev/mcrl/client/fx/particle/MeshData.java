package dev.mcrl.client.fx.particle;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

public final class MeshData {
	private MeshData() {
	}

	public static @Nullable String mesh(EmitterInstance e) {
		return e.def().typeData().mesh();
	}

	public static @Nullable String material(EmitterInstance e) {
		for (FxModule m : e.def().modules()) {
			if (m instanceof FxModule.MaterialByParameter mp && !mp.defaultMaterials().isEmpty()
					&& !mp.defaultMaterials().get(0).equals("None")) {
				return mp.defaultMaterials().get(0);
			}
		}
		String own = e.def().required().material();
		return e.def().typeData().overrideMaterial() && !own.isEmpty() ? own : null;
	}

	public static Matrix3f rlLinear(EmitterInstance e, int i, Matrix3f out) {
		Vector3f rot = e.meshRotation(i, new Vector3f());
		Vector3f col = new Vector3f();
		float[] size = {e.sizeX(i), e.sizeY(i), e.sizeZ(i)};
		for (int k = 0; k < 3; k++) {
			col.set(k == 0 ? size[0] : 0, k == 1 ? size[1] : 0, k == 2 ? size[2] : 0);
			FxModules.rotate(rot.x, rot.y, rot.z, col);
			e.particleAxesToWorld(col);
			out.setColumn(k, col);
		}
		return out;
	}

	public static Matrix4f transform(EmitterInstance e, int i, Vec3 camera, Matrix4f out) {
		Matrix3f l = rlLinear(e, i, new Matrix3f());
		out.set(l.m00, l.m02, l.m01, 0,
				l.m20, l.m22, l.m21, 0,
				l.m10, l.m12, l.m11, 0,
				0, 0, 0, 1);
		Vector3d p = new Vector3d();
		e.drawPositionRl(i, p);
		out.m30((float) (p.x / 50 - camera.x));
		out.m31((float) (p.z / 50 - camera.y));
		out.m32((float) (p.y / 50 - camera.z));
		return out;
	}
}
