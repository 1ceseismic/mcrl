package dev.mcrl.client.screen.tabs;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.client.rl.SetupStatus;
import dev.mcrl.client.screen.McrlSettingsScreen;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.McrlPaths;
import dev.mcrl.rlassets.UmodelRunner;
import dev.mcrl.rlassets.VgmstreamRunner;

public final class SetupTab implements SettingsTab {
	static final String CONVERT_OFF_KEY = "mcrl.setup.convert_off";
	static final int LINE_H = 11, BOX_H = 18, LABEL_W = 110, LABEL_GAP = 4;
	private static final int PITCH = LINE_H + BOX_H + 4;
	private static final ExecutorService IO = Executors.newCachedThreadPool(
			r -> Thread.ofPlatform().daemon().name("mcrl-setup-status").unstarted(r));
	private static final Pattern UNMOUNTED_PATH = Pattern.compile("(?:last found|installed) at (.+?)(?:,| but) ");

	private final AtomicInteger generation = new AtomicInteger();
	private final AtomicInteger watching = new AtomicInteger(-1);
	private CutLine[] lines = new CutLine[0];
	private @Nullable Button convert;

	private record Field(String key, Function<McrlConfig, String> get, BiConsumer<McrlConfig, String> set,
			Supplier<String> fallback) {
	}

	private static final List<Field> FIELDS = List.of(
			new Field("mcrl.setup.field.rl", c -> c.rlCookedDir, (c, v) -> c.rlCookedDir = v,
					() -> Component.translatable("mcrl.setup.default.rl").getString()),
			new Field("mcrl.setup.field.umodel", c -> c.umodelPath, (c, v) -> c.umodelPath = v,
					() -> UmodelRunner.binary("").toString()),
			new Field("mcrl.setup.field.vgmstream", c -> c.vgmstreamPath, (c, v) -> c.vgmstreamPath = v,
					() -> VgmstreamRunner.binary("").toString()),
			new Field("mcrl.setup.field.cache", c -> c.rlCacheDir, (c, v) -> c.rlCacheDir = v,
					() -> McrlPaths.cacheDir().toString()));

	@Override
	public Id id() {
		return Id.SETUP;
	}

	@Override
	public void build(McrlSettingsScreen screen) {
		generation.incrementAndGet();
		int x = screen.left();
		int y = screen.top();
		lines = new CutLine[FIELDS.size()];
		for (int i = 0; i < FIELDS.size(); i++) {
			Field field = FIELDS.get(i);
			lines[i] = screen.add(new CutLine(x, y, screen.width(), LINE_H,
					Component.translatable("mcrl.setup.checking")));
			screen.add(pathBox(screen, y + LINE_H + 1, Component.translatable(field.key()),
					Component.literal(field.fallback().get()), field.get().apply(McrlConfig.get()),
					box -> commit(field, box)));
			y += PITCH;
		}
		int w = (screen.width() - McrlSettingsScreen.GAP) / 2;
		screen.add(Button.builder(Component.translatable("mcrl.setup.recheck"), b -> recheck()).bounds(x, y, w, 20)
				.build());
		convert = screen.add(Button.builder(Component.translatable("mcrl.setup.convert"), b -> onConvert())
				.bounds(x + w + McrlSettingsScreen.GAP, y, w, 20).build());
		updateConvert();
		recheck();
		if (RlConversion.game().isRunning()) {
			watchRun();
		}
	}

	static CommitBox pathBox(McrlSettingsScreen screen, int y, Component name, Component hint, String value,
			Consumer<CommitBox> onCommit) {
		int x = screen.left();
		screen.add(new CutLine(x, y, LABEL_W, BOX_H, name));
		CommitBox box = new CommitBox(x + LABEL_W + LABEL_GAP, y, screen.width() - LABEL_W - LABEL_GAP, BOX_H, name,
				onCommit);
		box.setMaxLength(1024);
		box.setHint(hint);
		box.setValue(value);
		return box;
	}

	@Override
	public void closed() {
		generation.incrementAndGet();
	}

	private void commit(Field field, CommitBox box) {
		String v = box.path(Path::of);
		if (v == null || v.equals(field.get().apply(McrlConfig.get()))) {
			return;
		}
		McrlConfig.edit(c -> field.set().accept(c, v));
		recheck();
	}

	private void recheck() {
		int gen = generation.get();
		for (CutLine l : lines) {
			l.setText(Component.translatable("mcrl.setup.checking"));
		}
		IO.execute(() -> {
			SetupStatus s = null;
			String failure = null;
			try {
				s = RlConversion.game().status();
			} catch (RuntimeException e) {
				String m = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
				failure = m.length() > 80 ? m.substring(0, 79) + "…" : m;
			}
			SetupStatus result = s;
			String failed = failure;
			Minecraft.getInstance().execute(() -> {
				if (gen == generation.get()) {
					show(result, failed);
				}
			});
		});
	}

	private void show(@Nullable SetupStatus s, @Nullable String failure) {
		if (s == null) {
			for (CutLine l : lines) {
				l.setText(Component.translatable("mcrl.setup.check_failed", String.valueOf(failure)));
			}
		} else {
			List<Component> text = List.of(rlLine(s.rl()), tool("umodel", s.umodel()),
					tool("vgmstream-cli", s.vgmstream()), cacheLine(s.cache()));
			for (int i = 0; i < lines.length; i++) {
				lines[i].setText(text.get(i), i == 0 ? Component.literal(s.rl().hint()) : null);
			}
		}
		updateConvert();
	}

