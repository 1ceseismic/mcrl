package dev.mcrl.client.hud;

public record HudSettings(boolean boostMeter, boolean ballCamIndicator, boolean speed, boolean hideVanilla) {
	public static final HudSettings DEFAULT = new HudSettings(true, true, false, true);
}
