package dev.mcrl.net;

import java.util.Arrays;
import java.util.Objects;

public record ArenaBlock(int kind, byte[] body) {
	public static final int KIND_TERRAIN = 0;
	public static final int KIND_SOCCAR = 1;
	public static final ArenaBlock TERRAIN = new ArenaBlock(KIND_TERRAIN, new byte[0]);

	public ArenaBlock {
		if (kind < 0 || kind > 255) {
			throw new IllegalArgumentException("arena kind " + kind + " is not a u8");
		}
		body = body.clone();
	}

	@Override
	public byte[] body() {
		return body.clone();
	}

	public int length() {
		return body.length;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ArenaBlock a && a.kind == kind && Arrays.equals(a.body, body);
	}

	@Override
	public int hashCode() {
		return Objects.hash(kind, Arrays.hashCode(body));
	}

	@Override
	public String toString() {
		return "ArenaBlock[kind=" + kind + ", " + body.length + " B]";
	}
}
