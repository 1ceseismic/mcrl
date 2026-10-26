package dev.mcrl.entity;

import org.joml.Vector3d;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.function.IntPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.McrlGameRules;
import dev.mcrl.bot.BotSpec;
import dev.mcrl.match.StadiumConst;
import dev.mcrl.math.OrientedBox;
import dev.mcrl.net.InputMailbox;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.HitboxPreset;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;
import dev.mcrl.sim.Wet;

public class CarEntity extends Entity {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final EntityDataAccessor<Integer> DATA_TEAM = SynchedEntityData.defineId(CarEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> DATA_PRESET = SynchedEntityData.defineId(CarEntity.class,
			EntityDataSerializers.INT);
	private static final EntityDataAccessor<String> DATA_BODY = SynchedEntityData.defineId(CarEntity.class,
			EntityDataSerializers.STRING);

	// The Octane's seat, kept by every preset.
	public static final double SEAT_FROM_HITBOX_FORWARD = -0.37752;
	public static final double SEAT_FROM_HITBOX_UP = -0.0651;
	public static final double DISMOUNT_GAP = 0.4;
	// Native's FluidTuning::WET_ON.
	public static final float LAVA_IMMERSION = 0.10f;

	private @Nullable Pose pose;
	private double vx, vy, vz, wx, wy, wz;
	private float boost;
	private @Nullable CarState pending;
	private boolean inSim;
	private boolean demoed;
	private @Nullable Pose respawnPose;
	private boolean quietRemoval;
	private Wet wet = Wet.DRY;
	private @Nullable UUID owner;
	int offlineSince = -1;
	int choiceChangedAt = -1;
	private boolean matchOwned;
	private boolean staleMatchCar;
	private @Nullable BotSpec bot;
	private static volatile IntPredicate clientDemoed = id -> false;

	public CarEntity(EntityType<? extends CarEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	public static boolean carriesDriver(Entity e) {
		return e.getVehicle() instanceof CarEntity;
	}

	public int carId() {
		return getId();
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

	public @Nullable BotSpec bot() {
		return bot;
	}

	public boolean isBot() {
		return bot != null;
	}

	public void setBot(@Nullable BotSpec spec) {
		bot = spec;
		if (inSim && level() instanceof ServerLevel level) {
			SimHost host = SimHosts.get(level.dimension());
			if (host != null) {
				if (spec != null) {
					addBotTo(host, spec);
				} else {
					host.removeBot(carId());
				}
			}
		}
	}

	private void addBotTo(SimHost host, BotSpec spec) {
		if (host.kind() != SimHost.Kind.SOCCAR) {
			LOG.warn("mcrl: bot car {} is in a {} host, not a stadium's; it stays still", carId(), host.kind());
			return;
		}
		host.addBot(carId(), spec.policy(), spec.temperature(), spec.seed());
	}

	@Override
	protected boolean canAddPassenger(Entity passenger) {
		return bot == null && super.canAddPassenger(passenger);
	}

	public @Nullable UUID owner() {
		return owner;
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
	}

	public int team() {
		return getEntityData().get(DATA_TEAM);
	}

	public int preset() {
		return getEntityData().get(DATA_PRESET);
	}

	public HitboxPreset hitboxPreset() {
		SynchedEntityData d = getEntityData();
		// Null in Entity's constructor: its setPos needs the box.
		return d == null ? HitboxPreset.OCTANE : HitboxPreset.of(d.get(DATA_PRESET));
	}

	public void setTeam(int team) {
		if (team != 0 && team != 1) {
			throw new IllegalArgumentException("team must be 0 or 1: " + team);
		}
		getEntityData().set(DATA_TEAM, team);
		reconfigureInSim();
	}

	public void setPreset(int preset) {
		if (preset < 0 || preset >= HitboxPreset.ALL.size()) {
			throw new IllegalArgumentException("preset must be 0..6: " + preset);
		}
		getEntityData().set(DATA_PRESET, preset);
		reconfigureInSim();
	}

	public String bodyId() {
		return getEntityData().get(DATA_BODY);
	}

	public CarChoice choice() {
		return new CarChoice(bodyId(), preset(), team());
	}

	public void applyChoice(CarChoice c) {
		if (!c.valid()) {
			throw new IllegalArgumentException("invalid car choice: " + c);
		}
		boolean reconfigure = c.team() != team() || c.preset() != preset();
		SynchedEntityData d = getEntityData();
		d.set(DATA_BODY, c.bodyId());
		d.set(DATA_TEAM, c.team());
		d.set(DATA_PRESET, c.preset());
		if (reconfigure) {
			reconfigureInSim();
		}
	}

	private void reconfigureInSim() {
		if (inSim && level() instanceof ServerLevel level) {
			SimHost host = SimHosts.get(level.dimension());
			if (host != null) {
				host.reconfigureCar(carId(), team(), preset());
			}
		}
	}

	public boolean isDemoed() {
		return level().isClientSide() ? clientDemoed.test(carId()) : demoed;
	}

	public static void setClientDemoedSource(IntPredicate source) {
		clientDemoed = source;
	}

	public CarState simState() {
		return new CarState(pose(), vx, vy, vz, wx, wy, wz, boost, 0, null, 0f, 0, 0, 0f, 0f);
	}

	public Pose pose() {
		Pose p = pose;
		return p != null ? p : Pose.upright(getX(), getY(), getZ(), getYRot());
	}

	public float boost() {
		return boost;
	}

	public @Nullable CarState pendingState() {
		return pending;
	}

	public CarState keptState() {
		if (pending != null) {
			return pending;
		}
		Pose r = respawnPose;
		return demoed && r != null ? CarState.of(r, 0, 0, 0, 0, 0, 0) : simState();
	}

	public void setSimState(CarState s) {
		pending = s;
		applyState(s.pose(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
	}

	public void setClientPose(Pose p) {
		applyState(p, vx, vy, vz, wx, wy, wz);
	}

	public void applyFrame(SimFrame.CarSnap s) {
		boost = s.boost();
		demoed = s.demoed();
		respawnPose = s.respawnAt();
		wet = s.fx().wet();
		Pose p = s.cur();
		if (level() instanceof ServerLevel level && !CarTicket.follow(CarTicket.of(level), chunkPosition().x(),
				chunkPosition().z(), CarLoadGate.chunkOf(p.x()), CarLoadGate.chunkOf(p.z()))) {
			setSimOnly(p, s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
			return;
		}
		applyState(p, s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
	}

	private void setSimOnly(Pose p, double vx, double vy, double vz, double wx, double wy, double wz) {
		this.pose = p;
		this.vx = vx;
		this.vy = vy;
		this.vz = vz;
		this.wx = wx;
		this.wy = wy;
		this.wz = wz;
	}

	public Wet wet() {
		return wet;
	}

	private void applyState(Pose p, double vx, double vy, double vz, double wx, double wy, double wz) {
		// Before setPos: makeBoundingBox reads it.
		this.pose = p;
		this.vx = vx;
		this.vy = vy;
		this.vz = vz;
		this.wx = wx;
		this.wy = wy;
		this.wz = wz;
		setYRot(p.yawDegrees());
		setXRot(p.pitchDegrees());
		setPos(p.x(), p.y(), p.z());
	}

	public void onLoad(ServerLevel level) {
		if (staleMatchCar) {
			return;
		}
		CarLoadGate.onLoad(level, this);
	}

	Pose entryPose() {
		return keptState().pose();
	}

	void enterSim(ServerLevel level) {
		CarState s = keptState();
		SimHost host = SimHosts.getOrCreateIfOn(level, s.pose().x(), s.pose().z());
		if (host == null) {
			return;
		}
		pending = null;
		host.addCar(carId(), s, team(), preset());
		BotSpec b = bot;
		if (b != null) {
			addBotTo(host, b);
		}
		inSim = true;
		demoed = false;
		respawnPose = null;
	}

	public void leftSim() {
		Pose r = respawnPose;
		if (demoed && r != null) {
			setSimState(CarState.of(r, 0, 0, 0, 0, 0, 0));
		}
		demoed = false;
		respawnPose = null;
		inSim = false;
	}

	public void onUnload(ServerLevel level) {
		if (pending == null && demoed && respawnPose != null) {
			pending = keptState();
		}
		demoed = false;
		respawnPose = null;
		inSim = false;
		CarLoadGate.onUnload(level, this);
		SimHost host = SimHosts.get(level.dimension());
		if (host != null) {
			host.removeCar(carId());
		}
	}

	public boolean resetUpright() {
		if (!(level() instanceof ServerLevel level) || isRemoved() || isDemoed()) {
			return false;
		}
		Pose p = pose();
		CarState s = new CarState(Pose.upright(p.x(), p.y() + 1, p.z(), p.yawDegrees()), 0, 0, 0, 0, 0, 0, boost, 0, null,
				0f, 0, 0, 0f, 0f);
		if (CarLoadGate.isWaiting(this)) {
			setSimState(s);
			return true;
		}
		SimHost host = SimHosts.get(level.dimension());
		if (host == null) {
			return false;
		}
		SimFrame.CarSnap now = host.latest().car(carId());
		if (now != null && now.demoed()) {
			return false;
		}
		applyState(s.pose(), 0, 0, 0, 0, 0, 0);
		host.setCar(carId(), s);
		return true;
	}

	@Override
	public void remove(RemovalReason reason) {
		if ((reason == RemovalReason.KILLED || reason == RemovalReason.DISCARDED) && !isRemoved() && !quietRemoval
				&& level() instanceof ServerLevel) {
			McrlPayloads.sendFxEvent(this, McrlPayloads.FxEventS2C.Kind.CAR_LEFT, pose(), null);
		}
		super.remove(reason);
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
	public @Nullable LivingEntity getControllingPassenger() {
		return getFirstPassenger() instanceof Player p ? p : null;
	}

	void discardQuietly() {
		quietRemoval = true;
		discard();
	}

	@Override
	public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
		if (player.isSecondaryUseActive() || isDemoed()) {
			return InteractionResult.PASS;
		}
		if (!level().isClientSide()) {
			return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected void addPassenger(Entity passenger) {
		super.addPassenger(passenger);
		if (level() instanceof ServerLevel level && passenger instanceof ServerPlayer p && getControllingPassenger() == p) {
			InputMailbox.SERVER.bind(p.getUUID(), level.dimension(), carId());
		}
	}

	@Override
	protected void removePassenger(Entity passenger) {
		super.removePassenger(passenger);
		if (level() instanceof ServerLevel && passenger instanceof ServerPlayer p) {
			InputMailbox.SERVER.unbind(p.getUUID());
		}
		if (level() instanceof ServerLevel level && !isRemoved() && getControllingPassenger() instanceof ServerPlayer d) {
			InputMailbox.SERVER.bind(d.getUUID(), level.dimension(), carId());
		}
		if (level() instanceof ServerLevel level && !isRemoved() && getControllingPassenger() == null) {
			SimHost host = SimHosts.get(level.dimension());
			if (host != null) {
				host.submitInput(carId(), InputFrame.NEUTRAL);
			}
		}
	}

	@Override
	public Vec3 getPassengerRidingPosition(Entity passenger) {
		Pose p = pose();
		Vector3d f = p.forward(), u = p.up();
		HitboxPreset h = hitboxPreset();
		double fw = h.offsetForward() + SEAT_FROM_HITBOX_FORWARD, up = h.offsetUp() + SEAT_FROM_HITBOX_UP;
		return new Vec3(getX() + f.x * fw + u.x * up, getY() + f.y * fw + u.y * up, getZ() + f.z * fw + u.z * up);
	}

	@Override
	public void tick() {
		if (staleMatchCar && !level().isClientSide()) {
			ejectPassengers();
			discard();
			return;
		}
		// /ride bypasses canAddPassenger.
		if (bot != null && isVehicle() && !level().isClientSide()) {
			ejectPassengers();
		}
		super.tick();
		boolean fluids = level() instanceof ServerLevel level && level.getGameRules().get(McrlGameRules.FLUID_PHYSICS)
				&& SimHosts.get(level.dimension()) instanceof SimHost host && host.kind() == SimHost.Kind.TERRAIN;
		boolean inLava = fluids && !demoed && wet.fluid() == Wet.LAVA && wet.immersion() >= LAVA_IMMERSION;
		boolean inWater = fluids && wet.fluid() == Wet.WATER && wet.immersion() >= LAVA_IMMERSION;
		// Runs before the passengers' ticks; the mixins block extinguishing.
		for (Entity p : getPassengers()) {
			p.resetFallDistance();
			p.setTicksFrozen(0);
			if (!fluids) {
				p.clearFire();
			} else if (inLava) {
				p.lavaIgnite();
				p.lavaHurt();
			} else if (inWater || p.isInWaterOrRain()) {
				p.extinguishFire();
			}
		}
	}

	// Vanilla discards it; the sim's void rule respawns it.
	@Override
	protected void onBelowWorld() {
	}

	@Override
	public boolean canUsePortal(boolean allowPassengers) {
		return false;
	}

	@Override
	public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
		Pose p = pose();
		HitboxPreset h = hitboxPreset();
		OrientedBox box = OrientedBox.car(p, h);
		Vector3d f = p.forward(), r = p.right();
		double side = h.halfRight() + DISMOUNT_GAP, end = h.halfForward() + DISMOUNT_GAP;
		double[][] offsets = {
				{-r.x * side, -r.z * side},
				{r.x * side, r.z * side},
				{-f.x * end, -f.z * end},
				{f.x * end, f.z * end}};
		for (double[] o : offsets) {
			BlockPos at = BlockPos.containing(box.cx() + o[0], getY(), box.cz() + o[1]);
			Vec3 v = DismountHelper.findSafeDismountLocation(passenger.getType(), level(), at, true);
			if (v != null) {
				return v;
			}
		}
		AABB bb = getBoundingBox();
		Vec3 top = new Vec3((bb.minX + bb.maxX) / 2, bb.maxY, (bb.minZ + bb.maxZ) / 2);
		if (DismountHelper.canDismountTo(level(), top, passenger, net.minecraft.world.entity.Pose.STANDING)) {
			return top;
		}
		return super.getDismountLocationForPassenger(passenger);
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean isPickable() {
		return !isRemoved() && !isDemoed();
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
		if (level() instanceof ServerLevel level && !isRemoved() && !isDemoed()) {
			OrientedBox box = OrientedBox.car(pose(), hitboxPreset());
			Vec3 c = explosion.center();
			double dx = c.x - box.cx(), dy = c.y - box.cy(), dz = c.z - box.cz();
			double d = Math.sqrt(dx * dx + dy * dy + dz * dz), r = explosion.radius();
			if (d <= 2 * r) {
				float seen = ServerExplosion.getSeenPercent(c, this);
				SimHost host = inSim ? SimHosts.get(level.dimension()) : null;
				if (host != null && level.getGameRules().get(McrlGameRules.EXPLOSION_DEMOS)
						&& DemoRules.demolishes(explosion.getBlockInteraction(), r, d, seen)) {
					host.demolish(carId(), -1);
				} else {
					pushedBy(explosion, d, r, seen);
				}
			}
		}
		return true;
	}

	private void pushedBy(Explosion explosion, double distance, double radius, float seen) {
		SimHost host = inSim && level() instanceof ServerLevel level ? SimHosts.get(level.dimension()) : null;
		if (host == null || seen <= 0f) {
			return;
		}
		OrientedBox box = OrientedBox.car(pose(), hitboxPreset());
		Vec3 v = ExplosionPush.of(explosion, this, new Vec3(box.cx(), box.cy(), box.cz()), seen);
		if (v.lengthSqr() > 0) {
			host.addVelocity(carId(), v.x, v.y, v.z);
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	protected AABB makeBoundingBox(Vec3 pos) {
		Pose p = pose;
		Pose at = p != null ? new Pose(pos.x, pos.y, pos.z, p.qw(), p.qx(), p.qy(), p.qz())
				: Pose.upright(pos.x, pos.y, pos.z, getYRot());
		double[] b = OrientedBox.car(at, hitboxPreset()).aabb();
		return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (DATA_PRESET.equals(key)) {
			setBoundingBox(makeBoundingBox(position()));
		}
	}

	@Override
	protected InterpolationHandler createInterpolationHandler() {
		return IgnoreVanillaMoves.INSTANCE;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(DATA_TEAM, 0);
		builder.define(DATA_PRESET, 0);
		builder.define(DATA_BODY, "");
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput out) {
		CarState s = keptState();
		SimBodyData.write(out, new SimBodyData.Body(s.pose(), s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz()));
		out.putInt("team", team());
		out.putInt("preset", preset());
		out.putString("body", bodyId());
		out.storeNullable("owner", UUIDUtil.CODEC, owner);
		if (matchOwned) {
			out.putBoolean("match", true);
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput in) {
		int team = in.getIntOr("team", 0), preset = in.getIntOr("preset", 0);
		getEntityData().set(DATA_TEAM, team == 1 ? 1 : 0);
		getEntityData().set(DATA_PRESET, preset >= 0 && preset < HitboxPreset.ALL.size() ? preset : 0);
		String body = in.getStringOr("body", "");
		getEntityData().set(DATA_BODY, CarChoice.ID_PATTERN.matcher(body).matches() ? body : "");
		owner = in.read("owner", UUIDUtil.CODEC).orElse(null);
		if (in.getBooleanOr("match", false)) {
			// RootVehicle ignores shouldBeSaved; such a car leaves at once.
			matchOwned = true;
			staleMatchCar = true;
		}
		SimBodyData.Body b = SimBodyData.read(in);
		if (b != null) {
			pending = CarState.of(b.pose(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz());
			pose = b.pose();
			vx = b.vx();
			vy = b.vy();
			vz = b.vz();
			wx = b.wx();
			wy = b.wy();
			wz = b.wz();
		}
	}

	static final class IgnoreVanillaMoves implements InterpolationHandler {
		static final IgnoreVanillaMoves INSTANCE = new IgnoreVanillaMoves();

		@Override
		public @Nullable PositionAndRotation target() {
			return null;
		}

		@Override
		public boolean interpolateTo(PositionPath path, float yRot, float xRot, boolean rotate) {
			return true;
		}

		@Override
		public void interpolate() {
		}

		@Override
		public void applyPredictedMovement(Vec3 movement) {
		}

		@Override
		public boolean hasActiveInterpolation() {
			return false;
		}

		@Override
		public void cancel() {
		}
	}
}
