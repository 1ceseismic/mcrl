package dev.mcrl.client.fx.effects;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;

import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import dev.mcrl.client.fx.CarSignals;
import dev.mcrl.client.fx.EffectsSettings;
import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FrameSignals;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.WheelSignal;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.client.fx.render.FxSkin;
import dev.mcrl.client.link.FrameEvents;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;

public final class WheelDust {
	private final FxContext ctx;
	private final Int2ObjectOpenHashMap<CarDust> cars = new Int2ObjectOpenHashMap<>();
	private @Nullable JsonObject parsedFor;
	private Map<String, String> bySurface = Map.of();

	private WheelDust(FxContext ctx) {
		this.ctx = ctx;
	}

	public static void register(FxContext ctx) {
		WheelDust d = new WheelDust(ctx);
		FrameEvents.register((mc, frame, frameNanos) -> d.frame(FrameSignals.current()));
	}

	private static @Nullable FxSkin blockSkin(CarSignals car, int i) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return null;
		}
		WheelSignal w = car.wheel(i);
		BlockPos pos = BlockSprite.under(w.point(), w.normal());
		BlockState state = mc.level.getBlockState(pos);
		return state.isAir() ? null : BlockSprite.of(state, pos).skin();
	}

	private void frame(FrameSignals f) {
		RlCache rl = RlCache.loaded();
		JsonObject m = rl == null ? null : rl.fxObjects().get(FxConstants.WHEEL_EFFECTS_MAP);
		if (m != parsedFor) {
			parsedFor = m;
			bySurface = m == null ? Map.of() : EffectsMap.parse(m);
		}
		ObjectIterator<Int2ObjectMap.Entry<CarDust>> it = cars.int2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			Int2ObjectMap.Entry<CarDust> e = it.next();
			if (e.getValue().removed || f.car(e.getIntKey()) == null) {
				e.getValue().stop();
				it.remove();
			}
		}
		EffectsSettings settings = ctx.settings();
		boolean on = settings.on(Group.WHEELS);
		for (CarSignals s : f.cars().values()) {
			CarDust c = cars.get(s.carId());
			if (c == null) {
				c = ctx.world().add(new CarDust());
				cars.put(s.carId(), c);
			}
			int mask = WheelFxRules.emitting(s);
			Pose p = s.pose();
			for (int i = 0; i < 4; i++) {
				WheelSignal w = s.wheel(i);
				boolean active = on && (mask >> i & 1) != 0 && w.contact() && w.surface() != null
						&& !(s.supersonic() && WheelFxRules.rear(i));
				String path = active ? bySurface.get(w.surface().rlName()) : null;
				FxSystemDef def = path == null || path.isEmpty() ? null : ctx.system(path);
				c.wheel(i, def, def == null ? null : blockSkin(s, i), w, p);
			}
		}
	}

	private static final class Run {
		final SystemInstance system;
		final int wheel;
		@Nullable FxSkin skin;

		Run(SystemInstance system, @Nullable FxSkin skin, int wheel) {
			this.system = system;
			this.skin = skin;
			this.wheel = wheel;
		}
	}

	private final class CarDust implements FxEffect {
		private final List<Run> runs = new ArrayList<>();
		private final @Nullable Run[] live = new Run[4];
		private final Quaternionf q = new Quaternionf();
		private boolean stopped, removed;

		private void wheel(int i, @Nullable FxSystemDef def, @Nullable FxSkin skin, WheelSignal w, Pose p) {
			Run r = live[i];
			if (r != null && r.system.def() != def) {
				r.system.deactivate();
				live[i] = r = null;
			}
			if (def == null || stopped) {
				return;
			}
			if (r == null) {
				r = new Run(new SystemInstance(def, ctx.seed(), new FxParams()), skin, i);
				live[i] = r;
				runs.add(r);
			}
			r.skin = skin;
			q.set(p.qx(), p.qy(), p.qz(), p.qw());
			r.system.setTransform(w.point().x, w.point().y, w.point().z, q);
			r.system.params().setScalar(FxConstants.SPEED_PARAM, (float) Math.abs(w.forwardSpeed()));
			r.system.params().setScalar(FxConstants.SIDE_SPEED_PARAM, (float) Math.abs(w.sideSpeed()));
		}

		@Override
		public void update(FxFrame f) {
			for (Run r : runs) {
				r.system.update(f.emitter());
			}
			runs.removeIf(r -> live[r.wheel] != r && r.system.done());
		}

		@Override
		public void collect(FxDrawList out) {
			for (Run r : runs) {
				out.add(r.system, r.skin);
			}
		}

		@Override
		public boolean done() {
			return stopped && runs.stream().allMatch(r -> r.system.done());
		}

		@Override
		public void stop() {
			stopped = true;
			for (int i = 0; i < 4; i++) {
				if (live[i] != null) {
					live[i].system.deactivate();
					live[i] = null;
				}
			}
		}

		@Override
		public void removed() {
			removed = true;
		}
	}
}
