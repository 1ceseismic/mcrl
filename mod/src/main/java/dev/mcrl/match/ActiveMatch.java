package dev.mcrl.match;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.sim.SoccarState;

public final class ActiveMatch {
	private final Match match;
	private final MatchSize size;
	private final UUID creator;
	private final ServerLevel stadium;
	private final Slot[] slots;
	private final Set<UUID> spectators = new LinkedHashSet<>();
	int ballEntityId = -1;
	// By reference: level lookups miss entities in unready chunks.
	final Map<Integer, Entity> bodies = new HashMap<>();
	@Nullable SimHost observedHost;
	int kickoffsSeen = -1, bouncesSeen;
	long lastTick = -1;
	@Nullable SoccarState lastState;
	int @Nullable [] padPickupsSeen;
	final Map<Integer, Integer> hitsSeen = new HashMap<>();
	final Map<Integer, Long> lastHit = new HashMap<>();
	final Set<UUID> setupSent = new HashSet<>();

	ActiveMatch(Match match, UUID creator, ServerLevel stadium) {
		this.match = match;
		this.size = match.size();
		this.creator = creator;
		this.stadium = stadium;
		int n = size.perTeam();
		this.slots = new Slot[2 * n];
		for (int i = 0; i < 2 * n; i++) {
			slots[i] = Slot.empty(i, i < n ? 0 : 1);
		}
	}

	public Match match() {
		return match;
	}

	public MatchSize size() {
		return size;
	}

	public List<Slot> slots() {
		return List.of(slots.clone());
	}

	public Set<UUID> spectators() {
		return Set.copyOf(spectators);
	}

	public UUID creator() {
		return creator;
	}

	public ServerLevel stadium() {
		return stadium;
	}

	public @Nullable SimHost host() {
		return SimHosts.get(StadiumConst.KEY);
	}

	public @Nullable Entity body(int id) {
		return bodies.get(id);
	}

	public int ballEntityId() {
		return ballEntityId;
	}

	public void assignBot(int slot, CarEntity car) {
		Slot s = slots[slot];
		if (!s.open()) {
			throw new IllegalStateException("slot " + slot + " is a player's");
		}
		bodies.put(car.getId(), car);
		set(s.withBot(car.getId()));
	}

	Slot slot(int index) {
		return slots[index];
	}

	void set(Slot s) {
		slots[s.index()] = s;
		setupSent.clear();
	}

	@Nullable Slot slotOf(UUID player) {
		for (Slot s : slots) {
			if (player.equals(s.player())) {
				return s;
			}
		}
		return null;
	}

	Set<UUID> spectatorSet() {
		return spectators;
	}

	List<UUID> everyone() {
		List<UUID> out = new ArrayList<>();
		for (Slot s : slots) {
			if (s.player() != null) {
				out.add(s.player());
			}
		}
		out.addAll(spectators);
		return out;
	}
}
