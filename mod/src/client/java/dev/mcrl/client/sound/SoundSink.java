package dev.mcrl.client.sound;

import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

public final class SoundSink {
	public static final double RETRY = 1.0;

	private final RandomSource random = RandomSource.create();

	public record Request(String stem, boolean loop, boolean twoD, Supplier<Vec3> pos, float volume, float pitch) {
	}

	private static SoundManager manager() {
		return Minecraft.getInstance().getSoundManager();
	}

	public RlSoundInstance play(Request r) {
		RlSoundInstance s = new RlSoundInstance(Objects.requireNonNull(RlSoundLibrary.idOfStem(r.stem()), r.stem()),
				r.loop(), r.twoD(), r.pos(), r.volume(), r.pitch(), random);
		manager().play(s);
		return s;
	}

	public void set(RlSoundInstance h, float volume, float pitch) {
		h.set(volume, pitch);
	}

	public void stop(RlSoundInstance h) {
		h.stopNow();
		manager().stop(h);
	}

	public boolean active(RlSoundInstance h) {
		return manager().isActive(h);
	}
}
