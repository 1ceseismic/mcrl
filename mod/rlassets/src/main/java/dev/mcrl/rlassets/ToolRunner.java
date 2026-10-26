package dev.mcrl.rlassets;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ToolRunner {
	public record ToolResult(int exit, String stdoutTail, String stderrTail, boolean timedOut) {
	}

	private static final List<String> REMOVED_ENV = List.of("DISPLAY", "WAYLAND_DISPLAY", "XDG_ACTIVATION_TOKEN", "DESKTOP_STARTUP_ID");
	private static final int TAIL_BYTES = 16 * 1024;

	private static final Set<Process> LIVE = ConcurrentHashMap.newKeySet();
	private static final AtomicBoolean HOOKED = new AtomicBoolean();
	private static volatile boolean stopping;

	private final Path workDir;
	private final Path rlInstall;

	public ToolRunner(Path workDir, Path rlInstall) {
		this.workDir = workDir.toAbsolutePath().normalize();
		this.rlInstall = rlInstall;
	}

	public static ToolRunner standard(Path rlInstall) {
		return new ToolRunner(McrlPaths.workDir(), rlInstall);
	}

	public Path workDir() {
		return workDir;
	}

	static Map<String, String> sanitize(Map<String, String> env) {
		env.keySet().removeAll(REMOVED_ENV);
		env.put("SDL_VIDEODRIVER", "dummy");
		env.put("ALSOFT_DRIVERS", "null");
		return env;
	}

	public static int shutdown() {
		stopping = true;
		List<Process> live = List.copyOf(LIVE);
		live.forEach(ToolRunner::destroy);
		return live.size();
	}

	static void reopen() {
		stopping = false;
	}

	// Descendants first: once orphaned they are no longer found.
	private static void destroy(Process p) {
		p.descendants().forEach(ProcessHandle::destroyForcibly);
		p.destroyForcibly();
	}

	public ToolResult run(List<String> cmd, Path cwd, Duration timeout) throws IOException, InterruptedException {
		Path c = cwd.toAbsolutePath().normalize();
		Path real = realOfExistingPrefix(c);
		if (!c.startsWith(workDir) || !real.startsWith(realOfExistingPrefix(workDir))) {
			throw new IllegalArgumentException("tool cwd " + c + " is outside the work dir " + workDir);
		}
		if (rlInstall != null) {
			Path inst = realOfExistingPrefix(rlInstall.toAbsolutePath().normalize());
			if (Files.getFileStore(existingPrefix(real)).equals(Files.getFileStore(existingPrefix(inst)))) {
				throw new IllegalArgumentException("tool cwd " + c + " is on the RL install's volume");
			}
		}
		Files.createDirectories(c);
		List<String> full = new ArrayList<>();
		full.add("nice");
		full.add("-n");
		full.add("19");
		full.addAll(cmd);
		ProcessBuilder pb = new ProcessBuilder(full).directory(c.toFile());
		sanitize(pb.environment());
		pb.redirectInput(new File("/dev/null"));
		if (HOOKED.compareAndSet(false, true)) {
			Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("mcrl-tool-reaper").unstarted(ToolRunner::shutdown));
		}
		if (stopping) {
			throw new InterruptedException(cmd.getFirst() + " not started: the game is stopping");
		}
		Process p = pb.start();
		LIVE.add(p);
		try {
			if (stopping) { // shutdown may have missed this process
				destroy(p);
			}
			Tail out = new Tail(p.getInputStream());
			Tail err = new Tail(p.getErrorStream());
			Thread to = Thread.ofPlatform().daemon().name("tool-out").start(out);
			Thread te = Thread.ofPlatform().daemon().name("tool-err").start(err);
			boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!done) {
				destroy(p);
				p.waitFor();
			}
			to.join(2000);
			te.join(2000);
			if (stopping) {
				throw new InterruptedException(cmd.getFirst() + " stopped: the game is stopping");
			}
			return new ToolResult(done ? p.exitValue() : -1, out.text(), err.text(), !done);
		} finally {
			LIVE.remove(p);
		}
	}

	private static Path existingPrefix(Path p) {
		Path e = p;
		while (e != null && !Files.exists(e)) {
			e = e.getParent();
		}
		return e != null ? e : p.getRoot();
	}

	private static Path realOfExistingPrefix(Path p) throws IOException {
		Path e = existingPrefix(p);
		Path real = e.toRealPath();
		return real.resolve(e.relativize(p)).normalize();
	}

	private static final class Tail implements Runnable {
		private final InputStream in;
		private final byte[] buf = new byte[TAIL_BYTES];
		private long total;

		Tail(InputStream in) {
			this.in = in;
		}

		@Override
		public void run() {
			byte[] chunk = new byte[4096];
			try {
				int n;
				while ((n = in.read(chunk)) > 0) {
					synchronized (this) {
						for (int i = 0; i < n; i++) {
							buf[(int) (total++ % TAIL_BYTES)] = chunk[i];
						}
					}
				}
			} catch (IOException closedOnKill) {
			}
		}

		synchronized String text() {
			int len = (int) Math.min(total, TAIL_BYTES);
			byte[] o = new byte[len];
			long start = total - len;
			for (int i = 0; i < len; i++) {
				o[i] = buf[(int) ((start + i) % TAIL_BYTES)];
			}
			return new String(o, StandardCharsets.UTF_8);
		}
	}
}
