package dev.mcrl.natives;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NativeBoot {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static volatile boolean started;

	private NativeBoot() {
	}

	public static void ensureStarted() {
		if (started) {
			return;
		}
		synchronized (NativeBoot.class) {
			if (started) {
				return;
			}
			LOG.info("mcrl: loading native physics; if Minecraft crashes here, set \"nativeEnabled\": false in "
					+ "config/mcrl.json");
			int abi;
			Breadcrumb.enter(Breadcrumb.Op.INIT, 0, 0, 0, 0);
			try {
				abi = McrlNative.abiVersion();
				if (abi != McrlNative.EXPECTED_ABI_VERSION) {
					throw new IllegalStateException("mcrl: native library ABI version " + abi + ", this mod needs "
							+ McrlNative.EXPECTED_ABI_VERSION
							+ ": rebuild native/ and the mod together");
				}
				try {
					Layouts.verify();
				} catch (ExceptionInInitializerError | IllegalStateException e) {
					Throwable cause = e instanceof ExceptionInInitializerError && e.getCause() != null ? e.getCause() : e;
					throw new IllegalStateException("mcrl: native struct layouts do not match libmcrl_rs.so ("
							+ cause.getMessage() + "): rebuild native/ and the mod together",
							cause);
				}
				int rc = McrlNative.init();
				if (rc != 0) {
					throw new IllegalStateException("mcrl_init failed: " + rc + " " + McrlNative.lastError());
				}
			} finally {
				// This thread never ends: free its breadcrumb slot.
				Breadcrumb.release();
			}
			started = true;
			LOG.info("mcrl: physics library started (abi {})", abi);
		}
	}

	public static boolean started() {
		return started;
	}

	public static long buildHash() {
		return started ? NativeLoader.loadedHash() : 0;
	}
}
