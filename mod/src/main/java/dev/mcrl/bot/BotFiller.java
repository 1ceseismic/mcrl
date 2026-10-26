package dev.mcrl.bot;

import java.lang.ref.WeakReference;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import dev.mcrl.entity.CarEntity;
import dev.mcrl.entity.McrlEntities;
import dev.mcrl.match.ActiveMatch;
import dev.mcrl.match.MatchConfig;
import dev.mcrl.match.MatchManager;
import dev.mcrl.match.Slot;
import dev.mcrl.sim.CarState;
import dev.mcrl.sim.SimHost;

public final class BotFiller {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final long SEED_STEP = 0x9E3779B97F4A7C15L;

	private static WeakReference<@Nullable ActiveMatch> told = new WeakReference<>(null);

	private BotFiller() {
	}

	public static void onEmpty(ActiveMatch m, Slot s) {
		try {
			fill(m, s);
		} catch (RuntimeException e) {
			LOG.warn("mcrl: no bot for slot {}", s.index(), e);
			noBots(m, e.toString());
		}
	}

	private static void fill(ActiveMatch m, Slot s) {
		MatchConfig cfg = MatchManager.config();
		if (!cfg.bots()) {
			return;
		}
		BotPolicies.Loaded policy;
		try {
			policy = BotPolicies.load(cfg.botPolicy());
		} catch (BotPolicyException e) {
			noBots(m, e.getMessage());
			return;
		}
		SimHost host = m.host();
		if (host == null) {
			return;
		}
		int team = s.team();
		List<CarState> spawns = host.soccarSpawns(team);
		if (spawns.isEmpty()) {
			return;
		}
		ServerLevel stadium = m.stadium();
		CarEntity car = McrlEntities.CAR.create(stadium, EntitySpawnReason.EVENT);
		if (car == null) {
			LOG.warn("mcrl: could not make a bot car for slot {}", s.index());
			return;
		}
		int inTeam = s.index() - team * m.size().perTeam();
		car.setMatchOwned(true);
		car.setTeam(team);
		car.setSimState(spawns.get(inTeam % spawns.size()));
		car.setBot(new BotSpec(policy, cfg.botTemperature(), SEED_STEP * (s.index() + 1) + stadium.getGameTime()));
		if (!stadium.addFreshEntity(car)) {
			LOG.warn("mcrl: could not spawn a bot car for slot {}", s.index());
			return;
		}
		m.assignBot(s.index(), car);
	}

	public static void onRelease(ActiveMatch m, Slot s) {
		if (m.body(s.carEntityId()) instanceof CarEntity car && car.isBot()) {
			car.setBot(null);
		}
	}

	private static void noBots(ActiveMatch m, String why) {
		if (told.get() == m) {
			return;
		}
		told = new WeakReference<>(m);
		LOG.warn("mcrl: no bots this match: {}", why);
		ServerPlayer creator = m.stadium().getServer().getPlayerList().getPlayer(m.creator());
		if (creator == null) {
			return;
		}
		Component c = Component.translatableWithFallback("mcrl.match.bots_unavailable", "No bots this match: %s", why);
		creator.sendSystemMessage(c);
	}
}
