package dev.mcrl.client.sound;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.client.sound.ak.Poster;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.SoundCue;

public final class FreeActorSounds implements FxSinks.ActorSink {
	private static long pooled = Long.MIN_VALUE / 4;

	private final Poster p;
	private final Map<String, SoundCue> cues;
	private final Map<FxActor, Map<String, FxSoundSink>> playing = new WeakHashMap<>();

	public FreeActorSounds(Poster p, Map<String, SoundCue> cues) {
		this.p = p;
		this.cues = cues;
	}

	@Override
	public void activate(FxActorRef r, AttachmentDef a) {
		Pose o = r.origin();
		if (o == null) {
			return;
		}
		GameObject obj = new GameObject(pooled++, null);
		obj.setTransform(new Vec3(o.x(), o.y(), o.z()), CarAudio.local(o, new Vec3(1, 0, 0)));
		FxSoundSink sink = new FxSoundSink(p, obj, r.local(), cues);
		sink.activate(a);
		SoundCue c = sink.cue(a);
		if (c != null && c.stop() != null) {
			playing.computeIfAbsent(r.actor(), k -> new HashMap<>()).put(a.name(), sink);
		}
	}

	@Override
	public boolean deactivate(FxActorRef r, AttachmentDef a) {
		Map<String, FxSoundSink> m = playing.get(r.actor());
		FxSoundSink sink = m == null ? null : m.remove(a.name());
		return sink == null || sink.deactivate(a);
	}
}
