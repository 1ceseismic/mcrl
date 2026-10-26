package dev.mcrl.client.sound;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcrl.client.fx.FxActor;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.client.sound.ak.Poster;
import dev.mcrl.rlassets.extract.SoundCueExtractor;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.SoundCue;

public final class FxSoundSink implements FxActor.Sink {
	private static final String LOCAL_RECEIVER = "PlaySoundReceiver_Local",
			NON_LOCAL_RECEIVER = "PlaySoundReceiver_NonLocal";

	private final Poster p;
	private final GameObject obj;
	private final boolean local;
	private final Map<String, SoundCue> cues;

	public FxSoundSink(Poster p, GameObject obj, boolean local, Map<String, SoundCue> cues) {
		this.p = Objects.requireNonNull(p);
		this.obj = Objects.requireNonNull(obj);
		this.local = local;
		this.cues = Objects.requireNonNull(cues);
	}

	@Override
	public void activate(AttachmentDef a) {
		SoundCue c = cue(a);
		JsonElement auto = a.component().get("bAutoPlay");
		if (c != null && auto != null && auto.isJsonPrimitive() && auto.getAsBoolean()) {
			p.post(c.start(), obj);
		}
	}

	@Override
	public boolean deactivate(AttachmentDef a) {
		SoundCue c = cue(a);
		if (c != null && c.stop() != null) {
			p.post(c.stop(), obj);
		}
		return true;
	}

	@Nullable SoundCue cue(AttachmentDef a) {
		if (!a.componentClass().endsWith(SoundCueExtractor.COMPONENT)) {
			return null;
		}
		JsonObject c = a.component();
		JsonElement receiver = c.get("Receiver");
		String r = receiver != null && receiver.isJsonPrimitive() ? receiver.getAsString() : "";
		if (local ? r.equals(NON_LOCAL_RECEIVER) : r.equals(LOCAL_RECEIVER)) {
			return null;
		}
		JsonElement ref = c.get("SoundCue");
		return ref != null && ref.isJsonPrimitive() ? cues.get(ref.getAsString()) : null;
	}
}
