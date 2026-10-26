package dev.mcrl.client.rl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.bodies.BodyConverter;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.rlassets.bodies.BodyIndex;
import dev.mcrl.rlassets.bodies.BodyScanner;

public final class BodyJobs {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final String CONVERTING = "Converting the %s body from your RL install…";
	private static final String READY = "%s body ready";
	private static final String FAILED = "%s body could not be converted: %s";

	private static final long POLL_MILLIS = 1000;
	private static final BodyJobs GAME = new BodyJobs();
	private final Executor worker = Executors.newSingleThreadExecutor(
			r -> Thread.ofPlatform().daemon().name("mcrl-bodyconvert").unstarted(r));
	private final Map<String, BodyStatus> status = new ConcurrentHashMap<>();
	private final Map<String, String> failures = new ConcurrentHashMap<>();
	private final Set<String> requested = ConcurrentHashMap.newKeySet();
	private final Set<String> reconverted = ConcurrentHashMap.newKeySet();
	private final Set<String> said = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean scanned = new AtomicBoolean();
	private final Set<String> thumbs = ConcurrentHashMap.newKeySet();
	private final Deque<Job> jobs = new ArrayDeque<>(), thumbJobs = new ArrayDeque<>();
	private boolean pumping;

	private record Job(@Nullable String id, @Nullable Consumer<Path> done) {
		Job(@Nullable String id) {
			this(id, null);
		}
	}

	private BodyJobs() {
	}

	public static BodyJobs game() {
		return GAME;
	}

	public void request(String id) {
		if (!BodyIds.valid(id) || BodyIds.isDefault(id) || !requested.add(id)) {
			return;
		}
		RlCache rl = RlConversion.game().cache();
		boolean loaded = rl != null && rl.bodies().look(id) != null;
		if (!RlConversion.game().conversionEnabled()) {
			status.put(id, loaded ? BodyStatus.READY : BodyStatus.UNAVAILABLE);
			return;
		}
		status.put(id, loaded ? BodyStatus.READY : BodyStatus.QUEUED);
		enqueue(jobs, new Job(id));
	}

	public void scan() {
		if (RlConversion.game().conversionEnabled() && scanned.compareAndSet(false, true)) {
			enqueue(jobs, new Job(null));
		}
	}

	public void thumbnail(String id, Consumer<Path> done) {
		if (BodyIds.valid(id) && thumbs.add(id)) {
			enqueue(thumbJobs, new Job(id, done));
		}
	}

	public BodyStatus status(String id) {
		if (BodyIds.isDefault(id)) {
			return BodyStatus.READY;
		}
		RlCache rl = RlConversion.game().cache();
		if (rl != null && rl.bodies().look(id) != null && status.get(id) != BodyStatus.CONVERTING) {
			return BodyStatus.READY;
		}
		return status.getOrDefault(id, BodyStatus.UNKNOWN);
	}

	public void forgetThumbnails() {
		thumbs.clear();
	}

	public void newSession() {
		requested.clear();
		reconverted.clear();
		thumbs.clear();
		failures.clear();
		said.clear();
		status.clear();
		scanned.set(false);
	}

	private void enqueue(Deque<Job> queue, Job j) {
		synchronized (this) {
			queue.add(j);
			if (pumping) {
				return;
			}
			pumping = true;
		}
		worker.execute(this::pump);
	}

	private void pump() {
		if (RlConversion.game().isRunning()) {
			CompletableFuture.delayedExecutor(POLL_MILLIS, TimeUnit.MILLISECONDS, worker).execute(this::pump);
			return;
		}
		Job j;
		synchronized (this) {
			j = jobs.poll();
			if (j == null) {
				j = thumbJobs.poll();
			}
			if (j == null) {
				pumping = false;
				return;
			}
		}
		try {
			if (j.done() != null) {
				runThumbnail(j.id(), j.done());
			} else if (j.id() == null) {
				runScan();
			} else {
				evaluate(j.id());
			}
		} catch (RuntimeException e) {
			LOG.warn("mcrl: RL body job failed", e);
			if (j.id() != null) {
				fail(j.id(), e.toString());
			}
		}
		synchronized (this) {
			if (jobs.isEmpty() && thumbJobs.isEmpty()) {
				pumping = false;
				return;
			}
		}
		worker.execute(this::pump);
	}

