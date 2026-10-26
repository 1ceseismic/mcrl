package dev.mcrl.rlassets.model;

import com.google.gson.JsonObject;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record AttachmentDef(String name, @Nullable String socket, List<String> attachAny, List<String> attachAll,
		List<String> detachAny, float attachDelay, float detachDelay, float lifeTime, Target target,
		String componentClass, JsonObject component, JsonObject props) {
	public enum Target {
		ALL, LOCAL, NON_LOCAL
	}

	public AttachmentDef {
		attachAny = List.copyOf(attachAny);
		attachAll = List.copyOf(attachAll);
		detachAny = List.copyOf(detachAny);
		component = component.deepCopy();
		props = props.deepCopy();
	}
}
