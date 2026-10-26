package dev.mcrl.client.fx.effects;

import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.FxEvent;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.rl.RlCache;

public final class Impacts {
	private static final int WORLD = 0, BALL = 1;

	private final FxContext ctx;
	private final Int2ObjectOpenHashMap<double[]> last = new Int2ObjectOpenHashMap<>();

	private Impacts(FxContext ctx) {
		this.ctx = ctx;
	}

	public static void register(FxContext ctx) {
		Impacts i = new Impacts(ctx);
		FrameEvents.register((mc, frame, frameNanos) -> i.frame(FrameSignals.current()));
	}

	private void frame(FrameSignals f) {
		last.keySet().removeIf(id -> f.car(id) == null);
		if (!ctx.settings().on(Group.IMPACTS)) {
			return;
		}
		double now = f.simSeconds();
		for (CarSignals s : f.cars().values()) {
			for (FxEvent e : s.events()) {
				if (e instanceof FxEvent.WorldHit h) {
					hit(s.carId(), WORLD, FxConstants.WORLD_IMPACT_MATERIAL, h.speed(), h.point(), h.normal(), now);
				} else if (e instanceof FxEvent.BallHit h) {
					hit(s.carId(), BALL, FxConstants.BALL_IMPACT_MATERIAL, h.speed(), h.point(), h.normal(), now);
				}
			}
		}
	}

	private void hit(int carId, int kind, String material, double speed, Vec3 point, Vec3 normal, double now) {
		if (speed < FxConstants.MIN_IMPACT_MOMENTUM) {
			return;
		}
		double[] t = last.computeIfAbsent(carId, id -> new double[] {Double.NEGATIVE_INFINITY,
				Double.NEGATIVE_INFINITY});
		if (now - t[kind] < FxConstants.MIN_IMPACT_DELAY) {
			return;
		}
		RlCache rl = RlCache.loaded();
		JsonObject m = rl == null ? null : rl.fxObjects().get(FxConstants.CAR_IMPACT_EFFECTS_MAP);
		String path = m == null ? null : EffectsMap.system(m, material);
		FxSystemDef def = path == null ? null : ctx.system(path);
		if (def == null) {
			return;
		}
		t[kind] = now;
		FxParams p = new FxParams();
		p.setScalar(FxConstants.IMPACT_VEL_PARAM, (float) speed);
		OneShot.play(ctx, def, point, Sockets.alongNormal(normal), p, () -> ctx.carPose(carId));
	}
}
