package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;

import dev.mcrl.math.Pose;

public final class CarEffects extends ActorEffects {
	private final int carId;

	public CarEffects(FxContext ctx, int carId) {
		super(ctx);
		this.carId = carId;
	}

	@Override
	protected @Nullable Pose pose() {
		return ctx.carPose(carId);
	}
}
