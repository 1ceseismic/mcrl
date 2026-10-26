package dev.mcrl.rlassets.bodies;

import java.nio.file.Path;
import java.util.regex.Pattern;

public final class BodyIds {
	public static final Pattern PATTERN = Pattern.compile("[A-Za-z0-9_]{0,64}");
	public static final String OCTANE = "Body_Octane";

	private BodyIds() {
	}

	public static boolean valid(String id) {
		return id != null && !id.isEmpty() && PATTERN.matcher(id).matches();
	}

	public static boolean isDefault(String id) {
		return id == null || id.isEmpty() || id.equals(OCTANE);
	}

	public static String id(String pkg) {
		String s = pkg.endsWith(".upk") ? pkg.substring(0, pkg.length() - 4) : pkg;
		return s.endsWith("_SF") ? s.substring(0, s.length() - 3) : s;
	}

	public static String label(String id) {
		String s = id.startsWith("Body_") ? id.substring(5) : id;
		StringBuilder b = new StringBuilder(s.length() + 4);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '_') {
				b.append(' ');
				continue;
			}
			if (i > 0 && Character.isUpperCase(c)) {
				char p = s.charAt(i - 1);
				if (Character.isLowerCase(p) || Character.isDigit(p)) {
					b.append(' ');
				}
			}
			b.append(c);
		}
		return b.toString().trim();
	}

	public static Path dir(Path cacheDir, String id) {
		return cacheDir.resolve("bodies").resolve(checked(id));
	}

	public static Path thumb(Path cacheDir, String id) {
		return cacheDir.resolve("bodies").resolve("thumbs").resolve(checked(id) + ".png");
	}

	private static String checked(String id) {
		if (!valid(id)) {
			throw new IllegalArgumentException("not a body id: " + id);
		}
		return id;
	}
}
