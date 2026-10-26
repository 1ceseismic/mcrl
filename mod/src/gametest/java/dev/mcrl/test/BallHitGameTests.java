package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.simTick;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;
import static dev.mcrl.test.GameTestSupport.topOf;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.BallHitTuning;
import dev.mcrl.entity.BallHits;
import dev.mcrl.entity.EntityMass;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.math.Pose;
import dev.mcrl.sim.BallState;
import dev.mcrl.sim.SimFrame;

public class BallHitGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");
	private static final double BALL_REST = 1.863;
	private static final double BALL_X = 4.5, TARGET_X = 12.5, LANE_Z = 7.5;

	private static void floor(GameTestHelper h) {
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
	}

	private static BallEntity restingBall(GameTestHelper h) {
		BallEntity ball = McrlEntities.BALL.create(h.getLevel(), EntitySpawnReason.COMMAND);
		if (ball == null) {
			throw h.assertionException("could not create a BallEntity");
		}
		Vec3 a = h.absoluteVec(new Vec3(BALL_X, 0, LANE_Z));
		ball.setSimState(BallEntity.restingAt(a.x, topOf(h, 0) + BALL_REST, a.z));
		if (!h.getLevel().addFreshEntity(ball)) {
			throw h.assertionException("the ball was not added");
		}
		return ball;
	}

	private static SimFrame.BallSnap ballSnap(GameTestHelper h) {
		SimFrame.BallSnap b = host(h).latest().ball();
		if (b == null) {
			throw h.assertionException("no ball in the sim yet");
		}
		return b;
	}

	private static double speed(double vx, double vy, double vz) {
		return Math.sqrt(vx * vx + vy * vy + vz * vz);
	}

	private static void assertResting(GameTestHelper h) {
		SimFrame.BallSnap b = ballSnap(h);
		h.assertTrue(Math.abs(b.cur().y() - (topOf(h, 0) + BALL_REST)) < 0.05 && speed(b.vx(), b.vy(), b.vz()) < 0.1,
				"the ball is not resting yet: y " + b.cur().y() + ", speed " + speed(b.vx(), b.vy(), b.vz()));
	}

	private static long roll(GameTestHelper h, double speed) {
		Vec3 a = h.absoluteVec(new Vec3(BALL_X, 0, LANE_Z));
		host(h).setBall(new BallState(new Pose(a.x, topOf(h, 0) + BALL_REST, a.z, 1, 0, 0, 0), speed, 0, 0, 0, 0, 0));
		return simTick(h);
	}

	private static List<BallHits.Hit> hitsOn(GameTestHelper h, Entity e) {
		return BallHits.of(h.getLevel()).hits().stream().filter(x -> x.entityId() == e.getId()).toList();
	}

	@GameTest(environment = "mcrl-test:ball_knocks_player_no_damage_with_pvp_off", structure = RUNWAY, maxTicks = 300)
	public void ballKnocksPlayerNoDamageWithPvpOff(GameTestHelper h) {
		clean(h);
		terrainKeepsUp(h);
		floor(h);
		MinecraftServer server = h.getLevel().getServer();
		h.getLevel().getGameRules().set(GameRules.PVP, false, server);
		GameProfile profile = new GameProfile(UUID.randomUUID(), "test-ball-player");
		CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
		ServerPlayer p = new ServerPlayer(server, h.getLevel(), profile, cookie.clientInformation());
		Connection connection = new Connection(PacketFlow.SERVERBOUND);
		new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, p, cookie);
		p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
		Vec3 start = h.absoluteVec(new Vec3(TARGET_X, 1.0, LANE_Z));
		p.setPos(start.x, start.y, start.z);
		BallEntity ball = restingBall(h);
		float[] hp = {0};
		Vec3[] knocked = {null, null};
		h.onEachTick(() -> {
			if (knocked[0] == null && !hitsOn(h, p).isEmpty()) {
				knocked[0] = p.position();
				knocked[1] = p.getDeltaMovement();
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> assertResting(h))
				.thenExecute(() -> {
					hp[0] = p.getHealth();
					roll(h, 30);
				})
				.thenWaitUntil(() -> h.assertTrue(knocked[0] != null, "the player has not been hit yet"))
				.thenIdle(30)
				.thenExecute(() -> {
					try {
						List<BallHits.Hit> on = hitsOn(h, p);
						BallHits.Hit hit = on.get(0);
						double m = EntityMass.of(p), dvEnt = speed(hit.dvEnt()[0], hit.dvEnt()[1], hit.dvEnt()[2]);
						double want = (1 + BallHitTuning.E_PLAYER) * hit.closing() / (1 / EntityMass.BALL + 1 / m) / m;
						LOG.info("ball_knocks_player_no_damage_with_pvp_off: closing {}, |dvEnt| {} (e 0.3: {}), moved {} at the"
								+ " hit, {} now, delta {}, {} hits, lost {}", hit.closing(), dvEnt, want,
								knocked[0].x - start.x, p.getX() - start.x, knocked[1], on.size(), hp[0] - p.getHealth());
						h.assertTrue(on.stream().allMatch(x -> x.damage() == 0f), "a hit dealt damage");
						h.assertTrue(p.getHealth() == hp[0], "the player lost " + (hp[0] - p.getHealth()) + " HP");
						h.assertTrue(Math.abs(dvEnt - want) <= 1e-6 * want, "|dvEnt| " + dvEnt + ", e 0.3 gives " + want);
						h.assertTrue(knocked[0].x > start.x, "the player was not moved east: " + (knocked[0].x - start.x));
						h.assertTrue(knocked[1].x > 0.5, "the player's launch: " + knocked[1]);
						h.assertTrue(p.getX() >= knocked[0].x, "the player was pushed back west to " + (p.getX() - start.x));
					} finally {
						server.getGameRules().set(GameRules.PVP, true, server);
						p.discard();
						ball.discard();
					}
				})
				.thenSucceed();
	}
}
