package dev.mcrl.client.sound;

import dev.mcrl.client.sound.ak.AkEngine;

public final class BlowoffRule {
	private double fullThrottleTime;

	public boolean step(double dt, float rawThrottle) {
		dt = Math.max(0, Math.min(dt, AkEngine.MAX_DT));
		if (rawThrottle >= 1) {
			fullThrottleTime += dt;
		} else if (fullThrottleTime > 0) {
			if (rawThrottle <= 0 && fullThrottleTime > EngineAudioProfile.BLOWOFF_THROTTLE_TIME) {
				fullThrottleTime = 0;
				return true;
			}
			fullThrottleTime = Math.max(fullThrottleTime - dt, 0);
		}
		return false;
	}
}
