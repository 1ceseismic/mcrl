package dev.mcrl.client.sound.ak;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.wwise.HircJson;
import dev.mcrl.rlassets.wwise.HircModel;
import dev.mcrl.rlassets.wwise.HircModel.Bus;
import dev.mcrl.rlassets.wwise.HircModel.Node;
import dev.mcrl.rlassets.wwise.HircModel.SwitchMap;

public final class SoundModel {
	private final HircModel raw;
	private final Map<Long, List<Node>> chains = new HashMap<>();
	private final Map<Long, Long> outputBuses = new HashMap<>();
	private final Map<Long, List<Bus>> busChains = new HashMap<>();
	private final @Nullable Bus hdrBus;
	private final double belowHdrDb;

	private SoundModel(HircModel raw) {
		this.raw = raw;
		for (Node n : raw.nodes().values()) {
			List<Node> chain = new ArrayList<>();
			Set<Long> seen = new LinkedHashSet<>();
			long bus = 0;
			for (Node c = n; c != null && seen.add(c.id()); c = raw.nodes().get(c.parent())) {
				chain.add(c);
				if (bus == 0) {
					bus = c.bus();
				}
			}
			chains.put(n.id(), List.copyOf(chain));
			outputBuses.put(n.id(), bus);
		}
		for (Bus b : raw.buses().values()) {
			List<Bus> chain = new ArrayList<>();
			Set<Long> seen = new LinkedHashSet<>();
			for (Bus c = b; c != null && seen.add(c.id()); c = raw.buses().get(c.parent())) {
				chain.add(c);
			}
			busChains.put(b.id(), List.copyOf(chain));
		}
		Bus hdr = null;
		for (long out : new LinkedHashSet<>(outputBuses.values())) {
			for (Bus b : busChain(out)) {
				if (b.hdr() != null && hdr == null) {
					hdr = b;
				}
			}
		}
		hdrBus = hdr;
		double below = 0;
		if (hdr != null) {
			for (Bus b : busChain(hdr.parent())) {
				below += b.props().getOrDefault("Volume", 0.0) + b.props().getOrDefault("BusVolume", 0.0);
			}
		}
		belowHdrDb = below;
	}

	public static SoundModel load(Path hircJson) throws IOException {
		return new SoundModel(HircJson.read(hircJson));
	}

	public HircModel raw() {
		return raw;
	}

	public HircModel.@Nullable Event event(long id) {
		return raw.events().get(id);
	}

	public @Nullable Node node(long id) {
		return raw.nodes().get(id);
	}

	public List<Node> chain(long nodeId) {
		return chains.getOrDefault(nodeId, List.of());
	}

	public long outputBus(long nodeId) {
		return outputBuses.getOrDefault(nodeId, 0L);
	}

	public List<Bus> busChain(long busId) {
		return busChains.getOrDefault(busId, List.of());
	}

	public double staticCents(long leafId) {
		double c = 0;
		for (Node n : chain(leafId)) {
			c += n.props().getOrDefault("Pitch", 0.0);
		}
		return c;
	}

	public @Nullable Bus hdrBus() {
		return hdrBus;
	}

	public double belowHdrDb() {
		return belowHdrDb;
	}

	public long switchFromRtpc(long group, double value) {
		SwitchMap m = raw.switchFromRtpc().get(group);
		if (m == null || m.points().isEmpty()) {
			return 0;
		}
		double[] pick = m.points().get(0);
		for (double[] p : m.points()) {
			if (p[0] <= value) {
				pick = p;
			}
		}
		return (long) pick[1];
	}
}
