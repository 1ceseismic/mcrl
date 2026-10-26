package dev.mcrl.natives;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.natives.Breadcrumb.Op;
import dev.mcrl.natives.Breadcrumb.Previous;

class BreadcrumbTest {
	private static final long NEVER_A_PID = 4_194_305L;
	private static final int SIZE = 4096, SLOTS_AT = 64, SLOT = 64, SLOTS = 32;

	@TempDir
	Path dir;

	@AfterEach
	void disable() {
		Breadcrumb.disable();
	}

	private Path file() {
		return dir.resolve(".mcrl").resolve("breadcrumb");
	}

	private record Slot(int index, Op op, int x, int y, int z, long tick, long millis, String thread) {
	}

	private void writeOld(String magic, int state, long pid, Slot... slots) throws IOException {
		ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.put(0, magic.getBytes(StandardCharsets.US_ASCII));
		b.putInt(8, state);
		b.putLong(16, pid);
		b.putLong(24, 1_000L);
		for (Slot s : slots) {
			int at = SLOTS_AT + s.index() * SLOT;
			b.putInt(at, s.op().ordinal());
			b.putInt(at + 4, s.x());
			b.putInt(at + 8, s.y());
			b.putInt(at + 12, s.z());
			b.putLong(at + 16, s.tick());
			b.putLong(at + 24, s.millis());
			byte[] name = s.thread().getBytes(StandardCharsets.US_ASCII);
			b.put(at + 32, name, 0, Math.min(32, name.length));
		}
		Files.createDirectories(file().getParent());
		Files.write(file(), b.array());
	}

	private ByteBuffer read() throws IOException {
		return ByteBuffer.wrap(Files.readAllBytes(file())).order(ByteOrder.LITTLE_ENDIAN);
	}