	private void runThumbnail(String id, Consumer<Path> done) {
		RlCache rl = RlConversion.game().cache();
		if (rl == null) {
			return;
		}
		Path p = BodyIds.thumb(rl.dir(), id);
		if (!Files.isRegularFile(p)) {
			if (!RlConversion.game().conversionEnabled()) {
				return;
			}
			LocateResult loc = RlConversion.game().host().locate();
			p = loc.state() == LocateResult.State.FOUND ? BodyTools.thumbnail(umodel(), loc, rl.dir(), id) : null;
		}
		if (p != null) {
			done.accept(p);
		}
	}

	private void runScan() {
		RlCache rl = RlConversion.game().cache();
		LocateResult loc = RlConversion.game().host().locate();
		if (rl == null || loc.state() != LocateResult.State.FOUND) {
			return;
		}
		scan(rl, loc);
	}

	private void scan(RlCache rl, LocateResult loc) {
		Converter.ConvertResult r = BodyTools.scan(umodel(), loc, rl.dir(), s -> LOG.info("mcrl: {}", s));
		if (r.ok()) {
			rl.bodies().reloadIndex();
		} else {
			LOG.warn("mcrl: RL body scan failed: {}", r.message());
		}
	}

	private void evaluate(String id) {
		RlCache rl = RlConversion.game().cache();
		if (rl == null) {
			status.put(id, BodyStatus.UNAVAILABLE);
			return;
		}
		boolean loaded = rl.bodies().look(id) != null;
		LocateResult loc = RlConversion.game().host().locate();
		if (loc.state() != LocateResult.State.FOUND) {
			status.put(id, loaded ? BodyStatus.READY : BodyStatus.UNAVAILABLE);
			return;
		}
		if (loaded) {
			if (!BodyConverter.isCurrent(loc, rl.dir(), id) && reconverted.add(id)) {
				convert(rl, loc, id);
			} else {
				status.put(id, BodyStatus.READY);
			}
			return;
		}
		BodyIndex index = rl.bodies().index();
		if ((index == null || !index.fingerprint().equals(BodyScanner.fingerprint(loc)) || index.get(id) == null)
				&& scanned.compareAndSet(false, true)) {
			scan(rl, loc);
			index = rl.bodies().index();
		}
		BodyIndex.Entry e = index == null ? null : index.get(id);
		if (e == null) {
			status.put(id, BodyStatus.UNAVAILABLE);
			return;
		}
		if (!e.supported()) {
			status.put(id, BodyStatus.UNSUPPORTED);
			return;
		}
		if (failures.containsKey(id)) {
			status.put(id, BodyStatus.FAILED);
			return;
		}
		convert(rl, loc, id);
	}

	private void convert(RlCache rl, LocateResult loc, String id) {
		String label = BodyIds.label(id);
		status.put(id, BodyStatus.CONVERTING);
		sayOnce(id + ":converting", String.format(CONVERTING, label));
		Converter.ConvertResult r = BodyTools.convert(umodel(), loc, rl.dir(), id, s -> LOG.info("mcrl: {}: {}", id, s));
		if (!r.ok()) {
			fail(id, r.message());
			return;
		}
		try {
			rl.bodies().load(id);
		} catch (IOException | RuntimeException e) {
			fail(id, "the converted body does not read: " + e.getMessage());
			return;
		}
		failures.remove(id);
		status.put(id, BodyStatus.READY);
		sayOnce(id + ":ready", String.format(READY, label));
	}

	private void fail(String id, String why) {
		failures.put(id, why);
		status.put(id, BodyStatus.FAILED);
		LOG.warn("mcrl: RL body {} could not be converted: {}", id, why);
		sayOnce(id + ":failed", String.format(FAILED, BodyIds.label(id), why));
	}

	private static Path umodel() {
		return RlConversion.game().host().umodelBinary();
	}

	private void sayOnce(String key, String line) {
		if (said.add(key)) {
			RlConversion.game().say(line);
		}
	}
}
