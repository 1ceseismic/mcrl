package dev.mcrl.entity;

import java.util.regex.Pattern;

import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.sim.HitboxPreset;

public record CarChoice(String bodyId, int preset, int team) {
	public static final Pattern ID_PATTERN = BodyIds.PATTERN;
	public static final CarChoice DEFAULT = new CarChoice("", 0, 0);

	public boolean valid() {
		return bodyId != null && ID_PATTERN.matcher(bodyId).matches() && preset >= 0
				&& preset < HitboxPreset.ALL.size() && (team == 0 || team == 1);
	}
}
