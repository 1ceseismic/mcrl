package dev.mcrl.client.fx.particle;

final class ParticleBuffer {
	static final int HARD_CAP = 2000;

	static final int PX = 0, PY = 1, PZ = 2,
			BVX = 3, BVY = 4, BVZ = 5,
			VX = 6, VY = 7, VZ = 8,
			SX = 9, SY = 10, SZ = 11,
			BSX = 12, BSY = 13, BSZ = 14,
			R = 15, G = 16, B = 17, A = 18,
			BR = 19, BG = 20, BB = 21, BA = 22,
			AGE = 23, LIFE = 24,
			ROT = 25, ROT_RATE = 26,
			SUB = 27,
			D0 = 28, D1 = 29, D2 = 30, D3 = 31,
			COLLISIONS = 32,
			AX = 33, AY = 34, AZ = 35,
			MRX = 36, MRY = 37, MRZ = 38,
			MRRX = 39, MRRY = 40, MRRZ = 41,
			OOX = 42, OOY = 43, OOZ = 44,
			ORX = 45, ORY = 46, ORZ = 47,
			ORRX = 48, ORRY = 49, ORRZ = 50,
			CAM = 51,
			DMX = 52, DMY = 53, DMZ = 54,
			MAXC = 55,
			TRAIL = 56;
	static final int COLUMNS = 57;
	static final float FROZEN = -1, HALTED = -2;

	final float[][] f = new float[COLUMNS][];
	private int size;

	ParticleBuffer() {
		for (int c = 0; c < COLUMNS; c++) {
			f[c] = new float[16];
		}
	}

	int size() {
		return size;
	}

	int add() {
		if (size >= HARD_CAP) {
			return -1;
		}
		if (size == f[0].length) {
			int n = Math.min(HARD_CAP, size * 2);
			for (int c = 0; c < COLUMNS; c++) {
				f[c] = java.util.Arrays.copyOf(f[c], n);
			}
		}
		int i = size++;
		for (int c = 0; c < COLUMNS; c++) {
			f[c][i] = 0;
		}
		return i;
	}

	void markDead(int i) {
		f[LIFE][i] = -1;
	}

	void removeDead() {
		int w = 0;
		for (int i = 0; i < size; i++) {
			if (f[LIFE][i] < 0) {
				continue;
			}
			if (w != i) {
				for (int c = 0; c < COLUMNS; c++) {
					f[c][w] = f[c][i];
				}
			}
			w++;
		}
		size = w;
	}

	void clear() {
		size = 0;
	}

	void shift(float dx, float dy, float dz) {
		for (int i = 0; i < size; i++) {
			f[PX][i] += dx;
			f[PY][i] += dy;
			f[PZ][i] += dz;
		}
	}
}
