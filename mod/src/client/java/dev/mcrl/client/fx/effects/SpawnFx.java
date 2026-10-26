package dev.mcrl.client.fx.effects;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.match.ClientTeams;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.net.McrlPayloads.FxEventS2C;
import dev.mcrl.rlassets.model.FxActorDef;

public final class SpawnFx {
	public static final String SPAWN = "spawn", LEAVE = "leave";
	public static final List<Consumer<FxEventS2C>> RECEIVED = new CopyOnWriteArrayList<>();
	private static final int CAP = 4;
	private static final double CAP_SECONDS = 1.5;
	private static final List<ArrayDeque<Double>> STARTED = List.of(new ArrayDeque<>(), new ArrayDeque<>());

	private SpawnFx() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(FxEventS2C.TYPE, (payload, ctx) -> {
			if (payload.playable()) {
				for (Consumer<FxEventS2C> l : RECEIVED) {
					l.accept(payload);
				}
				receive(payload);
			}
		});
	}

	private static void receive(FxEventS2C e) {
		FxEventS2C.Kind kind = e.kind();
		if (kind == null) {
			return;
		}
		if (kind == FxEventS2C.Kind.CAR_DEMOLISHED) {
			Demolition.trigger(e.entityId(), e.pose());
			return;
		}
		RlCache rl = RlCache.loaded();
		double now = FrameSignals.current().simSeconds();
		switch (kind) {
			case CAR_SPAWNED -> {
				FxActorDef def = rl == null ? null : rl.fxActors().get(SPAWN);
				if (def != null && admit(0, now)) {
					float[] c = FxConstants.TEAM_FX_COLOR[ClientTeams.ofCar(Minecraft.getInstance().level, e.entityId())
							.ordinal()];
					FxParams params = new FxParams();
					params.setVector("TeamColor", c[0], c[1], c[2]);
					FreeActors.play(def, SPAWN, e.pose(), now, params);
				}
			}
			case CAR_LEFT -> {
				FxActorDef def = rl == null ? null : rl.fxActors().get(LEAVE);
				if (def != null && admit(1, now)) {
					FreeActors.play(def, LEAVE, e.pose(), now, new FxParams());
				}
			}
			case BALL_SPAWNED -> BallDissolve.game().start();
		}
	}

	private static boolean admit(int k, double now) {
		ArrayDeque<Double> started = STARTED.get(k);
		if (!started.isEmpty() && now < started.peekLast()) {
			started.clear();
		}
		while (!started.isEmpty() && now - started.peekFirst() >= CAP_SECONDS) {
			started.pollFirst();
		}
		if (started.size() >= CAP) {
			return false;
		}
		started.addLast(now);
		return true;
	}
}
