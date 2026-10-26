package dev.mcrl.rlassets.extract;

import com.google.gson.JsonObject;
import dev.mcrl.rlassets.model.CacheJson;
import dev.mcrl.rlassets.model.SoundCue;
import dev.mcrl.rlassets.upk.UpkWorld;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class UpkExtractor implements RlExtractor {
	private @Nullable UpkWorld world;
	private @Nullable Path worldDir;

	@Override
	public RlData extract(Path unpacked, List<String> warnings) throws IOException {
		UpkWorld w = world(unpacked);
		JsonObject check = UpkCheck.run(w);
		int seen = w.warnings().size();
		FxExtractor.Result fx = FxExtractor.extract(w, warnings);
		JsonObject actors = fx.fxActors().has("actors") ? fx.fxActors().getAsJsonObject("actors") : new JsonObject();
		Map<String, SoundCue> cues = SoundCueExtractor.extract(w, CacheJson.fxActors(actors), warnings);
		decoderWarnings(w, seen, "the FX data", warnings);
		return new RlData(fx.effects(), fx.fxActors(), check, fx.materialRefs(), fx.meshRefs(), cues);
	}

	@Override
	public MaterialExtractor.Result materials(Path unpacked, List<MaterialExtractor.Named> named, Collection<String> refs,
			List<String> warnings) throws IOException {
		UpkWorld w = world(unpacked);
		int seen = w.warnings().size();
		MaterialExtractor.Result r = MaterialExtractor.extract(w, named, refs, warnings);
		decoderWarnings(w, seen, "the materials", warnings);
		return r;
	}

	private UpkWorld world(Path unpacked) throws IOException {
		if (world == null || !unpacked.equals(worldDir)) {
			UpkWorld w = new UpkWorld(unpacked);
			for (String script : List.of("Core", "Engine")) {
				if (w.pkg(script) == null) {
					throw new IOException("no readable " + script + ".bin in " + unpacked + ": the property schema needs it");
				}
			}
			world = w;
			worldDir = unpacked;
		}
		return world;
	}

	private static void decoderWarnings(UpkWorld w, int seen, String what, List<String> warnings) {
		List<String> decoder = w.warnings().subList(seen, w.warnings().size());
		if (!decoder.isEmpty()) {
			warnings.add(decoder.size() + " decoder warnings in " + what + ", e.g. " + decoder.get(0));
		}
	}
}
