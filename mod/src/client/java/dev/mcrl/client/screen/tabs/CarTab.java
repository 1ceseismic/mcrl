package dev.mcrl.client.screen.tabs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import dev.mcrl.client.car.CarChoiceClient;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.CarSettings;
import dev.mcrl.client.config.McrlConfig.Team;
import dev.mcrl.client.rl.BodyJobs;
import dev.mcrl.client.rl.BodyStatus;
import dev.mcrl.client.rl.RlCache;
import dev.mcrl.client.rl.ThumbTextures;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.rlassets.bodies.BodyIds;
import dev.mcrl.rlassets.bodies.BodyIndex;
import dev.mcrl.sim.HitboxPreset;

public final class CarTab implements SettingsTab {
	private static final int BODY_ROW_H = BodyButton.H + 4;

	@Override
	public Id id() {
		return Id.CAR;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		int left = screen.left();
		int right = screen.left() + McrlSettingsScreen.COL_W + McrlSettingsScreen.GAP;
		int top = screen.top();
		McrlConfig cfg = McrlConfig.get();
		screen.add(CycleButton.<Team>builder(
				t -> Component.translatable("mcrl.settings.team." + t.name().toLowerCase(Locale.ROOT)), cfg.team)
				.withValues(Team.values())
				.create(left, top, McrlSettingsScreen.COL_W, 20, Component.translatable("mcrl.settings.team"),
						(b, t) -> {
							cfg.team = t;
							cfg.save();
							CarChoiceClient.send();
						}));
		String chosen = BodyIds.isDefault(cfg.car.body()) ? BodyIds.OCTANE : cfg.car.body();
		String preset = HitboxPreset.of(cfg.car.preset()).name().toLowerCase(Locale.ROOT);
		Button hitbox = screen.add(Button.builder(Component.translatable("mcrl.settings.car.hitbox",
				Component.translatable("mcrl.settings.car.preset." + preset)), b -> { })
				.bounds(right, top, McrlSettingsScreen.COL_W, 20).build());
		hitbox.active = false;
		BodyStatus status = BodyJobs.game().status(chosen);
		screen.text(left, top + McrlSettingsScreen.ROW_H + 6, screen.width(),
				Component.translatable("mcrl.settings.car.status." + status.name().toLowerCase(Locale.ROOT)));
		int y = top + 2 * McrlSettingsScreen.ROW_H;
		RlCache rl = RlCache.loaded();
		BodyIndex index = rl == null ? null : rl.bodies().index();
		if (index == null) {
			screen.add(new BodyButton(screen, left, y, BodyIds.OCTANE, BodyIds.label(BodyIds.OCTANE), 0, chosen));
			screen.add(Button.builder(Component.translatable("mcrl.settings.car.scan"), b -> BodyJobs.game().scan())
					.bounds(right, y + (BodyButton.H - 20) / 2, McrlSettingsScreen.COL_W, 20).build());
			return;
		}
		List<BodyIndex.Entry> entries = new ArrayList<>(index.bodies());
		entries.sort(Comparator.comparing((BodyIndex.Entry e) -> !e.supported()).thenComparing(BodyIndex.Entry::label)
				.thenComparing(BodyIndex.Entry::id));
		int n = 0;
		for (BodyIndex.Entry e : entries) {
			BodyButton b = screen.add(new BodyButton(screen, n % 2 == 0 ? left : right, y + n / 2 * BODY_ROW_H,
					e.id(), e.label(), e.preset(), chosen));
			if (!e.supported()) {
				b.active = false;
				b.setTooltip(Tooltip.create(Component.translatable("mcrl.settings.car.unsupported",
						String.valueOf(e.why()))));
			}
			n++;
		}
	}

	private static final class BodyButton extends AbstractButton {
		static final int W = 150, H = 36;
		private static final int THUMB = 32, TEXTURE_PX = 128;
		private static final int PLACEHOLDER_ARGB = 0xFF505050, OUTLINE_ARGB = 0xFFFFFFFF, TEXT_ARGB = 0xFFFFFFFF,
				INACTIVE_TEXT_ARGB = 0xFFA0A0A0;

		private final McrlSettingsScreen screen;
		private final String id;
		private final String label;
		private final int preset;
		private final boolean selected;

		BodyButton(McrlSettingsScreen screen, int x, int y, String id, String label, int preset, String chosen) {
			super(x, y, W, H, Component.translatable("mcrl.settings.car.body", label));
			this.screen = screen;
			this.id = id;
			this.label = label;
			this.preset = preset;
			this.selected = id.equals(chosen);
		}

		@Override
		public void onPress(InputWithModifiers input) {
			McrlConfig.edit(c -> c.car = new CarSettings(id, preset));
			CarChoiceClient.send();
			BodyJobs.game().request(id);
			screen.rebuild();
		}

		@Override
		protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			extractDefaultSprite(g);
			Font font = Minecraft.getInstance().font;
			int x = getX(), y = getY(), tx = x + 2, ty = y + (H - THUMB) / 2;
			Identifier thumb = ThumbTextures.get(id);
			if (thumb != null) {
				g.blit(RenderPipelines.GUI_TEXTURED, thumb, tx, ty, 0, 0, THUMB, THUMB, TEXTURE_PX, TEXTURE_PX,
						TEXTURE_PX, TEXTURE_PX);
			} else {
				g.fill(tx, ty, tx + THUMB, ty + THUMB, PLACEHOLDER_ARGB);
				String first = label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(Locale.ROOT);
				g.centeredText(font, Component.literal(first), tx + THUMB / 2, ty + (THUMB - 8) / 2, TEXT_ARGB);
			}
			g.text(font, Component.literal(label), tx + THUMB + 4, y + (H - 8) / 2,
					active ? TEXT_ARGB : INACTIVE_TEXT_ARGB);
			if (selected) {
				g.outline(x, y, W, H, OUTLINE_ARGB);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
