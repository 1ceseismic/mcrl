package dev.mcrl.client.fx.effects;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class FlareOcclusion {
	public static final float FADE_SECONDS = 0.1f;

	public interface Blocked {
		boolean between(double x0, double y0, double z0, double x1, double y1, double z1);
	}

	private static final class State {
		float visibility;
		boolean seen;
	}

	private final Map<Object, State> flares = new HashMap<>();

	public float update(Object key, boolean visible, double dt) {
		float target = visible ? 1 : 0;
		State s = flares.get(key);
		if (s == null) {
			s = new State();
			s.visibility = target;
			flares.put(key, s);
		} else {
			float step = (float) (Math.max(0, dt) / FADE_SECONDS);
			s.visibility = target > s.visibility ? Math.min(target, s.visibility + step)
					: Math.max(target, s.visibility - step);
		}
		s.seen = true;
		return s.visibility;
	}

	public void endFrame() {
		for (Iterator<State> it = flares.values().iterator(); it.hasNext();) {
			State s = it.next();
			if (!s.seen) {
				it.remove();
			} else {
				s.seen = false;
			}
		}
	}

	public int size() {
		return flares.size();
	}
}
