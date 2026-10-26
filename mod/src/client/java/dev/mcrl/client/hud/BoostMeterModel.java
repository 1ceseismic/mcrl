package dev.mcrl.client.hud;

import static dev.mcrl.client.hud.MeterStyle.COLOR_BACKGROUND_AMBER;
import static dev.mcrl.client.hud.MeterStyle.COLOR_BOOST_MAX;
import static dev.mcrl.client.hud.MeterStyle.COLOR_BOOST_NORMAL;
import static dev.mcrl.client.hud.MeterStyle.COLOR_GLOW_AMBER;
import static dev.mcrl.client.hud.MeterStyle.COLOR_GLOW_RED;
import static dev.mcrl.client.hud.MeterStyle.COLOR_WHITE;
import static dev.mcrl.client.hud.MeterStyle.FLASH_TWEEN;
import static dev.mcrl.client.hud.MeterStyle.GLOW_CLIP_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.GLOW_FLASH_SCALE;
import static dev.mcrl.client.hud.MeterStyle.GLOW_TINT_TWEEN;
import static dev.mcrl.client.hud.MeterStyle.LABEL_BLINK;
import static dev.mcrl.client.hud.MeterStyle.LABEL_COLOR;
import static dev.mcrl.client.hud.MeterStyle.LABEL_FADE;
import static dev.mcrl.client.hud.MeterStyle.MAX_THRESHOLD;
import static dev.mcrl.client.hud.MeterStyle.TEAM_DIM_ALPHA;
import static dev.mcrl.client.hud.MeterStyle.TEXT_TEAM;
import static dev.mcrl.client.hud.MeterStyle.TEXT_WHITE;
import static dev.mcrl.client.hud.MeterStyle.TOTAL_FRAMES;
import static dev.mcrl.client.hud.MeterStyle.VALUE_TWEEN;
import static dev.mcrl.client.hud.MeterStyle.WEDGE_DEG_PER_FRAME;

import java.util.function.DoubleUnaryOperator;

import org.jspecify.annotations.Nullable;

final class BoostMeterModel {
	private static final DoubleUnaryOperator QUAD = Ease::quadOut, STRONG = Ease::strongOut;

	private boolean started;
	private MeterTeam team = MeterTeam.NONE;
	private int lastTarget, display;
	private boolean max;
	private boolean valueTweening;
	private int valueFrom, valueTo;
	private double valueStart;
	private int textColor;
	private final CtProp fill = new CtProp(), tint = new CtProp(), glow = new CtProp(), text = new CtProp(),
			label = new CtProp(), background = new CtProp();
	private final Scalar glowScale = new Scalar(), labelAlpha = new Scalar();

	BoostMeterModel() {
		reset();
	}

	void reset() {
		started = false;
		team = MeterTeam.NONE;
		lastTarget = 0;
		display = 0;
		max = false;
		valueTweening = false;
		textColor = TEXT_TEAM;
		fill.force(ColorTransform.IDENTITY);
		tint.force(ColorTransform.IDENTITY);
		glow.force(ColorTransform.IDENTITY.withAlpha(GLOW_CLIP_ALPHA));
		text.force(ColorTransform.IDENTITY);
		label.force(ColorTransform.IDENTITY);
		background.force(ColorTransform.IDENTITY);
		glowScale.force(1);
		labelAlpha.force(0);
	}

	void update(int target, MeterTeam team, double now) {
		target = Math.max(0, Math.min(100, target));
		if (!started) {
			started = true;
			this.team = team;
			teamColorChanged();
			boostChanged(target, false, now);
			return;
		}
		if (team != this.team) {
			this.team = team;
			teamColorChanged();
		}
		if (target != lastTarget) {
			boostChanged(target, true, now);
		}
		if (valueTweening) {
			double x = (now - valueStart) / VALUE_TWEEN;
			if (x >= 1) {
				display = valueTo;
				valueTweening = false;
			} else {
				display = (int) (valueFrom + (valueTo - valueFrom) * Ease.quadOut(x));
			}
			updateBoost(now);
		}
	}

	String text() {
		return Integer.toString(display);
	}

	private int frame() {
		return Math.max((int) (display / 100.0 * TOTAL_FRAMES), 1) - 1;
	}

	double wedgeDeg() {
		return WEDGE_DEG_PER_FRAME * frame();
	}

	boolean full() {
		return frame() >= MeterStyle.FULL_FRAME;
	}

	ColorTransform fill(double now) {
		return fill.get(now);
	}

	ColorTransform tint(double now) {
		return tint.get(now);
	}

	ColorTransform glow(double now) {
		return glow.get(now);
	}

	ColorTransform background() {
		return background.value;
	}

	int textArgb(double now) {
		return text.get(now).apply(0xFF000000 | textColor);
	}

	int labelArgb(double now) {
		int c = label.get(now).apply(0xFF000000 | LABEL_COLOR);
		int a = (int) Math.round((c >>> 24) * labelAlpha(now));
		return a << 24 | c & 0xFFFFFF;
	}

	private double labelAlpha(double now) {
		return labelAlpha.get(now);
	}

	double glowScale(double now) {
		return glowScale.get(now);
	}

