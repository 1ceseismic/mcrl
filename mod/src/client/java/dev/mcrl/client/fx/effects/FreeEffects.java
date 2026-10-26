package dev.mcrl.client.fx.effects;

import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.math.Pose;

public final class FreeEffects extends ActorEffects {
	private final Pose origin;

	public FreeEffects(FxContext ctx, Pose origin) {
		super(ctx);
		this.origin = origin;
	}

	@Override
	protected Pose pose() {
		return origin;
	}

	@Override
	public boolean done() {
		return systems().stream().allMatch(SystemInstance::done);
	}
}
