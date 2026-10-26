package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;

import dev.mcrl.math.Pose;

public final class BallEffects extends ActorEffects {
	public BallEffects(FxContext ctx) {
		super(ctx);
	}

	@Override
	protected @Nullable Pose pose() {
		return ctx.ballPose();
	}
}
