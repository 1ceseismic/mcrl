package dev.mcrl.client.render.stadium;

import java.util.List;

import dev.mcrl.client.render.stadium.StadiumScene.Instance;

public final class StadiumDrawList {
	private StadiumDrawList() {
	}

	public record Passes(int[] opaque, int[] masked, int[] glass, int[] glow) {
	}

	public static Passes build(StadiumScene s, boolean farDetail, long padActiveBits) {
		List<Instance> in = s.instances();
		int n = in.size();
		int[] counts = new int[4];
		int[] pass = new int[n];
		for (int i = 0; i < n; i++) {
			Instance x = in.get(i);
			boolean drawn = (farDetail || x.tier() != StadiumScene.Tier.FAR)
					&& (x.pad() < 0 || x.pad() >= 64 || (padActiveBits >>> x.pad() & 1) != 0);
			pass[i] = drawn ? x.pass().ordinal() : -1;
			if (drawn) {
				counts[pass[i]]++;
			}
		}
		int[][] out = {new int[counts[0]], new int[counts[1]], new int[counts[2]], new int[counts[3]]};
		int[] at = new int[4];
		for (int i = 0; i < n; i++) {
			if (pass[i] >= 0) {
				out[pass[i]][at[pass[i]]++] = i;
			}
		}
		return new Passes(out[0], out[1], out[2], out[3]);
	}
}
