package dev.mcrl.client.render;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.client.link.ClientPoses;
import dev.mcrl.client.match.MatchClientState;
import dev.mcrl.client.render.look.LookDraws;
import dev.mcrl.client.render.look.MeshBounds;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;

public class BallRenderer extends EntityRenderer<BallEntity, BallRenderer.BallRenderState> {
	private static final float[] BALL = SimpleModels.ball();
	private double rlRadius;
	private @Nullable RlCache radiusCache;

	public BallRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public BallRenderState createRenderState() {
		return new BallRenderState();
	}

	@Override
	public void extractRenderState(BallEntity ball, BallRenderState s, float partialTick) {
		super.extractRenderState(ball, s, partialTick);
		Pose p = ClientPoses.ball();
		if (p == null) {
			p = new Pose(s.x, s.y, s.z, 1f, 0f, 0f, 0f);
		}
		s.pose = p;
		s.hidden = MatchClientState.ballOutOfPlay() && ball.level().dimension().equals(StadiumConst.KEY);
		s.x = p.x();
		s.y = p.y();
		s.z = p.z();
		s.rl = RlConversion.effectiveVisuals() == Visuals.RL ? RlCache.loaded() : null;
		if (s.rl != null) {
			LookDraws.ballLight(ball.level(), p.x(), p.y(), p.z(), s.probes, s.ground);
		}
	}

	@Override
	public void submit(BallRenderState s, PoseStack ps, SubmitNodeCollector collector, CameraRenderState camera) {
		if (s.hidden) {
			return;
		}
		int light = s.lightCoords;
		Pose p = s.pose;
		ps.pushPose();
		ps.rotate(new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()));
		RlCache rl = s.rl;
		if (rl == null || !LookDraws.submitBall(rl, s, ps, collector)) {
			RenderType type = MeshEmitter.renderType();
			collector.submitCustomGeometry(ps, type, (pose, vc) -> MeshEmitter.emitTriangles(pose, vc, BALL, light));
		}
		ps.popPose();
		super.submit(s, ps, collector, camera);
	}

	@Override
	protected AABB getBoundingBoxForCulling(BallEntity ball, float partialTick) {
		Pose p = ClientPoses.ball();
		Vec3 c = p != null ? new Vec3(p.x(), p.y(), p.z()) : ball.getPosition(partialTick);
		double r = BallEntity.RADIUS;
		RlCache rl = RlConversion.effectiveVisuals() == Visuals.RL ? RlCache.loaded() : null;
		if (rl != null) {
			if (rl != radiusCache) {
				rlRadius = MeshBounds.ballRadius(rl) / UU_PER_BLOCK;
				radiusCache = rl;
			}
			r = Math.max(r, rlRadius);
		}
		return new AABB(c.x - r, c.y - r, c.z - r, c.x + r, c.y + r, c.z + r);
	}

	public static class BallRenderState extends EntityRenderState {
		public Pose pose = new Pose(0, 0, 0, 1f, 0f, 0f, 0f);
		public boolean hidden;
		public @Nullable RlCache rl;
		public final float[] probes = new float[16];
		public final float[] ground = new float[3];
	}
}
