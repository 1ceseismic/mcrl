package dev.mcrl.net;

import dev.mcrl.net.NetPayloads.HelloS2C;

public enum NetMode {
	NONE, INTERPOLATE, PREDICT;

	public static NetMode choose(HelloS2C s, int protocol, int abi, long hash, boolean physicsOn, boolean predictionOn) {
		if (s.protocol() != protocol) {
			return NONE;
		}
		if (s.abi() != abi || s.buildHash() != hash || !physicsOn || !predictionOn) {
			return INTERPOLATE;
		}
		return PREDICT;
	}

	static NetMode of(int i) {
		NetMode[] all = values();
		return i >= 0 && i < all.length ? all[i] : NONE;
	}
}
