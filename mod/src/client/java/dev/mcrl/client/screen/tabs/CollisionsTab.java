package dev.mcrl.client.screen.tabs;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.WorldRules;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.net.McrlPayloads.RulesS2C;

public final class CollisionsTab implements SettingsTab {
	private static final List<String> RULES = List.of("mcrl:step_up", "mcrl:car_damage", "mcrl:car_demos",
			"mcrl:explosion_demos", "mcrl:surface_physics", "mcrl:fluid_physics", "mcrl:ball_hits_entities");

	static void sendToServer(String command) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.player.connection.sendCommand(command);
		}
	}

	@Override
	public Id id() {
		return Id.COLLISIONS;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		RulesS2C r = WorldRules.get();
		boolean canEdit = r != null && r.canEdit();
		int x = screen.left();
		int y = screen.top();
		for (String rule : RULES) {
			screen.add(ruleButton(x, y, rule, r, canEdit));
			y += McrlSettingsScreen.ROW_H;
		}
		screen.text(x, y, screen.width(), Component.translatable("mcrl.settings.steps_always"));
	}

	private static Button ruleButton(int x, int y, String rule, @Nullable RulesS2C r, boolean canEdit) {
		Boolean value = r == null ? null : r.get(rule);
		Component state = Component.translatable(value == null ? "mcrl.settings.unknown"
				: value ? "mcrl.settings.on" : "mcrl.settings.off");
		String key = "mcrl.settings." + rule.substring("mcrl:".length());
		Button b = Button.builder(Component.translatable(key, state), btn -> {
			RulesS2C now = WorldRules.get();
			Boolean current = now == null ? null : now.get(rule);
			if (current != null) {
				sendToServer("gamerule " + rule + " " + !current);
			}
		}).bounds(x, y, 2 * McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP, 20).build();
		b.active = canEdit && value != null;
		if (r == null) {
			b.setTooltip(Tooltip.create(Component.translatable("mcrl.settings.no_mcrl_server")));
		} else if (!canEdit) {
			b.setTooltip(Tooltip.create(Component.translatable("mcrl.settings.needs_permission")));
		}
		return b;
	}
}
