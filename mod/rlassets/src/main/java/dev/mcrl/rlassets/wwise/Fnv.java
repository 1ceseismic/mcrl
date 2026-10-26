package dev.mcrl.rlassets.wwise;

import java.nio.charset.StandardCharsets;

public final class Fnv {
	private static final int OFFSET_BASIS = 0x811C9DC5;
	private static final int PRIME = 16777619;

	private Fnv() {
	}

	// Wwise ids: 32-bit FNV-1 of the lowercased name.
	public static long hash(String name) {
		int h = OFFSET_BASIS;
		for (byte c : name.getBytes(StandardCharsets.UTF_8)) {
			if (c >= 'A' && c <= 'Z') {
				c += 'a' - 'A';
			}
			h = h * PRIME ^ (c & 0xFF);
		}
		return h & 0xFFFFFFFFL;
	}
}
