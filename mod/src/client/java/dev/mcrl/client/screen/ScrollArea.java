package dev.mcrl.client.screen;

public final class ScrollArea {
	private static final int STEP = 12;
	private static final int MIN_THUMB = 16;

	private final int top, height;
	private int bottom, offset;

	public ScrollArea(int top, int height) {
		this.top = top;
		this.height = height;
		this.bottom = top;
	}

	public void content(int bottom) {
		this.bottom = bottom;
		scrollTo(offset);
	}

	public int offset() {
		return offset;
	}

	public int maxOffset() {
		return Math.max(0, bottom - (top + height));
	}

	public void scrollBy(double notches) {
		scrollTo(offset - (int) Math.round(notches * STEP));
	}

	public void scrollTo(int offset) {
		this.offset = Math.max(0, Math.min(maxOffset(), offset));
	}

	public void reveal(int virtualY, int h) {
		int y = y(virtualY);
		if (y < top || h > height) {
			scrollTo(virtualY - top);
		} else if (y + h > top + height) {
			scrollTo(virtualY + h - (top + height));
		}
	}

	public int y(int virtualY) {
		return virtualY - offset;
	}

	public boolean visible(int virtualY, int h) {
		int y = y(virtualY);
		return y >= top && y + h <= top + height;
	}

	public int thumbHeight() {
		int content = Math.max(1, bottom - top);
		return Math.min(height, Math.max(MIN_THUMB, height * height / content));
	}

	public int thumbTop() {
		int max = maxOffset();
		return max == 0 ? top : top + (int) ((long) offset * (height - thumbHeight()) / max);
	}
}
