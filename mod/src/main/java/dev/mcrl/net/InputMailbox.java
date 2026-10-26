package dev.mcrl.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public final class InputMailbox {
	public static final InputMailbox SERVER = new InputMailbox(SimHosts::get);

	private record Binding(ResourceKey<Level> dimension, int carId) {
	}

	private final Function<ResourceKey<Level>, @Nullable SimHost> hosts;
	private final Map<UUID, Binding> bindings = new ConcurrentHashMap<>();

	public InputMailbox(Function<ResourceKey<Level>, @Nullable SimHost> hosts) {
		this.hosts = hosts;
	}

	public void bind(UUID player, ResourceKey<Level> dimension, int carId) {
		bindings.put(player, new Binding(dimension, carId));
	}

	public void unbind(UUID player) {
		bindings.remove(player);
	}

	public void clear() {
		bindings.clear();
	}

	public int boundCar(UUID player) {
		Binding b = bindings.get(player);
		return b == null ? -1 : b.carId();
	}

	public void receive(UUID player, InputC2S in) {
		bindings.computeIfPresent(player, (k, b) -> {
			SimHost h = hosts.apply(b.dimension());
			if (h != null && !h.isClosed()) {
				h.submitInputs(b.carId(), in.firstTick(), in.epoch(), in.ticks());
			}
			return b;
		});
	}
}
