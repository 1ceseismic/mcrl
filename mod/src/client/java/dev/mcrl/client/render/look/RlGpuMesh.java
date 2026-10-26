package dev.mcrl.client.render.look;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;

import dev.mcrl.client.rl.RlCache;
import dev.mcrl.rlassets.MeshData;

public record RlGpuMesh(GpuBuffer vertexBuffer, GpuBuffer indexBuffer, int indexCount) implements AutoCloseable {
	private static final int VERTEX_BYTES = 48;

	public record Data(String name, ByteBuffer vertices, ByteBuffer indices, int indexCount) {
	}

	public static Data pack(String name, RlCache.Mesh mesh) {
		float[] v = mesh.vertices();
		int[] ix = mesh.indices();
		float[] t = MeshTangents.compute(v, ix);
		int n = v.length / MeshData.STRIDE;
		ByteBuffer vb = ByteBuffer.allocateDirect(n * VERTEX_BYTES).order(ByteOrder.nativeOrder());
		for (int i = 0; i < n; i++) {
			int s = i * MeshData.STRIDE;
			vb.putFloat(v[s]).putFloat(v[s + 1]).putFloat(v[s + 2]);
			vb.putFloat(v[s + 3]).putFloat(v[s + 4]).putFloat(v[s + 5]);
			vb.putFloat(t[4 * i]).putFloat(t[4 * i + 1]).putFloat(t[4 * i + 2]).putFloat(t[4 * i + 3]);
			vb.putFloat(v[s + 6]).putFloat(v[s + 7]);
		}
		ByteBuffer ib = ByteBuffer.allocateDirect(ix.length * 4).order(ByteOrder.nativeOrder());
		ib.asIntBuffer().put(ix);
		return new Data(name, vb.flip(), ib, ix.length);
	}

	public static RlGpuMesh upload(Data d) {
		GpuDevice device = RenderSystem.getDevice();
		GpuBuffer vertices = device.createBuffer(() -> "mcrl RL mesh " + d.name() + " vertices", GpuBuffer.USAGE_VERTEX,
				d.vertices());
		try {
			return new RlGpuMesh(vertices, device.createBuffer(() -> "mcrl RL mesh " + d.name() + " indices",
					GpuBuffer.USAGE_INDEX, d.indices()), d.indexCount());
		} catch (RuntimeException e) {
			vertices.close();
			throw e;
		}
	}

	@Override
	public void close() {
		vertexBuffer.close();
		indexBuffer.close();
	}
}
