package dev.mcrl.client.rl;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;

import net.minecraft.client.renderer.texture.AbstractTexture;

public final class MipTexture extends AbstractTexture {
	public MipTexture(String label, RlTextures.Upload u) {
		RenderSystem.assertOnRenderThread();
		GpuDevice device = RenderSystem.getDevice();
		// 26.3 mip sizes are unclamped shifts; stop at 1.
		int levels = Math.min(u.levels(), 32 - Integer.numberOfLeadingZeros(Math.min(u.width(), u.height())));
		texture = device.createTexture(label, GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA8_UNORM, u.width(), u.height(), 1, levels);
		try {
			textureView = device.createTextureView(texture);
			sampler = RenderSystem.getSamplerCache().getSampler(u.addressU(), u.addressV(), FilterMode.LINEAR,
					FilterMode.LINEAR, u.mipmapped());
			CommandEncoder encoder = device.createCommandEncoder();
			for (int k = 0; k < levels; k++) {
				int w = u.width(k), h = u.height(k);
				try (NativeImage image = new NativeImage(w, h, false)) {
					MemoryUtil.memIntBuffer(image.getPointer(), w * h).put(u.abgr().get(k));
					encoder.writeToTexture(texture, image, k, 0, 0, 0);
				}
			}
		} catch (RuntimeException e) {
			close();
			throw e;
		}
	}
}