	private static String name(ByteBuffer b, int slot) {
		int at = SLOTS_AT + slot * SLOT + 32;
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 32 && b.get(at + i) != 0; i++) {
			s.append((char) b.get(at + i));
		}
		return s.toString();
	}

	private void assertFreshForUs() throws IOException {
		ByteBuffer b = read();
		assertEquals(SIZE, b.capacity(), "file size");
		byte[] magic = new byte[8];
		b.get(0, magic);
		assertEquals("MCRLBC01", new String(magic, StandardCharsets.US_ASCII));
		assertEquals(1, b.getInt(8), "state running");
		assertEquals(ProcessHandle.current().pid(), b.getLong(16), "our pid");
		assertTrue(Math.abs(System.currentTimeMillis() - b.getLong(24)) < 60_000, "start millis is now");
		for (int i = 0; i < SLOTS; i++) {
			assertEquals(0, b.getInt(SLOTS_AT + i * SLOT), "slot " + i + " idle");
		}
	}

	@Test
	void clean_exit_reports_nothing() throws IOException {
		assertNull(Breadcrumb.open(dir), "no file yet");
		assertTrue(Breadcrumb.enabled());
		assertFreshForUs();
		Breadcrumb.enter(Op.STEP, 1, 2, 3, 40);
		Breadcrumb.exit();
		Breadcrumb.markClean();
		assertEquals(0, read().getInt(8), "markClean writes state 0");
		writeOld("MCRLBC01", 0, NEVER_A_PID, new Slot(0, Op.STEP, 1, 2, 3, 40, 5, "mcrl-sim/minecraft:overworld"));
		assertNull(Breadcrumb.open(dir));
		assertFreshForUs();
	}

	@Test
	void dead_pid_with_busy_slot_reports_op_and_section() throws IOException {
		writeOld("MCRLBC01", 1, NEVER_A_PID,
				new Slot(0, Op.BUILD, 9, 9, 9, 0, 1_000, "mcrl-mesh-0"),
				new Slot(1, Op.STEP, -2, 4, 7, 1234, 2_000, "mcrl-sim/minecraft:overworld"),
				new Slot(2, Op.IDLE, 5, 5, 5, 99, 3_000, "mcrl-mesh-1"));
		Previous p = Breadcrumb.open(dir);
		assertEquals(new Previous(Op.STEP, -2, 4, 7, 1234, 2_000, "mcrl-sim/minecraft:overworld"), p);
		assertFreshForUs();
		writeOld("MCRLBC01", 1, NEVER_A_PID,
				new Slot(31, Op.COMMIT, 1, -1, 1, 7, 9, "mcrl-sim/some:very_long_dimension_name_here"));
		assertEquals(new Previous(Op.COMMIT, 1, -1, 1, 7, 9, "mcrl-sim/some:very_long_dimensio"), Breadcrumb.open(dir));
	}

	@Test
	void live_pid_reports_nothing() throws IOException {
		long parent = ProcessHandle.current().parent().orElseThrow().pid();
		assertTrue(ProcessHandle.of(parent).map(ProcessHandle::isAlive).orElse(false), "the parent is alive");
		writeOld("MCRLBC01", 1, parent, new Slot(0, Op.STEP, 1, 2, 3, 4, 5, "mcrl-sim/minecraft:overworld"));
		assertNull(Breadcrumb.open(dir), "a live process still owns it");
		assertFreshForUs();
		writeOld("MCRLBC01", 1, ProcessHandle.current().pid(), new Slot(0, Op.STEP, 1, 2, 3, 4, 5, "x"));
		assertNull(Breadcrumb.open(dir));
	}

	@Test
	void corrupt_or_short_file_is_rewritten() throws IOException {
		Files.createDirectories(file().getParent());
		Files.write(file(), "garbage".getBytes(StandardCharsets.US_ASCII));
		assertNull(Breadcrumb.open(dir), "short file");
		assertFreshForUs();
		writeOld("NOTMAGIC", 1, NEVER_A_PID, new Slot(0, Op.STEP, 1, 2, 3, 4, 5, "x"));
		assertNull(Breadcrumb.open(dir), "bad magic");
		assertFreshForUs();
		writeOld("MCRLBC01", 7, NEVER_A_PID, new Slot(0, Op.STEP, 1, 2, 3, 4, 5, "x"));
		assertNull(Breadcrumb.open(dir), "unknown state");
		ByteBuffer b = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.put(0, "MCRLBC01".getBytes(StandardCharsets.US_ASCII)).putInt(8, 1).putLong(16, NEVER_A_PID)
				.putInt(SLOTS_AT, 99);
		Files.write(file(), b.array());
		assertNull(Breadcrumb.open(dir), "unknown op");
		assertFreshForUs();
		byte[] longer = new byte[SIZE + 100];
		System.arraycopy(b.array(), 0, longer, 0, SIZE);
		Files.write(file(), longer);
		assertNull(Breadcrumb.open(dir), "wrong size");
		assertFreshForUs();
	}

	@Test
	void unwritable_dir_disables_without_throwing() throws IOException {
		Path ro = dir.resolve("ro");
		Files.createDirectories(ro);
		Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			assertNull(assertDoesNotThrow(() -> Breadcrumb.open(ro)));
			assertFalse(Breadcrumb.enabled());
			assertDoesNotThrow(() -> {
				Breadcrumb.enter(Op.STEP, 1, 2, 3, 4);
				Breadcrumb.exit();
				Breadcrumb.release();
				Breadcrumb.markClean();
			});
			assertFalse(Files.exists(ro.resolve(".mcrl")));
		} finally {
			Files.setPosixFilePermissions(ro, PosixFilePermissions.fromString("rwxr-xr-x"));
		}
		Path plain = dir.resolve("plain");
		Files.createDirectories(plain);
		Files.writeString(plain.resolve(".mcrl"), "not a dir");
		assertNull(assertDoesNotThrow(() -> Breadcrumb.open(plain)));
		assertFalse(Breadcrumb.enabled());
		assertNull(Breadcrumb.open(dir));
		assertTrue(Breadcrumb.enabled());
	}
}
