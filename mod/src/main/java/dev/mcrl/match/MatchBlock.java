package dev.mcrl.match;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.jspecify.annotations.Nullable;

import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SoccarState;

public record MatchBlock(boolean[] padActive, float[] padCooldown, Header header) {
	public static final int PADS = 34, BYTES = PADS * 3 + 8;

	public record Header(int phase, boolean overtime, int clockSeconds, int blue, int orange, int countdown,
			boolean ballOutOfPlay) {
		public static Header lobby(boolean ballOutOfPlay) {
			return new Header(Match.Phase.LOBBY.ordinal(), false, (int) (MatchRules.GAME_TICKS / SimClock.TICK_RATE), 0,
					0, 0, ballOutOfPlay);
		}

		public Match.@Nullable Phase phaseOrNull() {
			Match.Phase[] all = Match.Phase.values();
			return phase >= 0 && phase < all.length ? all[phase] : null;
		}
	}

	public MatchBlock {
		if (padActive.length != PADS || padCooldown.length != PADS) {
			throw new IllegalArgumentException("pads " + padActive.length + "/" + padCooldown.length + ", want " + PADS);
		}
		padActive = padActive.clone();
		padCooldown = padCooldown.clone();
	}

	@Override
	public boolean[] padActive() {
		return padActive.clone();
	}

	@Override
	public float[] padCooldown() {
		return padCooldown.clone();
	}

	public static MatchBlock of(SoccarState s, @Nullable Header h) {
		return new MatchBlock(s.padActive(), s.padCooldown(), h != null ? h : Header.lobby(s.ballOutOfPlay()));
	}

	public byte[] encode() {
		ByteBuffer b = ByteBuffer.allocate(BYTES);
		for (int i = 0; i < PADS; i++) {
			b.put((byte) (padActive[i] ? 1 : 0));
			b.putShort(Float.floatToFloat16(padCooldown[i]));
		}
		b.put(u8(header.phase()));
		b.put((byte) (header.overtime() ? 1 : 0));
		b.putShort((short) Math.clamp(header.clockSeconds(), Short.MIN_VALUE, Short.MAX_VALUE));
		b.put(u8(header.blue()));
		b.put(u8(header.orange()));
		b.put(u8(header.countdown()));
		b.put((byte) (header.ballOutOfPlay() ? 1 : 0));
		return b.array();
	}

	public static MatchBlock decode(byte[] bytes) {
		if (bytes.length != BYTES) {
			throw new IllegalArgumentException("a match block is " + BYTES + " bytes, not " + bytes.length);
		}
		ByteBuffer b = ByteBuffer.wrap(bytes);
		boolean[] active = new boolean[PADS];
		float[] cooldown = new float[PADS];
		for (int i = 0; i < PADS; i++) {
			active[i] = b.get() != 0;
			cooldown[i] = Float.float16ToFloat(b.getShort());
		}
		int phase = b.get() & 0xFF;
		boolean overtime = b.get() != 0;
		int clock = b.getShort();
		int blue = b.get() & 0xFF, orange = b.get() & 0xFF, countdown = b.get() & 0xFF;
		boolean out = b.get() != 0;
		return new MatchBlock(active, cooldown, new Header(phase, overtime, clock, blue, orange, countdown, out));
	}

	private static byte u8(int v) {
		return (byte) Math.clamp(v, 0, 255);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof MatchBlock m && Arrays.equals(padActive, m.padActive)
				&& Arrays.equals(padCooldown, m.padCooldown) && header.equals(m.header);
	}

	@Override
	public int hashCode() {
		return 31 * (31 * Arrays.hashCode(padActive) + Arrays.hashCode(padCooldown)) + header.hashCode();
	}

	@Override
	public String toString() {
		return "MatchBlock[" + header + ", active " + Arrays.toString(padActive) + "]";
	}
}
