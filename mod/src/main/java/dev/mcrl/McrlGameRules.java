package dev.mcrl;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

import dev.mcrl.natives.McrlNative;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public final class McrlGameRules {
	public static final GameRule<Boolean> STEP_UP = GameRuleBuilder.forBoolean(false)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "step_up"));

	public static final GameRule<Boolean> CAR_DAMAGE = GameRuleBuilder.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "car_damage"));

	public static final GameRule<Boolean> CAR_DEMOS = GameRuleBuilder.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "car_demos"));

	public static final GameRule<Boolean> EXPLOSION_DEMOS = GameRuleBuilder.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "explosion_demos"));

	public static final GameRule<Boolean> SURFACE_PHYSICS = GameRuleBuilder.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "surface_physics"));

	public static final GameRule<Boolean> FLUID_PHYSICS = GameRuleBuilder.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "fluid_physics"));

	public static final GameRule<Boolean> BALL_HITS_ENTITIES = GameRuleBuilder.forBoolean(true)
			.category(GameRuleCategory.MISC)
			.buildAndRegister(Identifier.fromNamespaceAndPath(Mcrl.MOD_ID, "ball_hits_entities"));

	public static final SequencedMap<String, GameRule<Boolean>> ALL = ordered(
			STEP_UP, CAR_DAMAGE, CAR_DEMOS, EXPLOSION_DEMOS, SURFACE_PHYSICS, FLUID_PHYSICS,
			BALL_HITS_ENTITIES);

	private McrlGameRules() {
	}

	@SafeVarargs
	private static SequencedMap<String, GameRule<Boolean>> ordered(GameRule<Boolean>... rules) {
		SequencedMap<String, GameRule<Boolean>> m = new LinkedHashMap<>();
		for (GameRule<Boolean> r : rules) {
			m.put(r.getIdentifier().toString(), r);
		}
		return Collections.unmodifiableSequencedMap(m);
	}

	public static void register() {
		GameRuleEvents.changeCallback(STEP_UP).register((value, server) -> {
			for (ServerLevel level : server.getAllLevels()) {
				SimHost host = SimHosts.get(level.dimension());
				if (host != null) {
					host.setStepUp(value);
				}
			}
		});
		GameRuleEvents.changeCallback(CAR_DEMOS).register((value, server) -> {
			for (SimHost host : SimHosts.all()) {
				pushCarDemos(host, value);
			}
		});
		// Callbacks run before the rule takes the value.
		GameRuleEvents.changeCallback(SURFACE_PHYSICS).register((value, server) -> pushExtras(
				withBit(extras(server.getGameRules()), McrlNative.EXTRAS_SURFACES, value)));
		GameRuleEvents.changeCallback(FLUID_PHYSICS).register((value, server) -> pushExtras(
				withBit(extras(server.getGameRules()), McrlNative.EXTRAS_FLUIDS, value)));
		for (Map.Entry<String, GameRule<Boolean>> e : ALL.entrySet()) {
			String id = e.getKey();
			GameRuleEvents.changeCallback(e.getValue())
					.register((value, server) -> McrlPayloads.broadcastRules(server, id, value));
		}
	}

	public static int extras(GameRules rules) {
		return extras(rules.get(SURFACE_PHYSICS), rules.get(FLUID_PHYSICS));
	}

	public static int extras(boolean surfaces, boolean fluids) {
		return (surfaces ? McrlNative.EXTRAS_SURFACES : 0) | (fluids ? McrlNative.EXTRAS_FLUIDS : 0);
	}

	private static int withBit(int flags, int bit, boolean on) {
		return on ? flags | bit : flags & ~bit;
	}

	private static void pushExtras(int flags) {
		for (SimHost host : SimHosts.all()) {
			if (host.kind() == SimHost.Kind.TERRAIN) {
				host.setExtras(flags);
			}
		}
	}

	public static void pushCarDemos(SimHost host, boolean on) {
		if (host.kind() == SimHost.Kind.TERRAIN && host.role() == SimHost.Role.SERVER) {
			host.setDemos(on ? 1 : 0, true);
		}
	}
}
