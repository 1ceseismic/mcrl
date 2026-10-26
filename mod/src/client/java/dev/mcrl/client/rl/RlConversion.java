package dev.mcrl.client.rl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.McrlPaths;
import dev.mcrl.rlassets.RlLocator;
import dev.mcrl.rlassets.RlState;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.VgmstreamRunner;

public final class RlConversion {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	public static final String CONVERTING = "Converting Rocket League assets from your install…";
	private static final String READY = "Rocket League assets ready";
	private static final String STADIUM_READY = "Stadium converted from your Rocket League install";
	private static final String STADIUM_FAILED = "Stadium not converted";
	static final String UNAVAILABLE = "Rocket League assets unavailable";
	static final String RETRY = "then open mcrl settings (M) > Setup and press Convert now (or run /mcrl visuals rl again)";
	private static final int MAX_MESSAGE = 400;

	public interface Host {
		Path cacheDir();

		boolean conversionEnabled();

		LocateResult locate();

		@Nullable String decoder(LocateResult loc);

		RlState.@Nullable Failed failedConversion();

		void rememberFailedConversion(RlState.@Nullable Failed f);

		Converter.ConvertResult convert(LocateResult loc, Path cacheDir, Consumer<String> progress) throws Exception;

		Converter.ConvertResult convertStadium(LocateResult loc, Path cacheDir, Consumer<String> progress) throws Exception;

		void stadiumConverted();

		RlCache load(Path dir) throws Exception;

		Path umodelBinary();

		Path vgmstreamBinary();

		String affected();
	}

	private static final RlConversion GAME =
			new RlConversion(new SystemHost(), r -> Thread.ofPlatform().daemon().name("mcrl-rlconvert").start(r));

	private final Host host;
	private final Executor executor;
	private final AtomicBoolean started = new AtomicBoolean();
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile boolean stadium;
	private volatile boolean retryable;
	private volatile @Nullable String fallbackWhy;
	private boolean restated;
	private volatile @Nullable RlCache cache;
	private volatile @Nullable Path cacheLoadedFrom;
	private final Set<String> said = new HashSet<>();
	private final List<String> queue = new ArrayList<>();

	RlConversion(Host host, Executor executor) {
		this.host = host;
		this.executor = executor;
	}

	public static RlConversion game() {
		return GAME;
	}

	public static Visuals effectiveVisuals() {
		Visuals v = McrlConfig.get().visuals;
		if (v == Visuals.RL) {
			game().start();
		}
		return game().effective(v);
	}

	public static void setVisuals(Visuals v) {
		McrlConfig cfg = McrlConfig.get();
		cfg.visuals = v;
		cfg.save();
		if (v == Visuals.RL) {
			game().retry();
		}
	}

	public void start() {
		if (started.compareAndSet(false, true)) {
			launch(false, false);
		}
	}

	public SetupStatus status() {
		return SetupStatus.compute(host);
	}

	public boolean isRunning() {
		return running.get();
	}

	public boolean conversionEnabled() {
		return host.conversionEnabled();
	}

	public boolean convertNow() {
		if (!host.conversionEnabled()) {
			return false;
		}
		started.set(true);
		return launch(true, true);
	}

	public boolean convertStadium() {
		if (!host.conversionEnabled() || running.get()) {
			return false;
		}
		started.set(true);
		stadium = true;
		if (!launch(true, false)) {
			stadium = false;
			return false;
		}
		return true;
	}

	public void retry() {
		if (started.compareAndSet(false, true)) {
			launch(false, false);
		} else if (cache != null || running.get()) {
			return;
		} else if (retryable) {
			launch(true, false);
		} else {
			String why = fallbackWhy;
			String line = why == null ? null : unavailable(host.affected(), why);
			synchronized (queue) {
				if (line != null && !restated) {
					restated = true;
					queue.add(line);
				}
			}
		}
	}

