package dev.mcrl.client.sound.ak;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.wwise.HircModel.GameParameter;

public final class Globals {
	private final SoundModel model;
	private final Map<Long, Double> rtpcs = new HashMap<>();
	private final Map<Long, Long> states = new HashMap<>();
	final Set<GameObject> ramping = new LinkedHashSet<>();

	public Globals(SoundModel model) {
		this.model = model;
	}

	public double rtpc(long param) {
		Double v = rtpcs.get(param);
		return v != null ? v : defaultOf(param);
	}

	public void setRtpc(long param, double v) {
		rtpcs.put(param, v);
	}

	public void clearRtpc(long param) {
		rtpcs.remove(param);
	}

	public void setState(long group, long state) {
		states.put(group, state);
	}

	public long state(long group) {
		return states.getOrDefault(group, 0L);
	}

	private double defaultOf(long param) {
		GameParameter g = parameter(param);
		return g == null ? 0 : g.defaultValue();
	}

	public @Nullable GameParameter parameter(long param) {
		return model.raw().gameParameters().get(param);
	}

	public SoundModel model() {
		return model;
	}
}