	private void boostChanged(int value, boolean tween, double now) {
		if (tween) {
			valueTweening = true;
			valueFrom = display;
			valueTo = value;
			valueStart = now;
		} else {
			valueTweening = false;
			display = value;
			updateBoost(now);
		}
		if (value > lastTarget) {
			fill.kill(now);
			tint.kill(now);
			glowScale.kill(now);
			if (tween) {
				fill.set(COLOR_WHITE, now);
				tint.set(COLOR_WHITE, now);
				fill.tween(ColorTransform.IDENTITY, FLASH_TWEEN, STRONG, now);
				ColorTransform teamCt = team.transform();
				tint.tween(teamCt != null ? teamCt : COLOR_BOOST_MAX, FLASH_TWEEN, STRONG, now);
				glowScale.force(GLOW_FLASH_SCALE);
				glowScale.tween(1, FLASH_TWEEN, STRONG, false, now);
			}
		}
		lastTarget = value;
	}

	private void updateBoost(double now) {
		ColorTransform teamCt = team.transform();
		if (display > MAX_THRESHOLD) {
			if (!max) {
				textColor = TEXT_WHITE;
				if (teamCt != null) {
					glow.tween(glow.get(now).withAlpha(1), GLOW_TINT_TWEEN, QUAD, now);
				} else {
					glow.tween(COLOR_GLOW_RED, GLOW_TINT_TWEEN, QUAD, now);
					text.set(COLOR_BOOST_MAX, now);
					tint.set(COLOR_BOOST_MAX, now);
					label.set(COLOR_BOOST_MAX, now);
				}
				labelAlpha.force(0);
				labelAlpha.tween(1, LABEL_BLINK, QUAD, true, now);
				max = true;
			}
		} else if (max) {
			if (teamCt != null) {
				textColor = TEXT_TEAM;
				glow.tween(glow.get(now).withAlpha(TEAM_DIM_ALPHA), GLOW_TINT_TWEEN, QUAD, now);
			} else {
				textColor = TEXT_WHITE;
				glow.tween(COLOR_GLOW_AMBER, GLOW_TINT_TWEEN, QUAD, now);
				text.set(COLOR_BOOST_NORMAL, now);
				tint.set(COLOR_BOOST_NORMAL, now);
				label.set(COLOR_BOOST_NORMAL, now);
			}
			labelAlpha.tween(0, LABEL_FADE, QUAD, false, now);
			max = false;
		}
	}

	private void teamColorChanged() {
		ColorTransform teamCt = team.transform();
		if (teamCt != null) {
			textColor = max ? TEXT_WHITE : TEXT_TEAM;
			text.force(teamCt);
			tint.force(teamCt);
			label.force(teamCt);
			glow.force(teamCt.withAlpha(max ? 1 : TEAM_DIM_ALPHA));
			background.force(ColorTransform.IDENTITY);
		} else {
			textColor = TEXT_WHITE;
			ColorTransform ct = max ? COLOR_BOOST_MAX : COLOR_BOOST_NORMAL;
			text.force(ct);
			tint.force(ct);
			label.force(ct);
			glow.force(max ? COLOR_GLOW_RED : COLOR_GLOW_AMBER);
			background.force(COLOR_BACKGROUND_AMBER);
		}
	}

	private static final class CtProp {
		ColorTransform value = ColorTransform.IDENTITY;
		private @Nullable ColorTransform from, to;
		private double start, duration;
		private DoubleUnaryOperator ease = QUAD;

		ColorTransform get(double now) {
			if (to == null || from == null) {
				return value;
			}
			double x = (now - start) / duration;
			return x >= 1 ? to : ColorTransform.lerp(from, to, ease.applyAsDouble(x));
		}

		void set(ColorTransform v, double now) {
			if (to != null && now < start + duration) {
				return;
			}
			force(v);
		}

		void force(ColorTransform v) {
			value = v;
			from = null;
			to = null;
		}

		void kill(double now) {
			force(get(now));
		}

		void tween(ColorTransform target, double duration, DoubleUnaryOperator ease, double now) {
			from = get(now);
			to = target;
			start = now;
			this.duration = duration;
			this.ease = ease;
		}
	}

	private static final class Scalar {
		private double value, from, to, start, duration;
		private boolean tweening, yoyo;
		private DoubleUnaryOperator ease = QUAD;

		double get(double now) {
			if (!tweening) {
				return value;
			}
			double x = Math.max(0, now - start) / duration;
			if (yoyo) {
				double cycle = Math.floor(x);
				x -= cycle;
				if (cycle % 2 == 1) {
					x = 1 - x;
				}
			} else if (x >= 1) {
				return to;
			}
			return from + (to - from) * ease.applyAsDouble(x);
		}

		void force(double v) {
			value = v;
			tweening = false;
		}

		void kill(double now) {
			force(get(now));
		}

		void tween(double target, double duration, DoubleUnaryOperator ease, boolean yoyo, double now) {
			from = get(now);
			to = target;
			start = now;
			this.duration = duration;
			this.ease = ease;
			this.yoyo = yoyo;
			tweening = true;
		}
	}
}
