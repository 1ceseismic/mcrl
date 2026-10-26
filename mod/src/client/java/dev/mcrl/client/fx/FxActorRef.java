package dev.mcrl.client.fx;

import org.jspecify.annotations.Nullable;

import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.math.Pose;

public record FxActorRef(FxActor actor, int carId, String key, @Nullable Pose origin, FxParams params, boolean local) {
}