	private boolean launch(boolean again, boolean force) {
		if (!running.compareAndSet(false, true)) {
			return false;
		}
		if (again) {
			synchronized (queue) {
				said.clear();
			}
		}
		try {
			boolean withStadium = stadium;
			executor.execute(() -> {
				try {
					runSafely(force);
					if (withStadium) {
						runStadiumSafely();
					}
				} finally {
					stadium = false;
					running.set(false);
				}
			});
			return true;
		} catch (RuntimeException e) {
			running.set(false);
			LOG.error("mcrl: could not start the RL asset setup", e);
			return false;
		}
	}

	public @Nullable RlCache cache() {
		return cache;
	}

	public Visuals effective(Visuals configured) {
		return configured == Visuals.RL && cache == null ? Visuals.SIMPLE : configured;
	}

	public List<String> takeMessages() {
		synchronized (queue) {
			List<String> out = List.copyOf(queue);
			queue.clear();
			return out;
		}
	}

	Host host() {
		return host;
	}

	public void say(String message) {
		String m = message.length() > MAX_MESSAGE ? message.substring(0, MAX_MESSAGE - 1) + "…" : message;
		synchronized (queue) {
			if (said.add(m)) {
				queue.add(m);
			}
		}
		LOG.info("mcrl: {}", message);
	}

	private void fallBack(String why, boolean canRetry) {
		fallbackWhy = why;
		retryable = canRetry;
		say(unavailable(host.affected(), why));
	}

	static String affected(Visuals visuals, float soundVolume, boolean effects) {
		List<String> parts = new ArrayList<>(3);
		if (visuals == Visuals.RL) {
			parts.add("drawing SIMPLE");
		}
		if (soundVolume > 0f) {
			parts.add("no RL sounds");
		}
		if (effects) {
			parts.add("no RL effects");
		}
		return String.join(", ", parts);
	}

	static String unavailable(String affected, String why) {
		String line = UNAVAILABLE + (affected.isEmpty() ? "" : " (" + affected + ")") + ": " + why;
		return line.length() > MAX_MESSAGE ? line.substring(0, MAX_MESSAGE - 1) + "…" : line;
	}

	private void runSafely(boolean force) {
		try {
			run(force);
		} catch (Throwable t) {
			LOG.error("mcrl: the RL asset setup failed", t);
			if (cache == null) {
				fallBack("internal error " + t, false);
			} else {
				say("Rocket League asset update failed, keeping the current RL assets: internal error " + t);
			}
		}
	}

	private void runStadiumSafely() {
		try {
			Path dir = host.cacheDir();
			if (cache == null) {
				say(STADIUM_FAILED + ": there is no RL cache to add it to");
				return;
			}
			LocateResult loc = host.locate();
			if (loc.state() != LocateResult.State.FOUND) {
				say(STADIUM_FAILED + ": " + loc.hint());
				return;
			}
			Converter.ConvertResult r = host.convertStadium(loc, dir, line -> LOG.info("mcrl rlconvert: {}", line));
			if (r.ok()) {
				LOG.info("mcrl: {}", r.message());
				RlCache c = cache;
				if (c != null) {
					c.resetStadium();
					host.stadiumConverted();
				}
				say(STADIUM_READY);
			} else {
				LOG.warn("mcrl: stadium conversion failed ({}): {}", r.failure(), r.message());
				say(STADIUM_FAILED + ": " + r.message() + " (" + hint(r.failure()) + ")");
			}
		} catch (Throwable t) {
			LOG.error("mcrl: the stadium conversion failed", t);
			say(STADIUM_FAILED + ": internal error " + t);
		}
	}

