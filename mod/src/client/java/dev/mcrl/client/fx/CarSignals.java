package dev.mcrl.client.fx;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarFx;

public record CarSignals(int carId, Pose pose, Vec3 velocity, Vec3 angularVelocity, WheelSignal[] wheels,
		boolean onGround, boolean supersonic, boolean boosting, double forwardSpeed, float throttle, boolean local,
		@Nullable RlSurface bodySurface, List<FxEvent> events, CarFx fx, boolean demoed) {
	public WheelSignal wheel(int i) {
		return wheels[i];
	}
}
