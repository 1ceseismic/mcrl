package dev.mcrl.rlassets.extract;

import com.google.gson.JsonObject;
import dev.mcrl.rlassets.model.SoundCue;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface RlExtractor {
	record RlData(JsonObject effects, JsonObject fxActors, JsonObject upkCheck, Set<String> materialRefs,
			Set<String> meshRefs, Map<String, SoundCue> soundCues) {
		public RlData {
			materialRefs = Collections.unmodifiableSet(new LinkedHashSet<>(materialRefs));
			meshRefs = Collections.unmodifiableSet(new LinkedHashSet<>(meshRefs));
			soundCues = Collections.unmodifiableMap(new LinkedHashMap<>(soundCues));
		}
	}

	RlData extract(Path unpacked, List<String> warnings) throws IOException;

	default MaterialExtractor.Result materials(Path unpacked, List<MaterialExtractor.Named> named, Collection<String> refs,
			List<String> warnings) throws IOException {
		return MaterialExtractor.Result.EMPTY;
	}
}
