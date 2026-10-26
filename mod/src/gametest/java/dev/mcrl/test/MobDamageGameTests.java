package dev.mcrl.test;

import static dev.mcrl.test.GameTestSupport.EAST;
import static dev.mcrl.test.GameTestSupport.RUNWAY;
import static dev.mcrl.test.GameTestSupport.assertRunning;
import static dev.mcrl.test.GameTestSupport.clean;
import static dev.mcrl.test.GameTestSupport.drive;
import static dev.mcrl.test.GameTestSupport.fill;
import static dev.mcrl.test.GameTestSupport.host;
import static dev.mcrl.test.GameTestSupport.simTick;
import static dev.mcrl.test.GameTestSupport.spawnCar;
import static dev.mcrl.test.GameTestSupport.terrainKeepsUp;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import dev.mcrl.McrlGameRules;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.HitTuning;
import dev.mcrl.entity.MobHits;
import dev.mcrl.sim.CarState;

public class MobDamageGameTests {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");

	private record Seen(CarEntity car, ServerPlayer driver, LivingEntity victim, float healthBefore, MobHits.Hit hit) {
		float lost() {
			return healthBefore - victim.getHealth();
		}
	}

	private static void damageRun(GameTestHelper h, int uuPerSecond, boolean driver,
			Function<GameTestHelper, LivingEntity> victimOf, Consumer<ServerPlayer> beforeStart, Consumer<Seen> check) {
		float throttle = uuPerSecond >= 2300 ? 1f : 1f / 127f;
		clean(h);
		terrainKeepsUp(h);
		fill(h, 0, 0, 0, 47, 0, 15, Blocks.STONE);
		h.getLevel().getGameRules().set(GameRules.PVP, true, h.getLevel().getServer());
		CarEntity car = spawnCar(h, 3.0, 1.34, 3.0, EAST);
		ServerPlayer rider = driver ? h.makeMockServerPlayerInLevel() : null;
		if (rider != null) {
			rider.startRiding(car, true, true);
			h.assertTrue(car.getControllingPassenger() == rider, "the mock player is not driving");
		}
		LivingEntity victim = victimOf.apply(h);
		beforeStart.accept(rider);
		float before = victim.getHealth();
		long t0 = simTick(h);
		MobHits hits = MobHits.of(h.getLevel());
		boolean[] go = {false};
		int[] seq = {0};
		h.onEachTick(() -> {
			if (go[0]) {
				host(h).submitInput(car.getId(), drive(++seq[0], throttle, false));
			}
		});
		h.startSequence()
				.thenWaitUntil(() -> assertRunning(h, car, t0))
				.thenExecute(() -> {
					host(h).submitInput(car.getId(), drive(0, throttle, false));
					host(h).setCar(car.getId(), CarState.of(car.simState().pose(), uuPerSecond / 50.0, 0, 0, 0, 0, 0));
					go[0] = true;
				})
				.thenWaitUntil(() -> h.assertTrue(hits.hitCount(victim.getId()) >= 1, "the victim has not been hit yet"))
				.thenExecuteAfter(3, () -> {
					try {
						MobHits.Hit hit = hits.lastHit(victim.getId());
						LOG.info("damage run at {} UU/s: closing {}, damage {}, lost {}, health {}, last source {}", uuPerSecond,
								hit.closing(), hit.damage(), before - victim.getHealth(), victim.getHealth(), victim.getLastDamageSource());
						h.assertTrue(hits.hitCount(victim.getId()) == 1, "the victim was hit " + hits.hitCount(victim.getId()) + " times");
						check.accept(new Seen(car, rider, victim, before, hit));
					} finally {
						h.getLevel().getGameRules().set(McrlGameRules.CAR_DAMAGE, true, h.getLevel().getServer());
						h.getLevel().getGameRules().set(GameRules.PVP, true, h.getLevel().getServer());
						if (rider != null) {
							rider.stopRiding();
							rider.discard();
						}
						car.discard();
						victim.discard();
					}
				})
				.thenSucceed();
	}

	private static Function<GameTestHelper, LivingEntity> playerAhead(GameType mode) {
		return h -> {
			ServerPlayer p;
			if (mode == GameType.CREATIVE) {
				p = h.makeMockServerPlayerInLevel();
			} else {
				GameProfile profile = new GameProfile(UUID.randomUUID(), "test-survival-player");
				CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
				p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), profile, cookie.clientInformation());
				Connection connection = new Connection(PacketFlow.SERVERBOUND);
				new EmbeddedChannel(connection);
				h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
				p.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
				p.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
			}
			Vec3 at = h.absoluteVec(new Vec3(14.0, 1.0, 3.0));
			p.setPos(at.x, at.y, at.z);
			return p;
		};
	}

	@GameTest(environment = "mcrl-test:pvp_off_player_not_damaged", structure = RUNWAY, maxTicks = 300)
	public void pvpOffPlayerNotDamaged(GameTestHelper h) {
		damageRun(h, 1000, false, playerAhead(GameType.SURVIVAL), p -> h.getLevel().getGameRules().set(GameRules.PVP, false,
				h.getLevel().getServer()), s -> {
			h.assertTrue(!h.getLevel().isPvpAllowed(), "PvP is still allowed");
			h.assertTrue(s.hit().damage() == 0f && s.lost() == 0f, "the player was hurt with PvP off: lost " + s.lost());
			h.assertTrue(s.hit().horizontal() > 0, "the player was not knocked");
		});
	}

	@GameTest(environment = "mcrl-test:creative_player_not_damaged", structure = RUNWAY, maxTicks = 300)
	public void creativePlayerNotDamaged(GameTestHelper h) {
		damageRun(h, 2300, false, playerAhead(GameType.CREATIVE), p -> {
		}, s -> {
			h.assertTrue(s.hit().closing() >= HitTuning.LETHAL_AT, "closing " + s.hit().closing());
			h.assertTrue(s.hit().damage() == 0f && s.lost() == 0f && s.victim().isAlive(), "the creative player was hurt: lost " + s.lost());
			h.assertTrue(s.hit().horizontal() > 0, "the creative player was not knocked");
		});
	}

	@GameTest(environment = "mcrl-test:killed_player_not_knocked", structure = RUNWAY, maxTicks = 300)
	public void killedPlayerNotKnocked(GameTestHelper h) {
		Vec3[] before = new Vec3[1];
		damageRun(h, 2300, false, playerAhead(GameType.SURVIVAL), p -> {
		}, s -> {
			h.assertTrue(s.hit().damage() == Float.MAX_VALUE, "damage " + s.hit().damage());
			h.assertTrue(!s.victim().isAlive(), "the survival player survived 2300 UU/s");
			Vec3 start = h.absoluteVec(new Vec3(14.0, 1.0, 3.0));
			h.assertTrue(s.hit().position().distanceTo(start) <= 1e-9, "the dead player was moved to " + s.hit().position());
			h.assertTrue(s.hit().delta().equals(s.victim().getDeltaMovement()) && s.hit().delta().y <= 0.0,
					"the dead player was launched: " + s.hit().delta());
			h.assertTrue(s.hit().syncBase() == null, "the dead player was synced");
			h.assertTrue(s.victim().position().distanceTo(start) <= 1e-9, "the dead player is at " + s.victim().position());
		});
	}
}
