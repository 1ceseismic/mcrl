package dev.mcrl.client.fx;

import java.util.List;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.math.Pose;

public record BallSignals(Pose pose, Vec3 velocity, boolean worldContact, int lastTouchTeam, List<FxEvent> events) {
}
