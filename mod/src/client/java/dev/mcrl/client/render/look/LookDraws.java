package dev.mcrl.client.render.look;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import dev.mcrl.client.fx.effects.BallDissolve;
import dev.mcrl.client.fx.render.BallDissolveLook;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.render.BallRenderer;
import dev.mcrl.client.render.CarRenderer;
import dev.mcrl.client.render.RlModels;
import dev.mcrl.client.rl.BodyLook;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.WheelRig;
import dev.mcrl.rlassets.bodies.BodyIds;

public final class LookDraws {
	private static final float[] NO_DYN = {0, 0, 0, 0};
	private static final float[] TYRE_DYN = {0, 0, 1, 0};
	private static final Matrix4f ZERO = new Matrix4f().zero();
	private static final PoseStack WHEEL = new PoseStack();
	private static final Matrix4d CAR = new Matrix4d();
	private static final Quaterniond CAR_ROTATION = new Quaterniond();
	private static final List<Matrix4f> MATRICES = new ArrayList<>();
	private static final List<float[]> DYNS = new ArrayList<>();
	private static int matricesUsed, dynsUsed;
	private static boolean failureShown;

	private LookDraws() {
	}

	public static void frame() {
		matricesUsed = 0;
		dynsUsed = 0;
	}

	public static boolean submitCar(RlCache rl, BodyLook look, CarRenderer.CarRenderState s, PoseStack ps,
			SubmitNodeCollector c) {
		RlLookAssets own = RlLookAssets.forBody(rl, look);
		BodyLook octane = rl.bodies().octane();
		noteFailure(own);
		RlLookAssets a = RlLookAssets.drawable(own);
		if (a == null && look != octane) {
			RlLookAssets o = RlLookAssets.forBody(rl, octane);
			noteFailure(o);
			a = RlLookAssets.drawable(o);
		}
		if (a == null) {
			return false;
		}
		BodyLook drawn = a.bodyId().equals(own.bodyId()) ? look : octane;
		Matrix4f base = matrix().set(ps.last().pose());
		Matrix4f box = normalisation(a.probeBox(), matrix());
		submit(c, a.body(s.team), base, box, s.probes, NO_DYN, s.ground);
		float[] chassisDyn = dyn();
		chassisDyn[0] = s.brake;
		chassisDyn[1] = s.boostGlow;
		submit(c, a.chassis(), base, box, s.probes, chassisDyn, s.ground);
		WheelRig rig = drawn.wheels();
		for (int i = 0; i < 4; i++) {
			WHEEL.pushPose();
			RlModels.wheelTransform(WHEEL, rig, i, s.rlWheelOffset[i], s.rlSteer, s.wheelSpin[i]);
			Matrix4f w = WHEEL.last().pose();
			Matrix4f model = base.mul(w, matrix());
			Matrix4f probe = box.mul(w, matrix());
			submit(c, a.rim(i), model, probe, s.probes, NO_DYN, s.ground);
			submit(c, a.tyre(i), model, probe, s.probes, TYRE_DYN, s.ground);
			WHEEL.popPose();
		}
		return true;
	}

	public static boolean submitBall(RlCache rl, BallRenderer.BallRenderState s, PoseStack ps, SubmitNodeCollector c) {
		RlLookAssets own = RlLookAssets.forBody(rl, rl.bodies().octane());
		noteFailure(own);
		RlLookAssets a = RlLookAssets.drawable(own);
		if (a == null) {
			return false;
		}
		RlLookAssets.Part part = a.ball();
		float[] dyn = NO_DYN;
		BallDissolve d = BallDissolve.game();
		if (d.dissolving()) {
			RlLookAssets.Part p = BallDissolveLook.part(rl, a);
			if (p != null) {
				double t = ClientPoses.simSeconds();
				part = p;
				dyn = new float[] {d.opacity(), (float) frac(0.2 * t), (float) frac(0.575 * t), 0};
			}
		}
		submit(c, part, matrix().set(ps.last().pose()), ZERO, s.probes, dyn, s.ground);
		return true;
	}

	private static double frac(double x) {
		return x - Math.floor(x);
	}

	public static void carLight(RlCache rl, BodyLook look, Level level, Pose pose, float[] probes16, float[] ground3) {
		CAR.translation(pose.x(), pose.y(), pose.z()).rotate(CAR_ROTATION.set(pose.qx(), pose.qy(), pose.qz(), pose.qw()));
		LightProbes.box(level, CAR, RlLookAssets.forBody(rl, look).probeBox(), probes16);
		ground(level, pose.x(), pose.y(), pose.z(), ground3);
	}

	public static void ballLight(Level level, double x, double y, double z, float[] probes16, float[] ground3) {
		LightProbes.point(level, x, y, z, probes16);
		ground(level, x, y, z, ground3);
	}

	private static void ground(Level level, double x, double y, double z, float[] out) {
		Vector3f g = SceneLight.groundColor(level, x, y, z);
		if (g == null) {
			out[0] = Float.NaN;
		} else {
			out[0] = g.x;
			out[1] = g.y;
			out[2] = g.z;
		}
	}

	private static void noteFailure(RlLookAssets a) {
		if (!failureShown && a.state() == RlLookAssets.State.FAILED && a.bodyId().equals(BodyIds.OCTANE)) {
			failureShown = true;
			chat(String.valueOf(a.failure()));
		}
	}

	private static void chat(String failure) {
		Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(
				Component.translatable("mcrl.look.failed", failure));
	}

	private static Matrix4f normalisation(AABB box, Matrix4f out) {
		return out.scaling((float) (1 / box.getXsize()), (float) (1 / box.getYsize()), (float) (1 / box.getZsize()))
				.translate((float) -box.minX, (float) -box.minY, (float) -box.minZ);
	}

	private static Matrix4f matrix() {
		if (matricesUsed == MATRICES.size()) {
			MATRICES.add(new Matrix4f());
		}
		return MATRICES.get(matricesUsed++);
	}

	private static float[] dyn() {
		if (dynsUsed == DYNS.size()) {
			DYNS.add(new float[4]);
		}
		float[] d = DYNS.get(dynsUsed++);
		Arrays.fill(d, 0);
		return d;
	}

	private static void submit(SubmitNodeCollector c, RlLookAssets.Part part, Matrix4f model, Matrix4f probeMat,
			float[] probes, float[] dyn, float[] ground) {
		c.submitCustom(SubmitRenderPhases.SOLID, new RlMeshSubmit(part, model, probeMat, probes, dyn, ground));
	}
}
