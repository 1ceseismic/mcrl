package dev.mcrl.client.input;

public record RawInput(float throttleKey, float reverseKey, float steerLeft, float steerRight, float lsX, float lsY,
		float rsX, float rsY, float lt, float rt, boolean jump, boolean boost, boolean powerslide, boolean rollLeft,
		boolean rollRight, boolean screenOpen, boolean driving) {
}
