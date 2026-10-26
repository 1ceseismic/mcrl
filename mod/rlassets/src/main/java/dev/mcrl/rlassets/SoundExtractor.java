package dev.mcrl.rlassets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.mcrl.rlassets.wwise.HircExport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SoundExtractor {
	public record SoundSet(HircExport.Result model, List<String> warnings) {
		public int media() {
			return model.model().media().size();
		}

		public JsonObject toJson() {
			JsonObject o = new JsonObject();
			o.addProperty("model", HircExport.MODEL_FILE);
			o.addProperty("media", media());
			JsonArray ms = new JsonArray();
			model.missing().forEach(ms::add);
			o.add("missing", ms);
			return o;
		}
	}

	private SoundExtractor() {
	}

	public static SoundSet extract(Path cookedDir, VgmstreamRunner vgm, Path outDir) throws IOException, InterruptedException {
		HircExport.Result model = HircExport.export(cookedDir, vgm, outDir);
		List<String> warnings = new ArrayList<>();
		if (!model.missing().isEmpty()) {
			warnings.add(model.missing().size() + " RL sound media missing, e.g. " + model.missing().get(0));
		}
		if (!model.warnings().isEmpty()) {
			warnings.add(model.warnings().size() + " sound model warnings, e.g. " + model.warnings().get(0));
		}
		return new SoundSet(model, List.copyOf(warnings));
	}
}
