package dev.mcrl.client.screen.tabs;

import java.nio.file.InvalidPathException;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.mcrl.bot.BotPolicies;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.client.screen.tabs.SetupTab.CommitBox;
import dev.mcrl.client.screen.tabs.SetupTab.CutLine;
import dev.mcrl.match.ArenaMeshes;
import dev.mcrl.match.MatchConfig;
import dev.mcrl.match.MatchSize;

public final class MatchTab implements SettingsTab {
	private static final String DIR_KEY = "mcrl.settings.arena_meshes_dir", POLICY_KEY = "mcrl.settings.bot_policy",
			CONVERT_KEY = "mcrl.settings.convert_stadium";
	private static final int LINE_H = SetupTab.LINE_H, BOX_H = SetupTab.BOX_H;

	private @Nullable CutLine found;
	private @Nullable CutLine arenaLine;
	private @Nullable Button convert;
	private @Nullable CutLine policyLine;

	@Override
	public Id id() {
		return Id.MATCH;
	}

	private static Component found(String dirText) {
		return Component.translatableWithFallback("mcrl.settings.arena_meshes_found", "%s mesh files found",
				meshFiles(dirText));
	}

	private static int meshFiles(String dirText) {
		if (dirText.isBlank()) {
			return 0;
		}
		try {
			return ArenaMeshes.files(ArenaMeshes.folder(dirText.trim())).size();
		} catch (InvalidPathException e) {
			return 0;
		}
	}

	private static Component arenaStatus(String dirText) {
		ArenaMeshes.Status s = ArenaMeshes.status(dirText);
		if (s.source() == null) {
			return Component.translatableWithFallback("mcrl.settings.arena_status_none",
					"Stadium arena: none (convert the stadium or set the folder)");
		}
		return Component.translatableWithFallback("mcrl.settings.arena_status", "Stadium arena: %s/%s known (%s)",
				s.known(), ArenaMeshes.SOCCAR_MESHES, s.source().name().toLowerCase(Locale.ROOT));
	}

	private static Component policyStatus(String setting) {
		return Component.translatableWithFallback("mcrl.settings.bot_policy_status", "Bot policy: %s",
				BotPolicies.describe(setting));
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		McrlConfig cfg = McrlConfig.get();
		int x = screen.left();
		int y = screen.top();
		screen.add(SetupTab.pathBox(screen, y, Component.translatableWithFallback(DIR_KEY, "Arena meshes folder"),
				Component.translatableWithFallback("mcrl.settings.arena_meshes_hint", "RocketSim's collision_meshes folder"),
				cfg.arenaMeshesDir, this::commitMeshes));
		int lineW = screen.width() - McrlSettingsScreen.COL_W - McrlSettingsScreen.GAP;
		found = screen.add(new CutLine(x, y + BOX_H + 4, lineW, LINE_H, found(cfg.arenaMeshesDir)));
		arenaLine = screen.add(new CutLine(x, y + BOX_H + 4 + LINE_H, lineW, LINE_H, arenaStatus(cfg.arenaMeshesDir)));
		convert = screen.add(Button.builder(Component.translatableWithFallback(CONVERT_KEY, "Convert stadium"),
				b -> onConvert()).bounds(x + lineW + McrlSettingsScreen.GAP, y + BOX_H + 4, McrlSettingsScreen.COL_W, 20)
				.build());
		updateConvert();
		int rowY = y + BOX_H + 4 + Math.max(2 * LINE_H, 20) + 6;
		int right = x + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP;
		screen.add(CycleButton.<MatchSize>builder(s -> Component.literal(s.label()), cfg.match.defaultSize())
				.withValues(MatchSize.values())
				.create(x, rowY, McrlSettingsScreen.COL_W, 20,
						Component.translatableWithFallback("mcrl.settings.match_size", "Default match size"),
						(b, size) -> McrlConfig.edit(c -> c.match = c.match.withDefaultSize(size))));
		screen.add(CycleButton.onOffBuilder(cfg.match.farDetail())
				.create(right, rowY, McrlSettingsScreen.COL_W, 20,
						Component.translatableWithFallback("mcrl.settings.far_detail", "Far stadium detail"),
						(b, on) -> McrlConfig.edit(c -> c.match = c.match.withFarDetail(on))));
		int botY = rowY + McrlSettingsScreen.ROW_H;
		screen.add(CycleButton.onOffBuilder(cfg.match.bots())
				.create(x, botY, McrlSettingsScreen.COL_W, 20,
						Component.translatableWithFallback("mcrl.settings.match_bots", "Bots fill empty slots"),
						(b, on) -> McrlConfig.edit(c -> c.match = c.match.withBots(on))));
		screen.add(new SamplingSlider(right, botY, cfg.match.botTemperature()));
		int policyY = botY + McrlSettingsScreen.ROW_H;
		screen.add(SetupTab.pathBox(screen, policyY, Component.translatableWithFallback(POLICY_KEY, "Bot policy"),
				Component.translatableWithFallback("mcrl.settings.bot_policy_hint", "POLICY.lt or checkpoint folder"),
				cfg.botPolicy, this::commitPolicy));
		policyLine = screen.add(new CutLine(x, policyY + BOX_H + 4, screen.width(), LINE_H,
				policyStatus(cfg.botPolicy)));
	}

