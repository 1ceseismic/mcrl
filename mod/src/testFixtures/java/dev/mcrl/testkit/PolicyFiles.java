package dev.mcrl.testkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.jspecify.annotations.Nullable;

public final class PolicyFiles {
	private PolicyFiles() {
	}

	public static Path write(Path file, List<float[]> blobs, boolean deflate) throws IOException {
		try (OutputStream os = Files.newOutputStream(file); ZipOutputStream z = new ZipOutputStream(os)) {
			put(z, "archive/data.pkl", new byte[] {(byte) 0x80, 2}, deflate);
			for (int i = 0; i < blobs.size(); i++) {
				ByteBuffer b = ByteBuffer.allocate(4 * blobs.get(i).length).order(ByteOrder.LITTLE_ENDIAN);
				for (float f : blobs.get(i)) {
					b.putFloat(f);
				}
				put(z, "archive/data/" + i, b.array(), deflate);
			}
			put(z, "archive/version", "3\n".getBytes(StandardCharsets.US_ASCII), deflate);
		}
		return file;
	}

	public static void put(ZipOutputStream z, String name, byte[] data, boolean deflate) throws IOException {
		ZipEntry e = new ZipEntry(name);
		if (!deflate) {
			CRC32 crc = new CRC32();
			crc.update(data);
			e.setMethod(ZipEntry.STORED);
			e.setSize(data.length);
			e.setCompressedSize(data.length);
			e.setCrc(crc.getValue());
		}
		z.putNextEntry(e);
		z.write(data);
		z.closeEntry();
	}

	public static List<float[]> net(int[] dims, @Nullable Random rng) {
		List<float[]> out = new ArrayList<>();
		for (int l = 0; l + 1 < dims.length; l++) {
			float[] w = new float[dims[l] * dims[l + 1]], b = new float[dims[l + 1]];
			if (rng != null) {
				for (int i = 0; i < w.length; i++) {
					w[i] = (float) (rng.nextGaussian() * 0.2);
				}
				for (int i = 0; i < b.length; i++) {
					b[i] = (float) (rng.nextGaussian() * 0.1);
				}
			}
			out.add(w);
			out.add(b);
		}
		return out;
	}

	public static List<float[]> constant(int k) {
		float[] w = new float[90 * 89], b = new float[90];
		Arrays.fill(b, -1f);
		b[k] = 1;
		return List.of(w, b);
	}
}
