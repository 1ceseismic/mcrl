package dev.mcrl.rlassets.extract;

import com.google.gson.JsonElement;
import dev.mcrl.rlassets.model.AttachmentDef;
import dev.mcrl.rlassets.model.FxActorDef;
import dev.mcrl.rlassets.model.SoundCue;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkWorld;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class SoundCueExtractor {
	public static final String COMPONENT = "AkPlaySoundComponent";

	private SoundCueExtractor() {
	}

	public static Map<String, SoundCue> extract(UpkWorld w, Map<String, FxActorDef> actors, List<String> warnings) {
		Map<String, SoundCue> out = new LinkedHashMap<>();
		Set<String> bad = new HashSet<>();
		for (FxActorDef d : actors.values()) {
			for (AttachmentDef a : d.attachments()) {
				if (!a.componentClass().endsWith(COMPONENT)) {
					continue;
				}
				JsonElement ref = a.component().get("SoundCue");
				if (ref == null || !ref.isJsonPrimitive()) {
					warnings.add("sound component " + d.key() + " " + a.name() + " has no SoundCue");
					continue;
				}
				String path = ref.getAsString();
				if (out.containsKey(path) || bad.contains(path)) {
					continue;
				}
				try {
					out.put(path, cue(w, path));
				} catch (IllegalArgumentException e) {
					bad.add(path);
					warnings.add("sound cue " + path + " (" + d.key() + " " + a.name() + ") does not decode: "
							+ e.getMessage());
				}
			}
		}
		return Collections.unmodifiableMap(out);
	}

	private static SoundCue cue(UpkWorld w, String path) {
		UpkWorld.Loc l = w.locate(path, null);
		if (l == null) {
			throw new IllegalArgumentException("not found");
		}
		UObject o = w.obj(l.pkg(), l.index());
		if (!o.classPath().endsWith(".AkSoundCue")) {
			throw new IllegalArgumentException("it is a " + o.classPath() + ", not an AkSoundCue");
		}
		String start = event(o.props().get("StartEvent"));
		if (start == null) {
			throw new IllegalArgumentException("no StartEvent");
		}
		return new SoundCue(start, event(o.props().get("StopEvent")));
	}

	private static @Nullable String event(@Nullable Object v) {
		return v instanceof String s && !s.isEmpty() && !s.equals("None") ? s : null;
	}
}
