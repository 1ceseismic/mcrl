package dev.mcrl.natives;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

public final class NativeLoader {
	public static final String RESOURCE = "natives/linux-x86_64/libmcrl_rs.so";
	public static final String LIB_NAME = "libmcrl_rs.so";
	public static final String PROPERTY = "mcrl.native";
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static volatile long loadedHash;

	private NativeLoader() {}

	public static SymbolLookup load() {
		String override = System.getProperty(PROPERTY);
		Path path = null;
		try {
			long hash;
			if (override != null && !override.isBlank()) {
				path = Path.of(override);
				hash = hashOf(path);
			} else {
				FabricLoader loader = FabricLoader.getInstance();
				Path res = loader.getModContainer("mcrl")
						.orElseThrow(() -> new IllegalStateException("mod container 'mcrl' not found"))
						.findPath(RESOURCE)
						.orElseThrow(() -> new IllegalStateException("resource " + RESOURCE + " missing from mod jar"));
				byte[] bytes;
				try (InputStream in = Files.newInputStream(res)) {
					bytes = in.readAllBytes();
				}
				byte[] sha = sha256(bytes);
				path = extract(bytes, sha, loader.getGameDir());
				hash = ByteBuffer.wrap(sha).getLong();
			}
			SymbolLookup lookup = SymbolLookup.libraryLookup(path, Arena.global());
			loadedHash = hash;
			return lookup;
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception | LinkageError e) {
			throw new IllegalStateException("failed to load native library " + path, e);
		}
	}

	public static long loadedHash() {
		return loadedHash;
	}

	static long hashOf(Path lib) {
		try {
			return ByteBuffer.wrap(sha256(Files.readAllBytes(lib))).getLong();
		} catch (IOException e) {
			LOG.warn("mcrl: could not hash the native library {}: {}", lib, e.toString());
			return 0;
		}
	}

	private static byte[] sha256(byte[] bytes) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(bytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Path extract(InputStream in, Path gameDir) throws IOException {
		byte[] bytes = in.readAllBytes();
		return extract(bytes, sha256(bytes), gameDir);
	}

	private static Path extract(byte[] bytes, byte[] sha256, Path gameDir) throws IOException {
		Path dir = gameDir.resolve(".mcrl").resolve("natives").resolve(HexFormat.of().formatHex(sha256));
		Path target = dir.resolve(LIB_NAME);
		if (Files.exists(target)) {
			return target;
		}
		Files.createDirectories(dir);
		Path tmp = Files.createTempFile(dir, "libmcrl_rs", ".tmp");
		try {
			Files.write(tmp, bytes);
			if (Files.exists(target)) {
				return target;
			}
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target);
			}
		} catch (java.nio.file.FileAlreadyExistsException e) {
			// Another process won the race; same content.
		} finally {
			Files.deleteIfExists(tmp);
		}
		return target;
	}
}
