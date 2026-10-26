package dev.mcrl.client.fx;

public enum RlSurface {
	GRASS("Grass", "Grass"),
	WET_GRASS("WetGrass", "Grass"),
	DIRT("Dirt", "Dirt"),
	SAND("Sand", "Dirt"),
	SAND_02("Sand_02", "Dirt"),
	MUD("Mud", "Mud"),
	ROCK("Rock", "Rock"),
	METAL("Metal", "Metal"),
	WET_METAL("WetMetal", "Metal"),
	GLASS("Glass", "Glass"),
	ICE("Ice", "Glass"),
	SNOW("Snow", "snow"),
	WATER("Water", "Water"),
	WET_PAVEMENT("WetPavement", "WetPavement"),
	WOOD("Wood", "Wood"),
	FENCE("Fence", "Fence"),
	FORCE_FIELD("ForceField", "ForceField"),
	BALL("Physmat_Ball", "Ball");

	private final String rlName, soundSwitch;

	RlSurface(String rlName, String soundSwitch) {
		this.rlName = rlName;
		this.soundSwitch = soundSwitch;
	}

	public String rlName() {
		return rlName;
	}

	public String soundSwitch() {
		return soundSwitch;
	}
}
