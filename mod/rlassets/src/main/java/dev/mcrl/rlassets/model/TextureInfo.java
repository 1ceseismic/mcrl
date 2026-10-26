package dev.mcrl.rlassets.model;

public record TextureInfo(String name, String pkg, String file, int width, int height, int exportedWidth,
		int exportedHeight, String format, String compression, boolean srgb, String lodGroup, String addressX,
		String addressY) {
	public TextureInfo exported(String file, int w, int h) {
		return new TextureInfo(name, pkg, file, width, height, w, h, format, compression, srgb, lodGroup, addressX, addressY);
	}
}
