package dev.mcrl.client.link;

import dev.mcrl.net.NetMode;

public record NetStats(NetMode mode, int rttMs, int leadTicks, int slack, double snapHz, double bytesPerSec,
		long corrections) {
	public static final NetStats NONE = new NetStats(NetMode.NONE, 0, 0, 0, 0, 0, 0);
}
