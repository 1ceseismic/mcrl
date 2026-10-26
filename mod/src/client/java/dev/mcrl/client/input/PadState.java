package dev.mcrl.client.input;

public record PadState(float lsX, float lsY, float rsX, float rsY, float lt, float rt, boolean south, boolean east,
		boolean west, boolean north, boolean dpadUp, boolean dpadDown) {
	public static final PadState NONE = new PadState(0f, 0f, 0f, 0f, 0f, 0f, false, false, false, false, false, false);
}
