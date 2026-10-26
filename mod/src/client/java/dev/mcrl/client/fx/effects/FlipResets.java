package dev.mcrl.client.fx.effects;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.FxEvent;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;

public final class FlipResets {
	private final FxContext ctx;

	private FlipResets(FxContext ctx) {
		this.ctx = ctx;
	}

	public static void register(FxContext ctx) {
		FlipResets r = new FlipResets(ctx);
		FrameEvents.register((mc, frame, frameNanos) -> r.frame(FrameSignals.current()));
	}

	private void frame(FrameSignals f) {
		if (!ctx.settings().on(Group.MOVEMENT)) {
			return;
		}
		for (CarSignals s : f.cars().values()) {
			if (s.events().stream().anyMatch(e -> e instanceof FxEvent.FlipReset)) {
				play(s.carId(), s.pose());
			}
		}
	}

	private void play(int carId, Pose pose) {
		RlCache rl = RlCache.loaded();
		JsonObject a = rl == null ? null : rl.fxObjects().get(FxConstants.FLIP_RESET);
		JsonElement ps = a == null ? null : a.get("ActivationPSArchetype");
		FxSystemDef def = ps == null || !ps.isJsonPrimitive() ? null : ctx.system(ps.getAsString());
		if (def == null) {
			return;
		}
		JsonElement t = a.get("TranslationOffset"), r = a.get("RotationOffset");
		Vector3f offset = t != null && t.isJsonObject() ? Sockets.vector(t.getAsJsonObject()) : new Vector3f();
		Quaternionf rot = r != null && r.isJsonObject() ? Sockets.rotator(r.getAsJsonObject()) : new Quaternionf();
		OneShot.play(ctx, def, pose, offset, rot, new FxParams(), () -> ctx.carPose(carId));
	}
}
