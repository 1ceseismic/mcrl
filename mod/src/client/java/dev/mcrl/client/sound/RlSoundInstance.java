package dev.mcrl.client.sound;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

public final class RlSoundInstance extends AbstractTickableSoundInstance {
	private static final int ATTENUATION_DISTANCE = 48;
	private static final float PITCH_MIN = 1f / 16f, PITCH_MAX = 4f;

	private final Supplier<@Nullable Vec3> pos;
	private final boolean twoD;

	public RlSoundInstance(Identifier id, boolean loop, boolean twoD, Supplier<@Nullable Vec3> pos, float volume,
			float pitch, RandomSource random) {
		super(SoundEvent.createVariableRangeEvent(id), SoundSource.PLAYERS, random);
		this.pos = pos;
		this.twoD = twoD;
		this.looping = loop;
		this.delay = 0;
		this.attenuation = Attenuation.NONE;
		this.relative = twoD;
		set(volume, pitch);
		follow();
	}

	@Override
	public WeighedSoundEvents getOrResolve(SoundManager manager) {
		soundEvent = new WeighedSoundEvents(identifier, null);
		sound = new Sound(identifier, ConstantFloat.of(1f), ConstantFloat.of(1f), 1, Sound.Type.FILE, false, false,
				ATTENUATION_DISTANCE);
		return soundEvent;
	}

	@Override
	public boolean canStartSilent() {
		return true;
	}

	@Override
	public void tick() {
		follow();
	}

	public void set(float volume, float pitch) {
		this.volume = Float.isFinite(volume) ? Math.max(0f, Math.min(1f, volume)) : 0f;
		this.pitch = Float.isFinite(pitch) ? pitch : 1f;
	}

	void stopNow() {
		stop();
	}

	public static float clampPitch(float pitch) {
		return Float.isFinite(pitch) ? Mth.clamp(pitch, PITCH_MIN, PITCH_MAX) : 1f;
	}

	private void follow() {
		if (twoD) {
			return;
		}
		Vec3 p = pos.get();
		if (p != null) {
			x = p.x;
			y = p.y;
			z = p.z;
		}
	}
}
