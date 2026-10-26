package dev.mcrl.client.rl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.bodies.BodyCacheWriter;
import dev.mcrl.rlassets.bodies.BodyConverter;
import dev.mcrl.rlassets.bodies.BodyIndex;
import dev.mcrl.rlassets.bodies.BodyScanner;
import dev.mcrl.rlassets.bodies.BodyThumbs;

final class BodyTools {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");

	private BodyTools() {
	}

	static Converter.ConvertResult scan(Path umodel, LocateResult loc, Path cacheDir, Consumer<String> progress) {
		if (!Files.isRegularFile(umodel) || !Files.isExecutable(umodel)) {
			return new Converter.ConvertResult(false, "umodel not found at " + umodel, Converter.Failure.TOOL_MISSING);
		}
		UmodelRunner runner = new UmodelRunner(umodel, loc.cookedDir());
		try {
			BodyIndex index = BodyScanner.scan(loc, runner, runner.workDir().resolve("bodyscan-"
					+ ProcessHandle.current().pid()), progress);
			BodyCacheWriter.writeIndex(cacheDir, index, guard(loc));
			return new Converter.ConvertResult(true, index.bodies().size() + " bodies listed", Converter.Failure.NONE);
		} catch (BodyScanner.ToolFailedException e) {
			return new Converter.ConvertResult(false, e.getMessage(), Converter.Failure.TOOL_MISSING);
		} catch (IOException e) {
			return new Converter.ConvertResult(false, "the body scan failed: " + e.getMessage(), Converter.Failure.IO);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Converter.ConvertResult(false, "the body scan was interrupted", Converter.Failure.IO);
		} catch (RuntimeException e) {
			return new Converter.ConvertResult(false, "the body scan failed: " + e, Converter.Failure.IO);
		}
	}

	static Converter.ConvertResult convert(Path umodel, LocateResult loc, Path cacheDir, String id,
			Consumer<String> progress) {
		if (!Files.isRegularFile(umodel) || !Files.isExecutable(umodel)) {
			return new Converter.ConvertResult(false, "umodel not found at " + umodel, Converter.Failure.TOOL_MISSING);
		}
		if (loc.cookedDir() == null) {
			return new Converter.ConvertResult(false, loc.hint(), Converter.Failure.RL_NOT_FOUND);
		}
		try {
			return BodyConverter.convert(loc, new UmodelRunner(umodel, loc.cookedDir()), id, cacheDir, progress);
		} catch (RuntimeException e) {
			return new Converter.ConvertResult(false, "the body conversion failed: " + e, Converter.Failure.IO);
		}
	}

	static @Nullable Path thumbnail(Path umodel, LocateResult loc, Path cacheDir, String id) {
		if (!Files.isRegularFile(umodel) || !Files.isExecutable(umodel) || loc.cookedDir() == null) {
			return null;
		}
		try {
			return BodyThumbs.export(new UmodelRunner(umodel, loc.cookedDir()), loc.cookedDir(), id, cacheDir,
					guard(loc));
		} catch (IOException | RuntimeException e) {
			LOG.warn("mcrl: RL body {} thumbnail not exported: {}", id, e.toString());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static CacheWriter.Guard guard(LocateResult loc) {
		return CacheWriter.Guard.standard(loc.installDir() != null ? loc.installDir() : loc.cookedDir());
	}
}
