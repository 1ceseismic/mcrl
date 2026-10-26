package dev.mcrl.client.hud;

import org.jspecify.annotations.Nullable;

public enum MeterTeam {
	NONE(null), BLUE(MeterStyle.TEAM_BLUE), ORANGE(MeterStyle.TEAM_ORANGE);

	private final @Nullable ColorTransform transform;

	MeterTeam(@Nullable Integer rgb) {
		transform = rgb == null ? null : ColorTransform.add(rgb);
	}

	public @Nullable ColorTransform transform() {
		return transform;
	}
}
