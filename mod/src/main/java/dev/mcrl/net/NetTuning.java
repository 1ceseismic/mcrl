package dev.mcrl.net;

public final class NetTuning {
	public static final double ACCEPT_POS_BLOCKS = 0.00234;
	public static final double ACCEPT_VEL_BLOCKS_S = 0.569;
	public static final double ACCEPT_ROT_RAD = 0.00556;
	public static final double ACCEPT_ANGVEL_RAD_S = 0.475;
	public static final double CORRECTION_TAU_S = 0.1;
	public static final double SWITCH_TAU_S = 0.5;
	public static final double SNAP_BLOCKS = 4;
	public static final int INTERP_DELAY_TICKS = 12;
	public static final int HISTORY_TICKS = 128;
	public static final int CAR_STALE_TICKS = 60;

	private NetTuning() {
	}
}
