package dev.mcrl.client.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.screen.tabs.CameraTab;
import dev.mcrl.client.screen.tabs.CarTab;
import dev.mcrl.client.screen.tabs.CollisionsTab;
import dev.mcrl.client.screen.tabs.ControlsTab;
import dev.mcrl.client.screen.tabs.EffectsTab;
import dev.mcrl.client.screen.tabs.HudTab;
import dev.mcrl.client.screen.tabs.MatchTab;
import dev.mcrl.client.screen.tabs.OnlineTab;
import dev.mcrl.client.screen.tabs.PhysicsTab;
import dev.mcrl.client.screen.tabs.SettingsTab;
import dev.mcrl.client.screen.tabs.SetupTab;
import dev.mcrl.client.screen.tabs.SoundTab;
import dev.mcrl.client.screen.tabs.VisualsTab;

public class McrlSettingsScreen extends Screen {
	public static final int COL_W = 150, ROW_H = 24, GAP = 10;
	private static final int MIN_TAB_W = 68, MAX_TAB_W = 100, TAB_ROW_H = 22;
	private static final int TAB_ROW_Y = 22, CONTENT_GAP = 6, DONE_H = 20, MARGIN = 8;
	private static final int SCROLLBAR_W = 4, SCROLLBAR_GAP = 2, TRACK_COLOUR = 0x80000000, THUMB_COLOUR = 0xFFA0A0A0;
	private static boolean dirty;

	private final @Nullable Screen parent;
	private final List<SettingsTab> tabs = List.of(new CameraTab(), new VisualsTab(), new EffectsTab(), new HudTab(),
			new SoundTab(), new ControlsTab(), new CollisionsTab(), new PhysicsTab(), new MatchTab(), new OnlineTab(),
			new CarTab(), new SetupTab());
	private SettingsTab.Id current;
	private int contentTop = TAB_ROW_Y + TAB_ROW_H + CONTENT_GAP;
	private SettingsTab.@Nullable Id scrolled;
	private ScrollArea scroll = new ScrollArea(contentTop, 0);
	private final List<AbstractWidget> added = new ArrayList<>();
	private List<Placed> placed = List.of();

	private record Placed(AbstractWidget widget, int y) {
	}

	public McrlSettingsScreen(@Nullable Screen parent, SettingsTab.Id initial) {
		super(Component.translatable("mcrl.settings.title"));
		this.parent = parent;
		this.current = initial;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	private static int tabRows(int count, int width) {
		int room = Math.max(1, width - 2 * MARGIN);
		return count * MIN_TAB_W <= room ? 1 : Math.ceilDiv(count * MIN_TAB_W, room);
	}

	private SettingsTab shown() {
		for (SettingsTab t : tabs) {
			if (t.id() == current) {
				return t;
			}
		}
		throw new IllegalStateException("no tab " + current);
	}

	@Override
	protected void init() {
		int n = tabs.size();
		int rows = tabRows(n, width);
		int perRow = Math.ceilDiv(n, rows);
		int tabW = Math.min(MAX_TAB_W, (width - 2 * MARGIN) / perRow);
		for (int i = 0; i < n; i++) {
			SettingsTab t = tabs.get(i);
			int row = i / perRow;
			int inRow = Math.min(perRow, n - row * perRow);
			int x0 = (width - tabW * inRow) / 2;
			Button b = Button.builder(t.title(), btn -> {
				current = t.id();
				rebuildWidgets();
			}).bounds(x0 + i % perRow * tabW, TAB_ROW_Y + row * TAB_ROW_H, tabW, 20).build();
			b.active = t.id() != current;
			addRenderableWidget(b);
		}
		contentTop = TAB_ROW_Y + rows * TAB_ROW_H + CONTENT_GAP;
		addRenderableWidget(Button.builder(Component.translatable("mcrl.settings.done"), b -> onClose())
				.bounds(width / 2 - COL_W / 2, height - DONE_H - MARGIN, COL_W, DONE_H).build());
		int keep = current == scrolled ? scroll.offset() : 0;
		scrolled = current;
		added.clear();
		shown().build(this);
		List<Placed> laidOut = new ArrayList<>(added.size());
		int bottom = top();
		for (AbstractWidget w : added) {
			laidOut.add(new Placed(w, w.getY()));
			bottom = Math.max(bottom, w.getY() + w.getHeight());
		}
		placed = laidOut;
		scroll = new ScrollArea(top(), height());
		scroll.content(bottom);
		scroll.scrollTo(keep);
		place();
	}

	// hidden widgets cannot take focus
	@Override
	public boolean keyPressed(KeyEvent event) {
		for (Placed p : placed) {
			p.widget().visible = true;
		}
		try {
			return super.keyPressed(event);
		} finally {
			GuiEventListener focused = getFocused();
			for (Placed p : placed) {
				if (p.widget() == focused) {
					scroll.reveal(p.y(), p.widget().getHeight());
				}
			}
			place();
		}
	}

	private void place() {
		for (Placed p : placed) {
			p.widget().setY(scroll.y(p.y()));
			p.widget().visible = scroll.visible(p.y(), p.widget().getHeight());
		}
	}

	private int scrollbarX() {
		return Math.min(left() + width() + SCROLLBAR_GAP, width - SCROLLBAR_W);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= left() && mouseX < scrollbarX() + SCROLLBAR_W && mouseY >= top() && mouseY < top() + height()) {
			if (scroll.maxOffset() > 0) {
				scroll.scrollBy(scrollY);
				place();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void tick() {
		super.tick();
		shown().tick();
	}

	public void onRulesChanged() {
		if (current == SettingsTab.Id.COLLISIONS || current == SettingsTab.Id.PHYSICS) {
			rebuildWidgets();
		}
	}

	public int left() {
		return width / 2 - COL_W - GAP / 2;
	}

	public int top() {
		return contentTop;
	}

	public int width() {
		return 2 * COL_W + GAP;
	}

	public int height() {
		return height - DONE_H - MARGIN - contentTop - GAP;
	}

	public <T extends AbstractWidget> T add(T widget) {
		added.add(widget);
		return addRenderableWidget(widget);
	}

	public void text(int x, int y, int width, Component message) {
		add(new StringWidget(x, y, width, 20, message, font));
	}

	public void open(Function<Screen, Screen> child) {
		save();
		minecraft.gui.setScreen(child.apply(this));
	}

	public void rebuild() {
		rebuildWidgets();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title, width / 2, 8, 0xFFFFFFFF);
		if (scroll.maxOffset() > 0) {
			int x = scrollbarX();
			g.fill(x, top(), x + SCROLLBAR_W, top() + height(), TRACK_COLOUR);
			int thumbY = scroll.thumbTop();
			g.fill(x, thumbY, x + SCROLLBAR_W, thumbY + scroll.thumbHeight(), THUMB_COLOUR);
		}
	}

	@Override
	public void onClose() {
		clearFocus(); // commits a focused path box
		save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		save();
		for (SettingsTab t : tabs) {
			t.closed();
		}
	}

	public static void markDirty() {
		dirty = true;
	}

	public static void save() {
		if (dirty) {
			McrlConfig.get().save();
			dirty = false;
		}
	}
}