	private void updateConvert() {
		Button b = convert;
		if (b == null) {
			return;
		}
		boolean enabled = RlConversion.game().conversionEnabled();
		boolean busy = RlConversion.game().isRunning();
		b.active = enabled && !busy;
		b.setMessage(Component.translatable(busy ? "mcrl.setup.converting" : "mcrl.setup.convert"));
		b.setTooltip(enabled ? null : Tooltip.create(Component.translatable(CONVERT_OFF_KEY)));
	}

	private void onConvert() {
		boolean started = RlConversion.game().convertNow();
		updateConvert();
		if (started) {
			watchRun();
		}
	}

	private void watchRun() {
		int gen = generation.get();
		if (watching.getAndSet(gen) == gen) {
			return;
		}
		IO.execute(() -> {
			try {
				while (RlConversion.game().isRunning() && gen == generation.get()) {
					Thread.sleep(250);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			Minecraft.getInstance().execute(() -> {
				if (gen == generation.get()) {
					recheck();
				}
			});
		});
	}

	private static Component rlLine(LocateResult rl) {
		return switch (rl.state()) {
			case FOUND -> Component.translatable("mcrl.setup.rl.found",
					String.valueOf(rl.installDir() != null ? rl.installDir() : rl.cookedDir()));
			case FOUND_UNMOUNTED -> {
				Matcher m = UNMOUNTED_PATH.matcher(rl.hint() + " ");
				yield m.find() ? Component.translatable("mcrl.setup.rl.unmounted", m.group(1))
						: Component.translatable("mcrl.setup.rl.unmounted_nopath");
			}
			case NOT_FOUND -> Component.translatable("mcrl.setup.rl.not_found");
		};
	}

	private static Component tool(String name, SetupStatus.ToolStatus t) {
		return Component.translatable(t.found() ? "mcrl.setup.tool.found" : "mcrl.setup.tool.missing", name,
				String.valueOf(t.path()));
	}

	private static Component yesNo(boolean b) {
		return Component.translatable(b ? "mcrl.setup.yes" : "mcrl.setup.no");
	}

	private static Component cacheLine(SetupStatus.CacheStatus c) {
		return switch (c.state()) {
			case CURRENT -> c.reason() == null
					? Component.translatable("mcrl.setup.cache.current", yesNo(c.sounds()))
					: Component.translatable("mcrl.setup.cache.current_note", yesNo(c.sounds()), c.reason());
			case UNCHECKED -> Component.translatable("mcrl.setup.cache.unchecked", yesNo(c.sounds()));
			case STALE -> Component.translatable("mcrl.setup.cache.stale", yesNo(c.sounds()));
			case MISSING -> Component.translatable("mcrl.setup.cache.missing");
			case INVALID -> Component.translatable("mcrl.setup.cache.invalid", String.valueOf(c.reason()));
		};
	}

	static final class CutLine extends AbstractWidget {
		CutLine(int x, int y, int w, int h, Component message) {
			super(x, y, w, h, message);
			active = false;
			setText(message);
		}

		void setText(Component text) {
			setText(text, null);
		}

		void setText(Component text, @Nullable Component detail) {
			Font font = Minecraft.getInstance().font;
			String s = text.getString();
			if (font.width(s) <= getWidth()) {
				setMessage(text);
				setTooltip(detail == null ? null : Tooltip.create(detail));
				return;
			}
			int n = s.length();
			while (n > 0 && font.width(s.substring(0, n) + "…") > getWidth()) {
				n--;
			}
			setMessage(Component.literal(s.substring(0, n) + "…"));
			setTooltip(Tooltip.create(detail != null ? detail : text));
		}

		@Override
		protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
			g.text(Minecraft.getInstance().font, getMessage(), getX(), getY() + (getHeight() - 8) / 2, 0xFFFFFFFF);
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
		}
	}

	static final class CommitBox extends EditBox {
		private final Consumer<CommitBox> onCommit;

		CommitBox(int x, int y, int w, int h, Component name, Consumer<CommitBox> onCommit) {
			super(Minecraft.getInstance().font, x, y, w, h, name);
			this.onCommit = onCommit;
		}

		@Override
		public void setFocused(boolean focused) {
			super.setFocused(focused);
			if (!focused) {
				onCommit.accept(this);
			}
		}

		@Nullable String path(Consumer<String> parse) {
			String v = getValue().trim();
			if (!v.isEmpty()) {
				try {
					parse.accept(v);
				} catch (InvalidPathException e) {
					setTextColor(0xFFFF5555);
					setTooltip(Tooltip.create(Component.translatable("mcrl.setup.bad_path",
							String.valueOf(e.getReason()))));
					return null;
				}
			}
			setTextColor(0xFFE0E0E0);
			setTooltip(null);
			return v;
		}

		@Override
		public boolean keyPressed(KeyEvent event) {
			if (event.isConfirmation()) {
				onCommit.accept(this);
				return true;
			}
			return super.keyPressed(event);
		}
	}
}
