package dev.mcrl.rlassets.arena;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

public final class CmfHash {
	// RocketSim's MeshHashSet(SOCCAR), in its order.
	public static final List<Integer> SOCCAR_KNOWN = List.of(
			0xA160BAF9, 0x2811EEE8, 0xB81AC8B9, 0x760358D3,
			0x73AE4940, 0x918F4A4E, 0x1F8EE550, 0x255BA8C1,
			0x14B84668, 0xEC759EBF, 0x94FB0D5C, 0xDEA07102,
			0xBD4FBEA8, 0x39A47F63, 0x3D79D25D, 0xD84C7A68);

	private static final int MUELLER = 0x45D9F3B, SHIFT = 0x9E3779B9;

	private CmfHash() {
	}

	public static int knownMask(int[] hashes) {
		int mask = 0;
		for (int h : hashes) {
			int i = SOCCAR_KNOWN.indexOf(h);
			if (i >= 0) {
				mask |= 1 << i;
			}
		}
		return mask;
	}

	public static int of(byte[] cmf) {
		ByteBuffer b = ByteBuffer.wrap(cmf).order(ByteOrder.LITTLE_ENDIAN);
		if (cmf.length < 8) {
			throw new IllegalArgumentException("a .cmf file has at least 8 bytes, this one " + cmf.length);
		}
		int tris = b.getInt(0), verts = b.getInt(4);
		if (tris < 0 || verts < 0 || 8L + 12L * tris + 12L * verts != cmf.length) {
			throw new IllegalArgumentException("a .cmf file of " + tris + " triangles and " + verts + " vertices has "
					+ (8L + 12L * tris + 12L * verts) + " bytes, this one " + cmf.length);
		}
		int vertsAt = 8 + 12 * tris;
		int hash = verts + tris * verts;
		for (int t = 0; t < tris; t++) {
			for (int i = 0; i < 3; i++) {
				int v = b.getInt(8 + 12 * t + 4 * i);
				if (v < 0 || v >= verts) {
					throw new IllegalArgumentException("triangle " + t + " names vertex " + v + " of " + verts);
				}
				for (int j = 0; j < 3; j++) {
					int cur = (int) (long) b.getFloat(vertsAt + 12 * v + 4 * j); // as x86-64 casts to uint32_t
					for (int k = 0; k < 2; k++) {
						cur = ((cur >>> 16) ^ cur) * MUELLER;
					}
					cur = (cur >>> 16) ^ cur;
					hash ^= cur + SHIFT + (hash << 6) + (hash >>> 2);
				}
			}
		}
		return hash;
	}
}
