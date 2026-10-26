package dev.mcrl.client.hud;

final class MeterStyle {
	private MeterStyle() {
	}

	// RL's HUD movie: content px on a 1120x720 stage
	static final ColorTransform COLOR_WHITE = new ColorTransform(1, 1, 1, 1, 255, 255, 255, 0);
	static final ColorTransform COLOR_BOOST_NORMAL = new ColorTransform(1, 0.38, 0.51, 1, 255, 127, 0, 0);
	static final ColorTransform COLOR_BOOST_MAX = new ColorTransform(0, 0.6, 0.49, 1, 255, 64, 64, 0);
	static final ColorTransform COLOR_GLOW_AMBER = new ColorTransform(0, 0, 0, 0.25, 255, 147, 67, 0);
	static final ColorTransform COLOR_GLOW_RED = new ColorTransform(0, 0, 0, 0.75, 255, 64, 64, 0);
	static final ColorTransform COLOR_BACKGROUND_AMBER = new ColorTransform(1, 0.43, 0, 1, 0, 0, 0, 0);

	static final int TEAM_BLUE = 0x002EBF;
	static final int TEAM_ORANGE = 0xB34000;
	static final double TEAM_DIM_ALPHA = 0.5;

	static final int TEXT_WHITE = 0xFFFFFF;
	static final int TEXT_TEAM = 0x666666;
	static final int LABEL_COLOR = 0x666666;

	static final double GLOW_CLIP_ALPHA = 0.5;
	static final int MAX_THRESHOLD = 80;
	static final double VALUE_TWEEN = 0.2;
	static final double FLASH_TWEEN = 0.8;
	static final double GLOW_TINT_TWEEN = 0.3;
	static final double LABEL_BLINK = 0.2;
	static final double LABEL_FADE = 0.2;
	static final double GLOW_FLASH_SCALE = 2;
	static final int TOTAL_FRAMES = 101;
	static final double WEDGE_DEG_PER_FRAME = 2.25;
	static final int FULL_FRAME = 100;

	static final double STAGE_WIDTH = 1120;
	static final double STAGE_HEIGHT = 720;
	static final double METER_INSET = 110;
	static final double BALL_CAM_X = 50, BALL_CAM_Y = 672;

	static final double ROT_X_DEG = -2;
	static final double ROT_Y_DEG = 15;
	static final double FOCAL_LENGTH = 500;
	static final double PROJECTION_OFFSET_X = -50, PROJECTION_OFFSET_Y = -100;
	static final double BARS_Z = -20;
	static final double TEXT_Z = -20;
	static final double LABEL_Z = 0;

	static final double RING_SIZE = 200;
	static final double GLOW_SIZE = 180;
	static final double WEDGE_RADIUS = 100;
	static final double TEXT_BOX_CENTRE_X = 0, TEXT_BOX_CENTRE_Y = -0.9;
	static final double LABEL_TOP = 16;
	static final double NUMBER_SIZE = 54;
	static final double LABEL_SIZE = 16;
	static final double CAP_RATIO = 0.6, MC_GLYPH_HEIGHT = 7; // mcrl's: Flash size to MC font

	// measured on RL's meter textures, for the procedural meter
	static final double RING_INNER = 60, RING_OUTER = 89;
	static final double[] FILL_SECTOR_ALPHA = { 0.18, 0.20, 0.24, 0.28, 0.30, 0.34, 0.45, 0.48, 0.53, 0.56, 0.58,
			0.62 };
	static final int FILL_RGB_FROM = 0xF39A3F, FILL_RGB_TO = 0xFCCF5B;
	static final double FILL_DEG_FROM = 10, FILL_DEG_TO = 170;
	static final int TINT_RGB = 0xD7D7D7;
	static final double TINT_ALPHA = 0.71, TINT_DEG_FROM = 180, TINT_DEG_TO = 225;
	static final int BG_RGB = 0x020202;
	static final double BG_ALPHA = 0.75, BG_FULL_TO = 70, BG_ZERO_AT = 88;
	static final double BG_TINT_BAND_ALPHA = 0.66;
	static final double GLOW_RADIUS = 90;
	static final int RING_TEXELS = 256, GLOW_TEXELS = 64;
	static final double TEXT_GUTTER = 2;

	// RL's ball cam indicator movie
	static final int BALL_CAM_RGB = 0xFF7752;
	static final double BALL_CAM_SIZE = 32, HINT_SIZE = 16;
	static final double BALL_CAM_TEXT_X = 12;
	static final double BALL_CAM_TEXT_MIDDLE = 3.2;
	static final double HINT_TOP = 12;
	static final double SHOW_TWEEN = 0.2, HIDE_TWEEN = 0.3;
	static final double HIDE_SLIDE = 20;
	static final double DOT_FPS = 30;
	static final double[] DOT_ALPHA = { 1, 0.988, 0.961, 0.910, 0.840, 0.750, 0.641, 0.512, 0.359, 0.191, 0, 0.359,
			0.641, 0.840, 0.961, 1 };
	static final double DOT_SIZE = 32;
	static final double GLOW_SCALE = 1.953, GLOW_ORIGIN_X = -36, GLOW_ORIGIN_Y = -32, FADE_W = 256, FADE_H = 64;
	static final ColorTransform GLOW_TRANSFORM = new ColorTransform(0, 0, 0, 0.5, 255, 67, 67, 0);
	// mcrl's own values, not RL's
	static final int DOT_RGB = 0xFF4343;
	static final double DOT_SOLID_R = 10, DOT_EDGE = 6;
	static final double STEP_UP_SHOW = 3;
	static final double STEP_UP_FADE = 0.5;
	static final int STEP_UP_ICON = 12;
}