	private void run(boolean force) throws Exception {
		Path dir = host.cacheDir();
		try {
			for (String w : CacheWriter.recover(dir)) {
				LOG.warn("mcrl: {}", w);
			}
		} catch (Exception e) {
			LOG.warn("mcrl: could not check {} for an interrupted cache write", dir, e);
		}
		String unusable = null;
		boolean loaded = cache != null && dir.equals(cacheLoadedFrom);
		if (!loaded && Files.isRegularFile(dir.resolve("manifest.json"))) {
			try {
				cache = host.load(dir);
				cacheLoadedFrom = dir;
				LOG.info("mcrl: RL cache loaded from {}", dir);
			} catch (Exception e) {
				unusable = "the RL cache at " + dir + " is unusable (" + reason(e) + ")";
				LOG.warn("mcrl: {}", unusable, e);
			}
		}
		String missing = unusable != null ? unusable : "no RL cache at " + dir;
		if (!host.conversionEnabled()) {
			if (cache == null) {
				fallBack(missing + "; conversion is off (-Dmcrl.rlconvert=off)", false);
			} else {
				LOG.info("mcrl: RL conversion is off; drawing the cache as it is");
			}
			return;
		}
		LocateResult loc = host.locate();
		if (loc.state() != LocateResult.State.FOUND) {
			if (cache == null) {
				fallBack(missing + "; " + loc.hint() + ", " + RETRY, true);
			} else {
				LOG.info("mcrl: Rocket League is {} ({}); drawing the RL cache as it is", loc.state(), loc.hint());
			}
			return;
		}
		if (!force && cache != null && Converter.isCurrent(loc, dir, host.decoder(loc))) {
			LOG.info("mcrl: the RL cache matches the install");
			return;
		}
		String fingerprint = Converter.fingerprint(loc);
		RlState.Failed failed = host.failedConversion();
		if (!force && failed != null && failed.fingerprint().equals(fingerprint)) {
			String why = failed.message() + " (" + hint(Converter.Failure.ASSETS_CHANGED) + ")";
			LOG.warn("mcrl: not converting the RL install again: it failed before ({}); {}. To try anyway, delete {} or run "
					+ "the converter with --force", failed.message(), cache == null ? "no RL cache" : "drawing the older RL cache",
					RlState.standardFile());
			if (cache == null) {
				fallBack(why, false);
			}
			return;
		}
		LOG.info("mcrl: converting the RL install ({}) into {} ({})", loc.cookedDir(), dir,
				cache == null ? "no usable cache" : "the cache is stale");
		AtomicBoolean announced = new AtomicBoolean();
		Converter.ConvertResult r = host.convert(loc, dir, line -> {
			if (announced.compareAndSet(false, true)) {
				say(CONVERTING);
			}
			LOG.info("mcrl rlconvert: {}", line);
		});
		if (r.failure() == Converter.Failure.ASSETS_CHANGED) {
			rememberFailed(new RlState.Failed(fingerprint, r.message()));
		} else if (r.ok() && failed != null) {
			rememberFailed(null);
		}
		if (!r.ok()) {
			String why = r.message() + " (" + hint(r.failure()) + ")";
			LOG.warn("mcrl: RL conversion failed ({}): {}", r.failure(), r.message());
			if (cache == null) {
				fallBack(why, retryable(r.failure()));
			} else {
				say("Rocket League asset conversion failed, keeping the older RL assets: " + why);
			}
			return;
		}
		LOG.info("mcrl: {}", r.message());
		try {
			cache = host.load(dir);
			cacheLoadedFrom = dir;
		} catch (Exception e) {
			LOG.warn("mcrl: the converted RL cache at {} does not load", dir, e);
			String why = "the new RL cache at " + dir + " is unusable (" + reason(e) + ")";
			if (cache == null) {
				fallBack(why, false);
			} else {
				say("Rocket League assets not updated: " + why);
			}
			return;
		}
		say(READY);
	}

	private void rememberFailed(RlState.@Nullable Failed f) {
		try {
			host.rememberFailedConversion(f);
		} catch (RuntimeException e) {
			LOG.warn("mcrl: could not save the RL conversion state", e);
		}
	}

	private static String reason(Throwable e) {
		Throwable c = e;
		while (c.getCause() != null && (c.getMessage() == null || c instanceof java.util.concurrent.ExecutionException
				|| c instanceof java.util.concurrent.CompletionException)) {
			c = c.getCause();
		}
		return c.getMessage() != null ? c.getMessage() : c.toString();
	}

	static boolean retryable(Converter.Failure f) {
		return switch (f) {
			case RL_NOT_FOUND, TOOL_MISSING, IO, NONE -> true;
			case ASSETS_CHANGED, VALIDATION, REFUSED -> false;
		};
	}

