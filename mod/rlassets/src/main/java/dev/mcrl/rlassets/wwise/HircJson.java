package dev.mcrl.rlassets.wwise;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class HircJson {
	public static final int SCHEMA = 1;

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping()
			.registerTypeAdapter(WwiseCurve.Point.class, new PointAdapter()).create();

	private HircJson() {
	}

	public static void write(HircModel model, Path file) throws IOException {
		JsonObject o = new JsonObject();
		o.addProperty("schema", SCHEMA);
		for (Map.Entry<String, JsonElement> e : GSON.toJsonTree(model).getAsJsonObject().entrySet()) {
			o.add(e.getKey(), e.getValue());
		}
		Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
	}

	public static HircModel read(Path file) throws IOException {
		try {
			JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			if (!e.isJsonObject()) {
				throw new IOException(file + " is not a JSON object");
			}
			JsonObject o = e.getAsJsonObject();
			int schema = o.has("schema") ? o.get("schema").getAsInt() : -1;
			if (schema != SCHEMA) {
				throw new IOException(file + " has schema " + schema + ", mcrl reads " + SCHEMA);
			}
			HircModel m = GSON.fromJson(o, HircModel.class);
			if (m.events() == null || m.nodes() == null || m.buses() == null || m.attenuations() == null
					|| m.modulators() == null || m.gameParameters() == null || m.switchFromRtpc() == null
					|| m.media() == null) {
				throw new IOException(file + " lacks a part of the model");
			}
			return m;
		} catch (JsonParseException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
			throw new IOException("unreadable " + file + ": " + e.getMessage(), e);
		}
	}

	private static final class PointAdapter extends TypeAdapter<WwiseCurve.Point> {
		@Override
		public void write(JsonWriter out, WwiseCurve.Point p) throws IOException {
			out.beginArray().value(p.x()).value(p.y()).value(p.shape().label()).endArray();
		}

		@Override
		public WwiseCurve.Point read(JsonReader in) throws IOException {
			if (in.peek() != JsonToken.BEGIN_ARRAY) {
				throw new JsonParseException("a curve point is [x, y, \"Shape\"], at " + in.getPath());
			}
			in.beginArray();
			double x = in.nextDouble(), y = in.nextDouble();
			String shape = in.nextString();
			in.endArray();
			try {
				return new WwiseCurve.Point(x, y, WwiseCurve.Shape.parse(shape));
			} catch (IllegalArgumentException e) {
				throw new JsonParseException(e.getMessage() + " at " + in.getPath());
			}
		}
	}
}
