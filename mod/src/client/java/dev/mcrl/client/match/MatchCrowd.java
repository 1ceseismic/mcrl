package dev.mcrl.client.match;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import dev.mcrl.client.sound.RlAudio;
import dev.mcrl.client.sound.ak.GameObject;
import dev.mcrl.match.Match;
import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;

public final class MatchCrowd {
	private static @Nullable GameObject bed;
	private static int lastCountdown = -1, lastBeep = -1;
	private static Match.@Nullable Phase lastPhase;

	private MatchCrowd() {
	}

	public static void register() {
		MatchClientState.addListener(e -> {
			if (e.kind() == MatchEventS2C.Kind.GOAL) {
				oneShot("Play_BG_Crowd_OneShots_GoalApplause_US");
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(MatchCrowd::tick);
	}

	private static void tick(Minecraft mc) {
		boolean inStadium = mc.level != null && mc.level.dimension().equals(StadiumConst.KEY);
		MatchBlock b = MatchClientState.block();
		MatchBlock.Header h = b == null ? null : b.header();
		Match.Phase phase = inStadium && h != null ? h.phaseOrNull() : null;
		boolean running = phase == Match.Phase.KICKOFF || phase == Match.Phase.PLAYING || phase == Match.Phase.GOAL;
		GameObject playing = bed;
		if (playing != null) {
			RlAudio a = RlAudio.game();
			if (!running) {
				if (a != null) {
					a.stop(playing);
				}
				bed = null;
			} else if (a == null || !a.atListener(playing)) {
				bed = null;
			}
		}
		if (running && bed == null) {
			RlAudio a = RlAudio.game();
			bed = a == null ? null : a.postAtListener("Play_BG_Crowd_01");
		}
		if (phase == Match.Phase.KICKOFF && h.countdown() >= 1 && h.countdown() <= 3 && h.countdown() != lastBeep) {
			lastBeep = h.countdown();
			oneShot(String.format(Locale.ROOT, "Play_SFX_UI_Countdown_%02d", lastBeep));
		}
		if (phase == Match.Phase.PLAYING && lastPhase == Match.Phase.KICKOFF) {
			oneShot("Play_SFX_UI_Countdown_Go");
		}
		if (phase != Match.Phase.KICKOFF) {
			lastBeep = -1;
		}
		int clock = phase == Match.Phase.PLAYING && !h.overtime() ? h.clockSeconds() : -1;
		if (clock >= 1 && clock <= 10 && clock != lastCountdown) {
			lastCountdown = clock;
			oneShot(String.format(Locale.ROOT, "Play_BG_Crowd_Countdown_%02d", clock));
		} else if (clock > 10 || phase == null) {
			lastCountdown = -1;
		}
		lastPhase = phase;
	}

	private static void oneShot(String event) {
		RlAudio a = RlAudio.game();
		if (a != null) {
			a.postOneShot(event, null);
		}
	}
}
