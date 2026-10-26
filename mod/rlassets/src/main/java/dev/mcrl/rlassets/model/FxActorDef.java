package dev.mcrl.rlassets.model;

import com.google.gson.JsonObject;
import java.util.List;

public record FxActorDef(String key, String path, String className, List<AttachmentDef> attachments,
		JsonObject props) {
	public FxActorDef {
		attachments = List.copyOf(attachments);
		props = props.deepCopy();
	}
}
