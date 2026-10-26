package dev.mcrl.client.fx;

public record EffectsSettings(boolean enabled, boolean boost, boolean supersonic, boolean wheels, boolean movement,
		boolean impacts, boolean ball, boolean spawn, boolean ownerOnly, Quality quality) {
	public static final EffectsSettings DEFAULTS = new EffectsSettings(true, true, true, true, true, true, true, true,
			true, Quality.FULL);

	public enum Quality { FULL, REDUCED }

	public enum Group {
		BOOST,
		SUPERSONIC,
		WHEELS,
		MOVEMENT,
		IMPACTS,
		BALL,
		SPAWN
	}

	public boolean on(Group g) {
		return enabled && switch (g) {
			case BOOST -> boost;
			case SUPERSONIC -> supersonic;
			case WHEELS -> wheels;
			case MOVEMENT -> movement;
			case IMPACTS -> impacts;
			case BALL -> ball;
			case SPAWN -> spawn;
		};
	}
}
