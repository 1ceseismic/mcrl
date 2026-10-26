package dev.mcrl.rlassets.wwise;

import dev.mcrl.rlassets.Pcm;
import dev.mcrl.rlassets.PcmOps;
import dev.mcrl.rlassets.ToolRunner;
import dev.mcrl.rlassets.VgmstreamRunner;
import dev.mcrl.rlassets.WavIo;
import dev.mcrl.rlassets.wwise.HircModel.Action;
import dev.mcrl.rlassets.wwise.HircModel.Event;
import dev.mcrl.rlassets.wwise.HircModel.Kind;
import dev.mcrl.rlassets.wwise.HircModel.Media;
import dev.mcrl.rlassets.wwise.HircModel.Node;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class HircExport {
	public static final String MODEL_FILE = "sounds/hirc.json";
	public static final int LOOP_FADE_MS = 10;

	private HircExport() {
	}

	public record Result(HircModel model, List<String> missing, List<String> warnings, int banks) {
	}

	public static HircModel model(List<HircReader.Bank> banks) {
		return select(HircReader.merge(banks), new ArrayList<>());
	}

	static HircModel select(HircModel all, List<String> warnings) {
		Map<Long, Event> events = new LinkedHashMap<>();
		Deque<Long> todo = new ArrayDeque<>();
		for (String name : SoundEvents.EVENTS) {
			Event e = all.events().get(Fnv.hash(name));
			if (e == null) {
				warnings.add("sound event " + name + " is in no bank");
				continue;
			}
			events.put(e.id(), e);
			for (Action a : e.actions()) {
				todo.add(a.target());
			}
		}
		Set<Long> reached = new HashSet<>();
		for (long target : List.copyOf(todo)) {
			for (Node n = all.nodes().get(target); n != null && n.parent() != 0; n = all.nodes().get(n.parent())) {
				if (!reached.add(n.parent())) {
					break;
				}
			}
		}
		Set<Long> down = new HashSet<>();
		while (!todo.isEmpty()) {
			long id = todo.pop();
			Node n = all.nodes().get(id);
			if (n != null && down.add(id)) {
				todo.addAll(n.children());
			}
		}
		reached.addAll(down);
		Map<Long, Node> nodes = new LinkedHashMap<>();
		all.nodes().forEach((id, n) -> {
			if (reached.contains(id)) {
				nodes.put(id, n);
			}
		});
		return new HircModel(Collections.unmodifiableMap(events), Collections.unmodifiableMap(nodes), all.buses(),
				all.attenuations(), all.modulators(), all.gameParameters(), all.switchFromRtpc(), Map.of());
	}

	public static Result export(Path cookedDir, VgmstreamRunner vgm, Path outDir) throws IOException, InterruptedException {
		List<String> missing = new ArrayList<>(), warnings = new ArrayList<>();
		List<HircReader.Bank> banks = new ArrayList<>();
		List<Path> bankFiles = new ArrayList<>();
		for (String file : SoundEvents.bankFiles()) {
			Path bnk = cookedDir.resolve(file);
			if (!Files.isRegularFile(bnk)) {
				warnings.add("sound bank " + file + " not found");
				continue;
			}
			try {
				banks.add(HircReader.read(bnk));
				bankFiles.add(bnk);
			} catch (IOException e) {
				warnings.add("sound bank " + file + " unreadable: " + e.getMessage());
			}
		}
		HircModel m = select(HircReader.merge(banks), warnings);

		Map<Long, Boolean> loops = new LinkedHashMap<>();
		for (Node n : m.nodes().values()) {
			if (n.kind() == Kind.SOUND && n.media() != 0) {
				loops.merge(n.media(), n.props().containsKey("Loop"), Boolean::logicalOr);
			}
		}
		Files.createDirectories(outDir.resolve("sounds"));
		Path scratch = Files.createDirectories(vgm.workDir().resolve("vgmstream").resolve("hirc-" + ProcessHandle.current().pid()));
		Map<Long, Media> media = new LinkedHashMap<>();
		try {
			for (Map.Entry<Long, Boolean> e : loops.entrySet()) {
				long id = e.getKey();
				Path bnk = null;
				int subsong = 0;
				for (int i = 0; i < banks.size() && bnk == null; i++) {
					Integer s = banks.get(i).didx().get(id);
					if (s != null) {
						bnk = bankFiles.get(i);
						subsong = s;
					}
				}
				if (bnk == null) {
					missing.add("media " + id + " (in no bank's DIDX)");
					continue;
				}
				Path wav = scratch.resolve("decode.wav");
				Files.deleteIfExists(wav);
				ToolRunner.ToolResult t = vgm.decode(bnk, subsong, wav);
				if (t.timedOut() || t.exit() != 0 || !Files.isRegularFile(wav)) {
					missing.add("media " + id + " in " + bnk.getFileName() + " (decode "
							+ (t.timedOut() ? "timed out" : "exit " + t.exit()) + ")");
					continue;
				}
				Pcm pcm;
				try {
					pcm = WavIo.read(wav);
				} catch (IOException x) {
					missing.add("media " + id + " in " + bnk.getFileName() + " (unreadable decode: " + x.getMessage() + ")");
					continue;
				}
				if (e.getValue()) {
					pcm = PcmOps.loopCrossfade(pcm, LOOP_FADE_MS);
				}
				String file = "sounds/w" + id + ".wav";
				WavIo.write(outDir.resolve(file), pcm);
				MediaEnvelope.Envelope env = MediaEnvelope.of(pcm);
				media.put(id, new Media(id, file, pcm.channels(), pcm.rate(), pcm.frames(), env.peakDb(), env.env10ms()));
			}
		} finally {
			deleteTree(scratch);
		}
		HircModel out = new HircModel(m.events(), m.nodes(), m.buses(), m.attenuations(), m.modulators(),
				m.gameParameters(), m.switchFromRtpc(), Collections.unmodifiableMap(media));
		HircJson.write(out, outDir.resolve(MODEL_FILE));
		return new Result(out, List.copyOf(missing), List.copyOf(warnings), banks.size());
	}

	private static void deleteTree(Path root) throws IOException {
		if (!Files.exists(root)) {
			return;
		}
		try (Stream<Path> s = Files.walk(root)) {
			for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}
}
