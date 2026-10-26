package dev.mcrl.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.match.ArenaMeshes;

public final class BotPolicies {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	public record Loaded(Path file, BotPolicy policy) {
	}

	private static @Nullable Loaded cached;
	private static long cachedSize = -1, cachedMtime = -1;

	private BotPolicies() {
	}

	public static synchronized Loaded load(String setting) throws BotPolicyException {
		try {
			List<Path> files = BotPolicy.candidates(path(setting));
			BotPolicyException newest = null;
			for (Path f : files) {
				try {
					Loaded before = cached, l = loadFile(f);
					if (newest != null && l != before) {
						LOG.warn("mcrl: bot policy: {}; using {}", newest.getMessage(), l.file());
					}
					return l;
				} catch (BotPolicyException e) {
					if (newest == null) {
						newest = e;
					}
				}
			}
			throw Objects.requireNonNull(newest);
		} catch (RuntimeException e) {
			throw new BotPolicyException("botPolicy: " + setting + ": " + e, e);
		}
	}

	private static Loaded loadFile(Path file) throws BotPolicyException {
		Path real;
		long size, mtime;
		try {
			real = file.toRealPath();
			size = Files.size(real);
			mtime = Files.getLastModifiedTime(real).toMillis();
		} catch (IOException e) {
			throw new BotPolicyException("botPolicy: " + file + ": unreadable (" + e.getMessage() + ")", e);
		}
		Loaded c = cached;
		if (c != null && c.file().equals(real) && cachedSize == size && cachedMtime == mtime) {
			return c;
		}
		BotPolicy p = BotPolicy.read(real);
		Loaded l = new Loaded(real, p);
		cached = l;
		cachedSize = size;
		cachedMtime = mtime;
		LOG.info("mcrl: bot policy {}: {}, {} parameters", real, dimsText(p), p.paramCount());
		return l;
	}

	public static String describe(String setting) {
		if (setting.isBlank()) {
			return "not set";
		}
		try {
			List<Path> files = BotPolicy.candidates(path(setting));
			BotPolicyException newest = null;
			for (Path f : files) {
				try {
					BotPolicy p = BotPolicy.read(f);
					return name(f) + ": " + dimsText(p) + (newest == null ? "" : " (the newer " + name(files.get(0))
							+ " is unreadable)");
				} catch (BotPolicyException e) {
					if (newest == null) {
						newest = e;
					}
				}
			}
			return Objects.requireNonNull(newest).getMessage();
		} catch (BotPolicyException e) {
			return e.getMessage();
		} catch (RuntimeException e) {
			return "botPolicy: " + setting + ": " + e;
		}
	}

	private static Path path(String setting) throws BotPolicyException {
		if (setting.isBlank()) {
			throw new BotPolicyException("botPolicy: not set (the Match tab of the mcrl settings: a POLICY.lt or a "
					+ "GigaLearn checkpoint folder)");
		}
		try {
			return ArenaMeshes.folder(setting.trim());
		} catch (InvalidPathException e) {
			throw new BotPolicyException("botPolicy: " + setting + ": not a path (" + e.getReason() + ")", e);
		}
	}

	private static String name(Path f) {
		Path parent = f.getParent();
		return (parent != null ? parent.getFileName() + "/" : "") + f.getFileName();
	}

	private static String dimsText(BotPolicy p) {
		return Arrays.stream(p.dims()).mapToObj(Integer::toString).collect(Collectors.joining("-"));
	}
}
