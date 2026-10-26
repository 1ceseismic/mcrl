package dev.mcrl.net;

public final class NetProtocol {
	public static final int VERSION = 2;
	public static final int SNAP_INTERVAL_TICKS = 2;
	public static final int MAX_INPUT_TICKS = 32;
	public static final int INPUT_RING = 128;
	public static final int HOLD_TICKS = 60;
	// Vanilla's vehicle tracking range.
	public static final int CAR_RANGE = 160;
	public static final int BALL_RANGE = 256;
	public static final int FAR_BLOCKS = 64;
	public static final int FAR_INTERVAL_TICKS = 6;
	public static final int FAR_MIN_CARS = 8;

	private NetProtocol() {
	}
}
