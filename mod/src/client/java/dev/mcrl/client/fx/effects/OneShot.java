package dev.mcrl.client.fx.effects;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.client.fx.particle.FxSystemDef;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.math.Pose;

public final class OneShot implements FxEffect {
	private final SystemInstance system;
	private final @Nullable Supplier<@Nullable Pose> owner;
	private final Vector3f offset = new Vector3f(), v = new Vector3f();
	private final Quaternionf rot = new Quaternionf(), q = new Quaternionf();

	private OneShot(SystemInstance system, @Nullable Supplier<@Nullable Pose> owner) {
		this.system = system;
		this.owner = owner;
	}

	public static OneShot play(FxContext ctx, FxSystemDef def, Vec3 at, Quaternionfc rotation, FxParams p,
			@Nullable Supplier<@Nullable Pose> owner) {
		SystemInstance s = new SystemInstance(def, ctx.seed(), p);
		OneShot shot = new OneShot(s, owner);
		Pose o = owner == null ? null : owner.get();
		if (o != null) {
			Quaternionf inv = new Quaternionf(o.qx(), o.qy(), o.qz(), o.qw()).conjugate();
			inv.transform(shot.offset.set((float) (at.x - o.x()), (float) (at.y - o.y()), (float) (at.z - o.z())));
			inv.mul(rotation, shot.rot);
			shot.place(o);
		} else {
			s.setTransform(at.x, at.y, at.z, new Quaternionf(rotation));
		}
		return ctx.world().add(shot);
	}

	public static OneShot play(FxContext ctx, FxSystemDef def, Pose pose, Vector3f offset, Quaternionfc rotation,
			FxParams p, @Nullable Supplier<@Nullable Pose> owner) {
		Quaternionf q = new Quaternionf(pose.qx(), pose.qy(), pose.qz(), pose.qw());
		Vector3f w = q.transform(new Vector3f(offset));
		return play(ctx, def, new Vec3(pose.x() + w.x, pose.y() + w.y, pose.z() + w.z), q.mul(rotation), p, owner);
	}

	private void place(Pose p) {
		q.set(p.qx(), p.qy(), p.qz(), p.qw());
		q.transform(v.set(offset));
		system.setTransform(p.x() + v.x, p.y() + v.y, p.z() + v.z, q.mul(rot));
	}

	public SystemInstance system() {
		return system;
	}

	@Override
	public void update(FxFrame f) {
		Pose p = owner == null ? null : owner.get();
		if (p != null) {
			place(p);
		}
		system.update(f.emitter());
	}

	@Override
	public void collect(FxDrawList out) {
		out.add(system);
	}

	@Override
	public boolean done() {
		return system.done();
	}

	@Override
	public void stop() {
		system.deactivate();
	}
}