	static String hint(Converter.Failure f) {
		return switch (f) {
			case RL_NOT_FOUND -> "mount the drive Rocket League is on, " + RETRY
					+ "; or open mcrl settings (M) > Setup and set the Rocket League folder, then press Convert now"
					+ " (rlCookedDir in config/mcrl.json also works)";
			case TOOL_MISSING -> "build umodel and vgmstream-cli with scripts/setup-tools.sh (see the game log), "
					+ RETRY + "; or open mcrl settings (M) > Setup, set the umodel path, then press Convert now"
					+ " (umodelPath in config/mcrl.json also works)";
			case ASSETS_CHANGED -> "Rocket League may have been updated; mcrl needs an update (see the log)";
			case VALIDATION -> "the converted assets failed their checks; see the game log";
			case REFUSED -> "open mcrl settings (M) > Setup, set the RL cache folder to an empty folder, then press Convert now"
					+ " (rlCacheDir in config/mcrl.json also works)";
			case IO, NONE -> "check the cache folder's free space and permissions, " + RETRY;
		};
	}

	private static final class SystemHost implements Host {
		@Override
		public String affected() {
			McrlConfig cfg = McrlConfig.get();
			return RlConversion.affected(cfg.visuals, cfg.soundVolume, cfg.effects.enabled());
		}

		@Override
		public Path umodelBinary() {
			return UmodelRunner.binary(McrlConfig.get().umodelPath);
		}

		@Override
		public Path vgmstreamBinary() {
			return VgmstreamRunner.binary(McrlConfig.get().vgmstreamPath);
		}

		@Override
		public Path cacheDir() {
			return McrlPaths.cacheDir(McrlConfig.get().rlCacheDir);
		}

		@Override
		public boolean conversionEnabled() {
			return !"off".equalsIgnoreCase(System.getProperty("mcrl.rlconvert", "").trim());
		}

		@Override
		public LocateResult locate() {
			String cfg = McrlConfig.get().rlCookedDir;
			LocateResult loc = RlLocator.locate(RlLocator.Env.system(cfg.isBlank() ? null : cfg));
			Path install = loc.installDir() != null ? loc.installDir() : loc.cookedDir();
			if (loc.state() == LocateResult.State.FOUND && install != null) {
				try {
					RlState.rememberInstall(RlState.standardFile(), install);
				} catch (Exception e) {
					LOG.warn("mcrl: could not remember the RL install", e);
				}
			}
			return loc;
		}

		@Override
		public @Nullable String decoder(LocateResult loc) {
			return loc.installDir() == null ? null : VgmstreamRunner.identity(vgmstreamBinary());
		}

		@Override
		public RlState.@Nullable Failed failedConversion() {
			return RlState.failed(RlState.standardFile());
		}

		@Override
		public void rememberFailedConversion(RlState.@Nullable Failed f) {
			try {
				RlState.rememberFailed(RlState.standardFile(), f);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}

		@Override
		public Converter.ConvertResult convert(LocateResult loc, Path cacheDir, Consumer<String> progress) {
			Path umodel = umodelBinary();
			if (!Files.isRegularFile(umodel) || !Files.isExecutable(umodel)) {
				return new Converter.ConvertResult(false, "umodel not found at " + umodel, Converter.Failure.TOOL_MISSING);
			}
			VgmstreamRunner vgm = decoder(loc) != null
					? new VgmstreamRunner(vgmstreamBinary(), loc.installDir()) : null;
			return Converter.convert(loc, new UmodelRunner(umodel, loc.cookedDir()), vgm, cacheDir, progress);
		}

		@Override
		public Converter.ConvertResult convertStadium(LocateResult loc, Path cacheDir, Consumer<String> progress) {
			Path umodel = umodelBinary();
			if (!Files.isRegularFile(umodel) || !Files.isExecutable(umodel)) {
				return new Converter.ConvertResult(false, "umodel not found at " + umodel, Converter.Failure.TOOL_MISSING);
			}
			return Converter.convertStadium(loc, new UmodelRunner(umodel, loc.cookedDir()), cacheDir, false, progress);
		}

		@Override
		public void stadiumConverted() {
			RlTextures.reloadAll();
		}

		@Override
		public RlCache load(Path dir) throws Exception {
			return RlCache.read(dir);
		}
	}
}
