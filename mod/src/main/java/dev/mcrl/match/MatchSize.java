package dev.mcrl.match;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

public enum MatchSize {
	DUEL(1), DOUBLES(2), STANDARD(3);

	private final int perTeam;

	MatchSize(int perTeam) {
		this.perTeam = perTeam;
	}

	public int perTeam() {
		return perTeam;
	}

	public String label() {
		return perTeam + "v" + perTeam;
	}

	public static @Nullable MatchSize parse(String s) {
		String t = s.trim().toLowerCase(Locale.ROOT);
		for (MatchSize m : values()) {
			if (m.label().equals(t) || m.name().toLowerCase(Locale.ROOT).equals(t)) {
				return m;
			}
		}
		return null;
	}
}
