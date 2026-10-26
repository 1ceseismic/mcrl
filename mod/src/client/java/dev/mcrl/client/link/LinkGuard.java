package dev.mcrl.client.link;

import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

final class LinkGuard {
	private static final Object NO_SESSION = new Object();

	private final Consumer<RuntimeException> report;
	private final Runnable onFailure;
	private @Nullable Object idleFor;
	private boolean idleBodyReported;

	LinkGuard(Consumer<RuntimeException> report, Runnable onFailure) {
		this.report = report;
		this.onFailure = onFailure;
	}

	void run(@Nullable Object session, Runnable frame, Runnable whileIdle) {
		Object key = session == null ? NO_SESSION : session;
		if (idleFor != null) {
			if (idleFor == key) {
				runIdle(whileIdle);
				return;
			}
			idleFor = null;
		}
		try {
			frame.run();
		} catch (RuntimeException e) {
			idleFor = key;
			idleBodyReported = false;
			report.accept(e);
			try {
				onFailure.run();
			} catch (RuntimeException e2) {
				report.accept(e2);
			}
		}
	}

	private void runIdle(Runnable whileIdle) {
		try {
			whileIdle.run();
		} catch (RuntimeException e) {
			if (!idleBodyReported) {
				idleBodyReported = true;
				report.accept(e);
			}
		}
	}

	boolean idle() {
		return idleFor != null;
	}
}
