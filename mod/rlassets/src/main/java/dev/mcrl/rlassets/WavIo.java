package dev.mcrl.rlassets;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WavIo {
	private WavIo() {
	}

	public static Pcm read(Path p) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.remaining() < 12 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) {
			throw new IOException(p + " is not a RIFF/WAVE file");
		}
		int pos = 12, rate = 0, ch = 0;
		boolean fmt = false;
		while (pos + 8 <= b.limit()) {
			int id = b.getInt(pos);
			long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
			int body = pos + 8;
			if (id == 0x20746d66) {
				if (size < 16 || body + 16 > b.limit()) {
					throw new IOException(p + ": short fmt chunk");
				}
				int tag = b.getShort(body) & 0xFFFF;
				ch = b.getShort(body + 2);
				rate = b.getInt(body + 4);
				int bits = b.getShort(body + 14);
				if (tag != 1 || bits != 16 || ch < 1) {
					throw new IOException(p + ": only PCM16 is supported (tag " + tag + ", " + bits + " bits, " + ch + " ch)");
				}
				fmt = true;
			} else if (id == 0x61746164) {
				if (!fmt) {
					throw new IOException(p + ": data before fmt");
				}
				long avail = Math.min(size, b.limit() - body);
				int n = (int) (avail / 2 / ch) * ch;
				short[] s = new short[n];
				b.position(body);
				b.asShortBuffer().get(s);
				return new Pcm(rate, ch, s);
			}
			pos = (int) Math.min((long) body + size + (size & 1), b.limit());
		}
		throw new IOException(p + ": no data chunk");
	}

	public static void write(Path p, Pcm pcm) throws IOException {
		int dataLen = pcm.samples().length * 2;
		ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(0x46464952).putInt(36 + dataLen).putInt(0x45564157);
		b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) pcm.channels()).putInt(pcm.rate())
				.putInt(pcm.rate() * pcm.channels() * 2).putShort((short) (pcm.channels() * 2)).putShort((short) 16);
		b.putInt(0x61746164).putInt(dataLen);
		b.asShortBuffer().put(pcm.samples());
		Files.write(p, b.array());
	}
}
