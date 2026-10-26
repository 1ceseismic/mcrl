package dev.mcrl.client.sound;

import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.link.LocalSimLink;
import dev.mcrl.client.mixin.SoundEngineAccessor;
import dev.mcrl.client.mixin.SoundManagerAccessor;

public final class RlSoundFrames {
	private RlSoundFrames() {
	}

	public static void update(Minecraft mc) {
		if (LocalSimLink.pausing(mc)) {
			return;
		}
		SoundManagerAccessor manager = (SoundManagerAccessor) mc.getSoundManager();
		SoundEngineAccessor engine = (SoundEngineAccessor) manager.mcrl$soundEngine();
		for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> e : engine.mcrl$instanceToChannel().entrySet()) {
			if (e.getKey() instanceof RlSoundInstance i) {
				i.tick();
				if (!i.isStopped()) {
					float volume = engine.mcrl$calculateVolume(i), pitch = engine.mcrl$calculatePitch(i);
					Vec3 pos = new Vec3(i.getX(), i.getY(), i.getZ());
					e.getValue().execute(ch -> {
						ch.setVolume(volume);
						ch.setPitch(pitch);
						ch.setSelfPosition(pos);
					});
				}
			}
		}
	}
}
