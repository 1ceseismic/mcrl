package dev.mcrl.rlassets;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolRunnerTest {
	@TempDir
	Path tmp;

	Path script(String body) throws IOException {
		Path f = tmp.resolve("tool-" + System.nanoTime() + ".sh");
		Files.writeString(f, "#!/bin/bash\n" + body + "\n");
		Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
		return f;
	}

	@Test
	void childIsHeadlessWithNullStdin() throws Exception {
		Path work = Files.createDirectories(tmp.resolve("work"));
		Path dump = tmp.resolve("dump.txt");
		Path tool = script("{ echo \"ARGV:$*\"; env; echo \"STDIN:$(readlink /proc/self/fd/0)\"; "
				+ "echo \"NICE:$(ps -o ni= -p $$ | tr -d '[:space:]')\"; } > " + dump);
		ToolRunner r = new ToolRunner(work, null);
		ToolRunner.ToolResult res = r.run(List.of(tool.toString(), "a", "b"), work.resolve("sub"), Duration.ofSeconds(20));
		assertEquals(0, res.exit());
		assertFalse(res.timedOut());
		String d = Files.readString(dump);
		assertTrue(d.contains("ARGV:a b"));
		assertFalse(d.matches("(?s).*(^|\\n)DISPLAY=.*"));
		assertFalse(d.matches("(?s).*(^|\\n)WAYLAND_DISPLAY=.*"));
		assertTrue(d.contains("\nSDL_VIDEODRIVER=dummy\n"));
		assertTrue(d.contains("\nALSOFT_DRIVERS=null\n"));
		assertTrue(d.contains("STDIN:/dev/null"));
		assertTrue(d.contains("NICE:19"), d);
	}

	@Test
	void cwdOnRlVolumeIsRefused() throws Exception {
		Path work = Files.createDirectories(tmp.resolve("work"));
		Path install = Files.createDirectories(tmp.resolve("install"));
		ToolRunner r = new ToolRunner(work, install);
		assertThrows(IllegalArgumentException.class, () -> r.run(List.of("true"), work, Duration.ofSeconds(5)));
	}

	@Test
	void sleepingToolIsKilledAtTimeout() throws Exception {
		Path work = Files.createDirectories(tmp.resolve("work"));
		Path tool = script("sleep 60");
		ToolRunner r = new ToolRunner(work, null);
		long t0 = System.nanoTime();
		ToolRunner.ToolResult res = r.run(List.of(tool.toString()), work, Duration.ofMillis(500));
		assertTrue(res.timedOut());
		assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
	}

	@Test
	void symlinkEscapeIsRefusedAndNothingIsCreated() throws Exception {
		Path work = Files.createDirectories(tmp.resolve("work"));
		Path outside = Files.createDirectories(tmp.resolve("outside"));
		Files.createSymbolicLink(work.resolve("link"), outside);
		ToolRunner r = new ToolRunner(work, null);
		assertThrows(IllegalArgumentException.class,
				() -> r.run(List.of("true"), work.resolve("link/new"), Duration.ofSeconds(5)));
		assertFalse(Files.exists(outside.resolve("new")));
	}

	@Test
	void shutdownKillsRunningToolsWithTheirChildren() throws Exception {
		Path work = Files.createDirectories(tmp.resolve("work"));
		Path pids = tmp.resolve("pids");
		Path tool = script("sleep 300 & echo $! > " + pids + ".tmp; echo $$ >> " + pids + ".tmp; mv " + pids + ".tmp " + pids
				+ "; wait");
		ToolRunner r = new ToolRunner(work, null);
		java.util.concurrent.CompletableFuture<Throwable> run = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				r.run(List.of(tool.toString()), work, Duration.ofMinutes(5));
				return null;
			} catch (Throwable t) {
				return t;
			}
		});
		try {
			long deadline = System.nanoTime() + 20_000_000_000L;
			while (!Files.exists(pids) && System.nanoTime() < deadline) {
				Thread.sleep(20);
			}
			List<Long> ids = Files.readAllLines(pids).stream().map(Long::parseLong).toList();
			assertEquals(2, ids.size());
			assertTrue(ids.stream().allMatch(ToolRunnerTest::alive));
			long t0 = System.nanoTime();
			assertEquals(1, ToolRunner.shutdown());
			Throwable t = run.get(20, java.util.concurrent.TimeUnit.SECONDS);
			assertInstanceOf(InterruptedException.class, t);
			assertTrue((System.nanoTime() - t0) / 1_000_000 < 10_000);
			for (long pid : ids) {
				long until = System.nanoTime() + 5_000_000_000L;
				while (alive(pid) && System.nanoTime() < until) {
					Thread.sleep(20);
				}
				assertFalse(alive(pid), "pid " + pid + " outlived the shutdown");
			}
			assertThrows(InterruptedException.class, () -> r.run(List.of("true"), work, Duration.ofSeconds(5)),
					"nothing starts once the game is stopping");
		} finally {
			ToolRunner.reopen();
		}
		assertEquals(0, r.run(List.of("true"), work, Duration.ofSeconds(5)).exit());
	}

	private static boolean alive(long pid) {
		return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}
}
