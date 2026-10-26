package dev.mcrl.entity;

import static dev.mcrl.math.Coords.UU_PER_BLOCK;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.BallState;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public class BallEntity extends Entity {
	// RocketSim's ball radius, 91.25 UU.
	public static final double RADIUS = 1.825;
	// RocketSim sleeps a ball at exactly zero velocity.
	public static final double WAKE_VY = -1e-3 / UU_PER_BLOCK;

	private static final Map<ResourceKey<Level>, BallEntity> ACTIVE = new ConcurrentHashMap<>();

	private @Nullable Pose pose;
	private double vx, vy, vz, wx, wy, wz;
	private @Nullable BallState pending;
	private final BallTicket ticket = new BallTicket();
	private boolean matchOwned;

	public BallEntity(EntityType<? extends BallEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	public boolean matchOwned() {
		return matchOwned;
	}

	public void setMatchOwned(boolean owned) {
		this.matchOwned = owned;
	}

	@Override
	public boolean shouldBeSaved() {
		return !matchOwned && super.shouldBeSaved();
	}

	@Override
	public boolean shouldRenderAtSqrDistance(double distSq) {
		if (level().dimension().equals(StadiumConst.KEY)) {
			return distSq < (double) StadiumConst.VIEW_BLOCKS * StadiumConst.VIEW_BLOCKS;
		}
		return super.shouldRenderAtSqrDistance(distSq);
	}

	public static @Nullable BallEntity active(ServerLevel level) {
		BallEntity b = ACTIVE.get(level.dimension());
		return b != null && !b.isRemoved() ? b : null;
	}

	public static void clearActive() {
		ACTIVE.clear();
	}

	public static BallState restingAt(double x, double y, double z) {
		return new BallState(new Pose(x, y, z, 1f, 0f, 0f, 0f), 0, WAKE_VY, 0, 0, 0, 0);
	}

	public Pose pose() {
		Pose p = pose;
		return p != null ? p : new Pose(getX(), getY(), getZ(), 1f, 0f, 0f, 0f);
	}

	public BallState simState() {
		return new BallState(pose(), vx, vy, vz, wx, wy, wz);
	}

	public @Nullable BallState pendingState() {
		return pending;
	}

	public void setSimState(BallState s) {
		pending = s;
		applyState(s.pose(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
	}

	public void relocate(BallState s) {
		applyState(s.pose(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
		if (level() instanceof ServerLevel level) {
			SimHost host = SimHosts.getOrCreateIfOn(level, s.pose().x(), s.pose().z());
			if (host == null) {
				pending = s;
				return;
			}
			host.setBall(withWake(s));
		}
	}

	public void applyFrame(SimFrame.BallSnap s) {
		applyState(s.cur(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
	}

	private void applyState(Pose p, double vx, double vy, double vz, double wx, double wy, double wz) {
		this.pose = p;
		this.vx = vx;
		this.vy = vy;
		this.vz = vz;
		this.wx = wx;
		this.wy = wy;
		this.wz = wz;
		if (level() instanceof ServerLevel level && ticket.holding()
				&& !ticket.follow(BallTicket.of(level), CarLoadGate.chunkOf(p.x()), CarLoadGate.chunkOf(p.z()))) {
			return;
		}
		setPos(p.x(), p.y(), p.z());
	}

	private static BallState withWake(BallState s) {
		if (s.vx() == 0 && s.vy() == 0 && s.vz() == 0 && s.wx() == 0 && s.wy() == 0 && s.wz() == 0) {
			return new BallState(s.pose(), 0, WAKE_VY, 0, 0, 0, 0);
		}
		return s;
	}

	public boolean allowLoad(ServerLevel level) {
		BallEntity current = active(level);
		if (current != null && current != this) {
			discard();
			return false;
		}
		return true;
	}

	public void onLoad(ServerLevel level) {
		ACTIVE.put(level.dimension(), this);
		ticket.start(BallTicket.of(level), chunkPosition().x(), chunkPosition().z());
		BallState s = pending != null ? pending : simState();
		SimHost host = SimHosts.getOrCreateIfOn(level, s.pose().x(), s.pose().z());
		if (host == null) {
			return;
		}
		pending = null;
		host.setBall(withWake(s));
	}

	public void onUnload(ServerLevel level) {
		ticket.release(BallTicket.of(level));
		if (ACTIVE.remove(level.dimension(), this)) {
			SimHost host = SimHosts.get(level.dimension());
			if (host != null) {
				host.removeBall();
			}
		}
	}

	@Override
	public boolean isClientAuthoritative() {
		return false;
	}

	@Override
	protected boolean isLocalClientAuthoritative() {
		return false;
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isPickable() {
		return !isRemoved();
	}

	@Override
	public boolean canBeCollidedWith(@Nullable Entity other) {
		return false;
	}

	@Override
	public boolean isPushedByFluid() {
		return false;
	}

	@Override
	public void push(double x, double y, double z) {
	}

	@Override
	public boolean ignoreExplosion(Explosion explosion) {
		if (level() instanceof ServerLevel level && !isRemoved() && active(level) == this) {
			SimHost host = SimHosts.get(level.dimension());
			Vec3 v = ExplosionPush.of(explosion, this, position());
			if (host != null && v.lengthSqr() > 0) {
				host.addVelocity(-1, v.x, v.y, v.z);
			}
		}
		return true;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		return new AABB(pos.x - RADIUS, pos.y - RADIUS, pos.z - RADIUS, pos.x + RADIUS, pos.y + RADIUS, pos.z + RADIUS);
	}

	// Vanilla probes at the eye, 3.1 blocks up.
	@Override
	public Vec3 getLightProbePosition(float partialTick) {
		return position();
	}

	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return CarEntity.IgnoreVanillaMoves.INSTANCE;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		BallState s = pending != null ? pending : simState();
		SimBodyData.write(out, new SimBodyData.Body(s.pose(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		SimBodyData.Body b = SimBodyData.read(in);
		if (b != null) {
			pending = new BallState(b.pose(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz());
			pose = b.pose();
			vx = b.vx();
			vy = b.vy();
			vz = b.vz();
			wx = b.wx();
			wy = b.wy();
			wz = b.wz();
		}
	}
}
