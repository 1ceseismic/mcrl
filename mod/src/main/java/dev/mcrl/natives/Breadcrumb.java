package dev.mcrl.natives;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicIntegerArray;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// A shared mapping: its pages outlive a native crash.
public final class Breadcrumb {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	public enum Op { IDLE, CREATE, DESTROY, STEP, BUILD, COMMIT, REMOVE, ADD_CAR, SET_CAR, SET_BALL, SHIFT, INIT,
		ARENA_MESHES, KICKOFF, SAVE }

	public record Previous(Op op, int x, int y, int z, long tick, long millis, String thread) {
	}

	static final int SIZE = 4096, SLOTS_AT = 64, SLOT = 64, SLOTS = 32, NAME_AT = 32, NAME_BYTES = 32;
	private static final byte[] MAGIC = "MCRLBC01".getBytes(StandardCharsets.US_ASCII);
	private static final int STATE_AT = 8, PID_AT = 16, START_AT = 24;
	private static final int CLEAN = 0, RUNNING = 1;
	private static final Op[] OPS = Op.values();

	private static volatile @Nullable MappedByteBuffer map;
	private static volatile int generation;
	private static volatile AtomicIntegerArray owned = new AtomicIntegerArray(SLOTS);

	private static final class Mine {
		int generation = -1;
		int at;
		int index = -1;
		boolean owner;
	}

	private static final ThreadLocal<Mine> MINE = ThreadLocal.withInitial(Mine::new);

	private Breadcrumb() {
	}

	public static synchronized @Nullable Previous open(Path gameDir) {
		map = null;
		Path file = gameDir.resolve(".mcrl").resolve("breadcrumb");
		Previous found = null;
		try {
			if (Files.isRegularFile(file)) {
				try {
					byte[] old = Files.readAllBytes(file);
					if (old.length == SIZE) {
						found = crashIn(ByteBuffer.wrap(old).order(ByteOrder.LITTLE_ENDIAN), ProcessHandle.current().pid());
					}
				} catch (IOException e) {
					LOG.warn("mcrl: could not read the old crash breadcrumb {}: {}", file, e.toString());
				}
			}
			Files.createDirectories(file.getParent());
			MappedByteBuffer m;
			try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
					StandardOpenOption.WRITE)) {
				ch.truncate(SIZE);
				m = ch.map(FileChannel.MapMode.READ_WRITE, 0, SIZE);
			}
			m.order(ByteOrder.LITTLE_ENDIAN);
			for (int i = 0; i < SIZE; i += 8) {
				m.putLong(i, 0L);
			}
			m.put(0, MAGIC);
			m.putLong(PID_AT, ProcessHandle.current().pid());
			m.putLong(START_AT, System.currentTimeMillis());
			m.putInt(STATE_AT, RUNNING);
			owned = new AtomicIntegerArray(SLOTS);
			generation++;
			map = m;
		} catch (IOException | RuntimeException e) {
			LOG.warn("mcrl: crash breadcrumb disabled: cannot map {}: {}", file, e.toString());
		}
		return found;
	}

	static @Nullable Previous crashIn(ByteBuffer b, long ourPid) {
		if (b.capacity() != SIZE) {
			return null;
		}
		for (int i = 0; i < MAGIC.length; i++) {
			if (b.get(i) != MAGIC[i]) {
				return null;
			}
		}
		if (b.getInt(STATE_AT) != RUNNING) {
			return null;
		}
		long pid = b.getLong(PID_AT);
		if (pid == ourPid || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
			return null;
		}
		Previous newest = null;
		for (int s = 0; s < SLOTS; s++) {
			int at = SLOTS_AT + s * SLOT;
			int op = b.getInt(at);
			if (op == 0) {
				continue;
			}
			if (op < 0 || op >= OPS.length) {
				return null;
			}
			long millis = b.getLong(at + 24);
			if (newest == null || millis > newest.millis()) {
				newest = new Previous(OPS[op], b.getInt(at + 4), b.getInt(at + 8), b.getInt(at + 12), b.getLong(at + 16),
						millis, name(b, at + NAME_AT));
			}
		}
		return newest;
	}

	private static String name(ByteBuffer b, int at) {
		StringBuilder s = new StringBuilder(NAME_BYTES);
		for (int i = 0; i < NAME_BYTES; i++) {
			byte c = b.get(at + i);
			if (c == 0) {
				break;
			}
			s.append(c >= 0x20 && c < 0x7f ? (char) c : '?');
		}
		return s.toString();
	}

	public static void enter(Op op, int x, int y, int z, long tick) {
		MappedByteBuffer m = map;
		if (m == null) {
			return;
		}
		int at = slot(m);
		m.putInt(at + 4, x);
		m.putInt(at + 8, y);
		m.putInt(at + 12, z);
		m.putLong(at + 16, tick);
		m.putLong(at + 24, System.currentTimeMillis());
		m.putInt(at, op.ordinal());
	}

	public static void exit() {
		MappedByteBuffer m = map;
		if (m == null) {
			return;
		}
		Mine mine = MINE.get();
		if (mine.generation == generation) {
			m.putInt(mine.at, 0);
		}
	}

	public static void release() {
		Mine mine = MINE.get();
		MappedByteBuffer m = map;
		if (m != null && mine.generation == generation) {
			m.putInt(mine.at, 0);
			if (mine.owner) {
				owned.set(mine.index, 0);
			}
		}
		mine.generation = -1;
	}

	public static void markClean() {
		MappedByteBuffer m = map;
		if (m != null) {
			m.putInt(STATE_AT, CLEAN);
		}
	}

	public static boolean enabled() {
		return map != null;
	}

	static synchronized void disable() {
		map = null;
		generation++;
	}

	private static int slot(MappedByteBuffer m) {
		Mine mine = MINE.get();
		int g = generation;
		if (mine.generation == g) {
			return mine.at;
		}
		AtomicIntegerArray own = owned;
		int index = SLOTS - 1;
		boolean owner = false;
		for (int i = 0; i < SLOTS; i++) {
			if (own.compareAndSet(i, 0, 1)) {
				index = i;
				owner = true;
				break;
			}
		}
		int at = SLOTS_AT + index * SLOT;
		String n = Thread.currentThread().getName();
		for (int i = 0; i < NAME_BYTES; i++) {
			char c = i < n.length() ? n.charAt(i) : 0;
			m.put(at + NAME_AT + i, (byte) (c < 0x80 ? c : '?'));
		}
		mine.generation = g;
		mine.at = at;
		mine.index = index;
		mine.owner = owner;
		return at;
	}
}
