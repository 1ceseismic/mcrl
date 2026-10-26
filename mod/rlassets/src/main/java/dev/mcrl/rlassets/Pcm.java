package dev.mcrl.rlassets;

public record Pcm(int rate, int channels, short[] samples) {
	public int frames() {
		return samples.length / channels;
	}
}