	private void commitMeshes(CommitBox box) {
		String v = box.path(ArenaMeshes::folder);
		McrlConfig cfg = McrlConfig.get();
		if (v == null || v.equals(cfg.arenaMeshesDir)) {
			return;
		}
		McrlConfig.edit(c -> c.arenaMeshesDir = v);
		if (found != null) {
			found.setText(found(v));
		}
		if (arenaLine != null) {
			arenaLine.setText(arenaStatus(v));
		}
	}

	// re-described when unchanged: the file may have changed
	private void commitPolicy(CommitBox box) {
		String v = box.path(ArenaMeshes::folder);
		if (v == null) {
			return;
		}
		if (!v.equals(McrlConfig.get().botPolicy)) {
			McrlConfig.edit(c -> c.botPolicy = v);
		}
		if (policyLine != null) {
			policyLine.setText(policyStatus(v));
		}
	}

	private void updateConvert() {
		Button b = convert;
		if (b == null) {
			return;
		}
		boolean enabled = RlConversion.game().conversionEnabled(), busy = RlConversion.game().isRunning();
		b.active = enabled && !busy;
		b.setMessage(busy ? Component.translatableWithFallback("mcrl.settings.converting_stadium", "Converting…")
				: Component.translatableWithFallback(CONVERT_KEY, "Convert stadium"));
		b.setTooltip(enabled ? null : Tooltip.create(Component.translatable(SetupTab.CONVERT_OFF_KEY)));
	}

	private void onConvert() {
		boolean started = RlConversion.game().convertStadium();
		updateConvert();
		if (!started) {
			return;
		}
		Button mine = convert;
		Thread.ofPlatform().daemon().name("mcrl-stadium-watch").start(() -> {
			try {
				while (RlConversion.game().isRunning()) {
					Thread.sleep(250);
				}
			} catch (InterruptedException e) {
				return;
			}
			Component text = arenaStatus(McrlConfig.get().arenaMeshesDir);
			Minecraft.getInstance().execute(() -> {
				if (convert == mine && arenaLine != null) {
					updateConvert();
					arenaLine.setText(text);
				}
			});
		});
	}

	private static final class SamplingSlider extends AbstractSliderButton {
		private static final int STEPS = Math.round(MatchConfig.MAX_BOT_TEMPERATURE * 10);

		SamplingSlider(int x, int y, float temperature) {
			super(x, y, McrlSettingsScreen.COL_W, 20, Component.empty(), temperature / MatchConfig.MAX_BOT_TEMPERATURE);
			updateMessage();
		}

		private int step() {
			return Math.max(0, Math.min(STEPS, Math.round((float) value * STEPS)));
		}

		@Override
		protected void updateMessage() {
			int k = step();
			Component v = k == 0
					? Component.translatableWithFallback("mcrl.settings.bot_sampling_off", "off (best action)")
					: Component.translatableWithFallback("mcrl.settings.bot_sampling_t", "temperature %s",
							k / 10 + "." + k % 10);
			setMessage(Component.translatableWithFallback("mcrl.settings.bot_sampling", "Bot sampling: %s", v));
		}

		@Override
		protected void applyValue() {
			int k = step();
			value = k / (double) STEPS;
			float t = k / 10f;
			if (t != McrlConfig.get().match.botTemperature()) {
				McrlConfig.edit(c -> c.match = c.match.withBotTemperature(t));
			}
		}
	}
}
