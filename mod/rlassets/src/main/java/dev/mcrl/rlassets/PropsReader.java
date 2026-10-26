package dev.mcrl.rlassets;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PropsReader {
	public record Socket(String name, String bone, double x, double y, double z) {
	}

	public record Props(List<Socket> sockets, Map<String, String> textures, Map<String, double[]> vectors) {
		public @Nullable String textureObject(String parameter) {
			String ref = textures.get(parameter);
			return ref == null ? null : ref.substring(ref.lastIndexOf('.') + 1);
		}
	}

	private static final Pattern OBJECT_REF = Pattern.compile("^\\w+'([^']*)'$");
	private static final Pattern STRUCT_FIELD = Pattern.compile("(\\w+)\\s*=\\s*([^,}]*)");

	private PropsReader() {
	}

	public static Props read(Path file) throws IOException {
		return parse(Files.readString(file, StandardCharsets.UTF_8));
	}

	public static String packageOf(String ref) {
		int dot = ref.indexOf('.');
		return dot < 0 ? ref : ref.substring(0, dot);
	}

	public static Props parse(String text) {
		List<Socket> sockets = new ArrayList<>();
		Map<String, String> textures = new LinkedHashMap<>();
		Map<String, double[]> vectors = new LinkedHashMap<>();
		Deque<Map<String, String>> blocks = new ArrayDeque<>();
		blocks.push(new LinkedHashMap<>());
		for (String raw : text.split("\\R")) {
			String t = raw.strip();
			if (t.equals("{")) {
				blocks.push(new LinkedHashMap<>());
				continue;
			}
			if (t.equals("}")) {
				if (blocks.size() > 1) {
					collect(blocks.pop(), sockets, textures, vectors);
				}
				continue;
			}
			int eq = t.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = t.substring(0, eq).strip();
			String value = t.substring(eq + 1).strip();
			blocks.peek().put(key, value);
		}
		return new Props(List.copyOf(sockets), Map.copyOf(textures), Map.copyOf(vectors));
	}

	private static void collect(Map<String, String> f, List<Socket> sockets, Map<String, String> textures,
			Map<String, double[]> vectors) {
		if (f.containsKey("SocketName") && f.containsKey("RelativeLocation")) {
			Map<String, String> loc = struct(f.get("RelativeLocation"));
			sockets.add(new Socket(f.get("SocketName"), f.getOrDefault("BoneName", ""), num(loc.get("X")), num(loc.get("Y")),
					num(loc.get("Z"))));
			return;
		}
		String name = f.containsKey("ParameterName") ? f.get("ParameterName") : f.get("Name");
		String value = f.containsKey("ParameterValue") ? f.get("ParameterValue")
				: f.containsKey("Texture") ? f.get("Texture") : f.get("Value");
		if (name == null || value == null) {
			return;
		}
		Matcher m = OBJECT_REF.matcher(value);
		if (m.matches()) {
			textures.putIfAbsent(name, m.group(1));
		} else if (value.startsWith("{")) {
			Map<String, String> s = struct(value);
			if (s.containsKey("R")) {
				vectors.putIfAbsent(name, new double[] {num(s.get("R")), num(s.get("G")), num(s.get("B")),
						s.containsKey("A") ? num(s.get("A")) : 1});
			}
		}
	}

	private static Map<String, String> struct(String s) {
		Map<String, String> out = new LinkedHashMap<>();
		Matcher m = STRUCT_FIELD.matcher(s);
		while (m.find()) {
			out.put(m.group(1), m.group(2).strip());
		}
		return out;
	}

	private static double num(@Nullable String s) {
		if (s == null) {
			return 0;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
