package dev.mcrl.rlassets.wwise;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;

public final class SoundEvents {
	public static final List<String> EVENTS = List.of(
			"Play_Car_Movement_Jump", "Play_Car_Movement_DoubleJump", "Play_Car_Movement_Dodge",
			"Play_Car_Movement_FlipReset", "Play_Car_LeaveGame_Vanish",
			"Play_Tires_Rolling_Loop", "Stop_Tires_Rolling_Loop",
			"Play_Ball_Rolling_Loop", "Stop_Ball_Rolling_Loop", "Play_Ball_Flying_Loop", "Stop_Ball_Flying_Loop",
			"Play_Ball_ExplodeVanish",
			"Play_Vehicle_SuperSonic", "Stop_Vehicle_SuperSonic",
			"Play_Car_Movement_InAirWhoosh_Loop", "Stop_Car_Movement_InAirWhoosh_Loop",
			"Play_Vehicle_SuperSonic_Loop", "Stop_Vehicle_SuperSonic_Loop",
			"Play_Boost_Pickup_Pad_Local", "Play_Boost_Pickup_Pill_Local", "Play_Boost_Pickup_NonLocal",
			"Play_UI_BoostFail",
			"Play_Boost_Standard_Loop", "Stop_Boost_Standard_Loop",
			"Play_OctaneMK2_Car_Engine", "Stop_OctaneMK2_Car_Engine", "Play_OctaneMK2_Car_Exhaust",
			"Stop_OctaneMK2_Car_Exhaust", "Play_OctaneMK2_BlowOffValve",
			"Play_Impact_Wheel", "Play_Impact_Ball", "Play_Impact_Body", "Play_Body_Slide", "Stop_Body_Slide",
			"Play_Impact_Car_Explode", "Play_Impact_Car_Explode_Small", "Play_Demolition_Opponent",
			"Play_Demolition_Teammate",
			"Play_UI_JumpFail",
			"Play_GoalEvent_Explosion", "Play_GoalEvent_Horn", "Play_GoalExplosion_NewDefault",
			"Play_BG_Crowd_01", "Play_BG_Crowd_OneShots_GoalApplause_US",
			"Play_BG_Crowd_Countdown_01", "Play_BG_Crowd_Countdown_02", "Play_BG_Crowd_Countdown_03",
			"Play_BG_Crowd_Countdown_04", "Play_BG_Crowd_Countdown_05", "Play_BG_Crowd_Countdown_06",
			"Play_BG_Crowd_Countdown_07", "Play_BG_Crowd_Countdown_08", "Play_BG_Crowd_Countdown_09",
			"Play_BG_Crowd_Countdown_10",
			"Play_SFX_UI_Countdown_01", "Play_SFX_UI_Countdown_02", "Play_SFX_UI_Countdown_03", "Play_SFX_UI_Countdown_Go");

	// Init first, as Wwise loads it.
	public static final List<String> BANKS = List.of("Init", "SFX_Car_Movements", "SFX_Car_Tires", "SFX_Car_Burnout",
			"SFX_Ball", "SFX_VehicleElements", "SFX_UI_Boost", "SFX_Boost_Standard", "SFX_Motor_OctaneMK2",
			"SFX_REV_OctaneMK2", "SFX_UI_InGame", "SFX_GlobalEvents", "SFX_Impacts", "SFX_UI_Training", "SFX_GoalEvent",
			"SFX_GoalExplosion_NewDefault", "SFX_AMB_Reverb", "SFX_BG_Crowd", "SFX_UI_Countdown");

	public static final String VERSION = version(EVENTS, BANKS, HircJson.SCHEMA);

	private SoundEvents() {
	}

	public static List<String> bankFiles() {
		return BANKS.stream().map(b -> b + ".bnk").toList();
	}

	static String version(List<String> events, List<String> banks, int schema) {
		CRC32 crc = new CRC32();
		crc.update((String.join("\n", events) + "\n\n" + String.join("\n", banks) + "\n\nschema " + schema)
				.getBytes(StandardCharsets.UTF_8));
		return Long.toHexString(crc.getValue());
	}
}
