package dev.mcrl.sim;

public enum PauseSource {
	CLIENT, SERVER, FREEZE;

	public int bit() {
		return 1 << ordinal();
	}
}
