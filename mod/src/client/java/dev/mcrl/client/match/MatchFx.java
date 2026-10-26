package dev.mcrl.client.match;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxState;
import dev.mcrl.client.fx.effects.FreeActors;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.sound.RlAudio;
import dev.mcrl.math.Pose;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;
import dev.mcrl.net.MatchPayloads.MatchSetupS2C;
import dev.mcrl.rlassets.model.FxActorDef;

public final class MatchFx {
	private static final String GOAL_EXPLOSION = "goalExplosion";
	private static final String PAD_USED = "boostPadUsed", PILL_USED = "boostPillUsed";

	private MatchFx() {
	}

	public static void register() {
		MatchClientState.addListener(MatchFx::play);
	}

	private static void play(MatchEventS2C e) {
		Minecraft mc = Minecraft.getInstance();
		RlAudio audio = RlAudio.game();
		RlCache rl = RlCache.loaded();
		int localCar = mc.player != null && mc.player.getVehicle() != null ? mc.player.getVehicle().getId() : -1;
		MatchSetupS2C setup = MatchClientState.setup();
		double now = FrameSignals.current().simSeconds();
		if (e.kind() == MatchEventS2C.Kind.GOAL) {
			Pose at = new Pose(e.x(), e.y(), e.z(), 1f, 0f, 0f, 0f);
			FxActorDef explosion = def(rl, GOAL_EXPLOSION);
			if (explosion != null) {
				FreeActors.play(explosion, GOAL_EXPLOSION, at, now, new FxParams());
			}
			sound(audio, "Play_Ball_ExplodeVanish", new Vec3(e.x(), e.y(), e.z()));
			sound(audio, "Play_GoalEvent_Horn", null);
		} else if (e.kind() == MatchEventS2C.Kind.PICKUP) {
			if (setup == null) {
				return;
			}
			float[] xyz = setup.padXyz();
			int pad = e.pad();
			if (pad < 0 || 3 * pad + 2 >= xyz.length) {
				return;
			}
			Vec3 at = new Vec3(xyz[3 * pad], xyz[3 * pad + 1], xyz[3 * pad + 2]);
			boolean big = pad < 64 && (setup.padBigBits() >>> pad & 1) != 0;
			String key = big ? PILL_USED : PAD_USED;
			FxActorDef used = def(rl, key);
			boolean mine = e.entityId() >= 0 && e.entityId() == localCar;
			if (mine) {
				sound(audio, big ? "Play_Boost_Pickup_Pill_Local" : "Play_Boost_Pickup_Pad_Local", null);
			} else if (used == null) {
				sound(audio, "Play_Boost_Pickup_NonLocal", at);
			}
			if (used != null) {
				FreeActors.play(used, key, new Pose(at.x, at.y, at.z, 1f, 0f, 0f, 0f), now, new FxParams(),
						FxState.PICKED_UP, mine);
			}
		}
	}

	private static @Nullable FxActorDef def(@Nullable RlCache rl, String key) {
		return rl == null ? null : rl.fxActors().get(key);
	}

	private static void sound(@Nullable RlAudio audio, String event, @Nullable Vec3 at) {
		if (audio != null) {
			audio.postOneShot(event, at);
		}
	}
}
