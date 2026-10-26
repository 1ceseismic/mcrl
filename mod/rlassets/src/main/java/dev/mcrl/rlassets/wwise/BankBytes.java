package dev.mcrl.rlassets.wwise;

import dev.mcrl.rlassets.wwise.HircReader.HircFormatException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.jspecify.annotations.Nullable;

final class BankBytes {
	private final ByteBuffer b;
	private final String source;
	private final long id;
	private final int type;
	private final @Nullable String chunk;
	private final @Nullable String part;

	private BankBytes(ByteBuffer body, String source, long id, int type, @Nullable String chunk, @Nullable String part) {
		this.b = body.slice().order(ByteOrder.LITTLE_ENDIAN);
		this.source = source;
		this.id = id;
		this.type = type;
		this.chunk = chunk;
		this.part = part;
	}

	static BankBytes chunk(ByteBuffer body, String source, String tag) {
		return new BankBytes(body, source, 0, -1, tag, null);
	}

	BankBytes object(int n, int type) throws IOException {
		ByteBuffer s = take(n);
		long objectId = n >= 4 ? s.getInt(0) & 0xFFFFFFFFL : 0;
		return new BankBytes(s, source, objectId, type, null, null);
	}

	BankBytes sub(int n, String part) throws IOException {
		return new BankBytes(take(n), source, id, type, chunk, part);
	}

	long id() {
		return id;
	}

	int u8() throws IOException {
		need(1);
		return b.get() & 0xFF;
	}

	int s8() throws IOException {
		need(1);
		return b.get();
	}

	int u16() throws IOException {
		need(2);
		return b.getShort() & 0xFFFF;
	}

	long u32() throws IOException {
		need(4);
		return b.getInt() & 0xFFFFFFFFL;
	}

	int s32() throws IOException {
		need(4);
		return b.getInt();
	}

	float f32() throws IOException {
		need(4);
		return b.getFloat();
	}

	long var() throws IOException {
		long v = 0;
		for (int i = 0; i < 5; i++) {
			int c = u8();
			v = v << 7 | c & 0x7F;
			if ((c & 0x80) == 0) {
				return v;
			}
		}
		throw error("variable-length value longer than 5 bytes");
	}

	void skip(long n) throws IOException {
		need(n);
		b.position(b.position() + (int) n);
	}

	int remaining() {
		return b.remaining();
	}

	void end() throws IOException {
		if (b.hasRemaining()) {
			throw sizeError(b.limit(), b.position());
		}
	}

	IOException error(String problem) {
		if (chunk != null) {
			return new IOException(source + ": " + chunk + where() + ": " + problem);
		}
		return new HircFormatException(source, id, type, -1, -1, part == null ? problem : part + ": " + problem);
	}

	private ByteBuffer take(int n) throws IOException {
		need(n);
		ByteBuffer s = b.slice(b.position(), n).order(ByteOrder.LITTLE_ENDIAN);
		b.position(b.position() + n);
		return s;
	}

	private void need(long n) throws IOException {
		if (n < 0 || n > b.remaining()) {
			throw sizeError(b.limit(), b.position() + n);
		}
	}

	private IOException sizeError(long expected, long consumed) {
		if (chunk != null) {
			return new IOException(source + ": " + chunk + where() + ": declared " + expected + " bytes, parsed "
					+ consumed);
		}
		return new HircFormatException(source, id, type, expected, consumed, part);
	}

	private String where() {
		return part == null ? "" : " " + part;
	}
}
