package dev.mcrl.rlassets;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public record LocateResult(State state, @Nullable Path cookedDir, @Nullable Path installDir, String source, String hint) {
	public enum State { FOUND, FOUND_UNMOUNTED, NOT_FOUND }

	public static final List<String> FINGERPRINT_FILES = List.of("Startup.upk", "MENU_Main_p.upk", "Boost_Standard_SF.upk");

	public String fingerprint() {
		return fingerprint(List.of());
	}

	public String fingerprint(Collection<String> extraFiles) {
		if (state != State.FOUND || cookedDir == null) {
			return "";
		}
		List<String> names = new ArrayList<>(FINGERPRINT_FILES);
		names.addAll(extraFiles);
		StringBuilder sb = new StringBuilder();
		for (String n : names) {
			if (!sb.isEmpty()) {
				sb.append(';');
			}
			Path f = cookedDir.resolve(n);
			String v;
			try {
				v = Files.size(f) + "@" + Files.getLastModifiedTime(f).toMillis();
			} catch (IOException e) {
				v = "missing";
			}
			sb.append(n).append('=').append(v);
		}
		return sb.toString();
	}
}
