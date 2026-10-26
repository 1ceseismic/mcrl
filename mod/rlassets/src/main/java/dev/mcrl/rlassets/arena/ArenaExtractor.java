package dev.mcrl.rlassets.arena;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkReader;
import dev.mcrl.rlassets.upk.UpkRef;
import dev.mcrl.rlassets.upk.UpkWorld;

public final class ArenaExtractor {
	public static final String COLLISION_GROUP = "FieldCollision_Standard";
	public static final double U2P = 0.02; // UE3 U2PScale: physics units per UU
	public static final int MESHES = 16, TRIANGLES = 8020;
	public static final double MIN_INTERIOR = 0.989;
	private static final byte[] NXS = {'N', 'X', 'S', 1, 'M', 'E', 'S', 'H'}; // cooked PhysX 2.8 mesh
	private static final float SCALE_EPS = 1e-3f;

	private ArenaExtractor() {
	}

	public record Cooked(float[] verts, int[] tris) {
		public int vertexCount() {
			return verts.length / 3;
		}

		public int triangleCount() {
			return tris.length / 3;
		}
	}

	public record CacheEntry(int mesh, float sx, float sy, float sz, int index) {
	}

	public record PerTriCache(List<CacheEntry> entries, List<byte[]> store) {
	}

	public record Placed(String actor, String mesh, int index, byte[] cmf, int hash) {
	}

	public record Arena(List<Placed> meshes, List<String> problems) {
		public List<byte[]> cmf() {
			return meshes.stream().map(Placed::cmf).toList();
		}

		public int[] hashes() {
			return meshes.stream().mapToInt(Placed::hash).toArray();
		}
	}

	public record Acceptance(int knownMask, int triangles, List<String> perMesh, List<String> problems) {
		public boolean accepted() {
			return problems.isEmpty();
		}
	}

	public static Arena extract(UpkWorld world, String pkg) throws IOException {
		UpkReader p = world.pkg(pkg);
		if (p == null) {
			throw new IOException("package " + pkg + " was not unpacked");
		}
		List<String> problems = new ArrayList<>();
		int level = levelExport(p);
		if (level == 0) {
			problems.add(pkg + " has no PersistentLevel");
			return new Arena(List.of(), problems);
		}
		PerTriCache cache = perTriCache(p.body(level));
		if (cache == null) {
			problems.add(pkg + "'s level holds no cooked per-triangle physics cache");
			return new Arena(List.of(), problems);
		}
		String levelPath = p.objectPath(level) + ".";
		List<Placed> out = new ArrayList<>();
		for (int i = 1; i <= p.exports().size(); i++) {
			if (!classOf(p, i).equals("StaticMeshActor") || !p.objectPath(i).startsWith(levelPath)) {
				continue;
			}
			UObject actor = world.obj(pkg, i);
			UObject comp = component(world, pkg, actor.props().get("StaticMeshComponent"));
			UpkRef mesh = comp == null ? null : comp.props().get("StaticMesh") instanceof UpkRef r ? r : null;
			if (mesh == null || !group(mesh.path()).equals(COLLISION_GROUP)) {
				continue;
			}
			Placed placed = place(world, p, cache, actor, comp, mesh, problems);
			if (placed != null) {
				out.add(placed);
			}
		}
		out.sort(Comparator.comparingInt(Placed::index));
		return new Arena(List.copyOf(out), problems);
	}

	private static @Nullable Placed place(UpkWorld world, UpkReader p, PerTriCache cache, UObject actor, UObject comp,
			UpkRef mesh, List<String> problems) {
		Map<String, Object> a = actor.props(), c = comp.props();
		float[] cs = vector(c.get("Scale3D"), 1f), location;
		float s = number(c.get("Scale"), 1f);
		int[] rotation;
		if (comp.path().startsWith(actor.path() + ".")) {
			if (nonZero(vector(c.get("Translation"), 0f)) || nonZero(rotator(c.get("Rotation")))) {
				problems.add(actor.path() + ": a component moved within its actor is not supported");
				return null;
			}
			float[] ds = vector(a.get("DrawScale3D"), 1f);
			s *= number(a.get("DrawScale"), 1f);
			cs = new float[] {cs[0] * ds[0], cs[1] * ds[1], cs[2] * ds[2]};
			location = vector(a.get("Location"), 0f);
			rotation = rotator(a.get("Rotation"));
		} else {
			location = vector(c.get("Translation"), 0f);
			rotation = rotator(c.get("Rotation"));
		}
		float sx = cs[0] * s, sy = cs[1] * s, sz = cs[2] * s;
		CacheEntry hit = null;
		for (CacheEntry e : cache.entries()) {
			UpkRef r = world.ref(p, e.mesh());
			if (r != null && r.path().equals(mesh.path()) && Math.abs(e.sx() - sx) < SCALE_EPS
					&& Math.abs(e.sy() - sy) < SCALE_EPS && Math.abs(e.sz() - sz) < SCALE_EPS) {
				hit = e;
				break;
			}
		}
		if (hit == null || hit.index() < 0 || hit.index() >= cache.store().size()) {
			problems.add(String.format(Locale.ROOT, "%s: no cooked mesh of %s at scale (%s, %s, %s)", actor.path(),
					mesh.path(), sx, sy, sz));
			return null;
		}
		Cooked cooked;
		try {
			cooked = cooked(cache.store().get(hit.index()));
		} catch (IllegalArgumentException e) {
			problems.add(actor.path() + ": cooked mesh " + hit.index() + " unreadable: " + e.getMessage());
			return null;
		}
		byte[] cmf = cmf(cooked, location, rotation);
		return new Placed(actor.path(), mesh.path(), hit.index(), cmf, CmfHash.of(cmf));
	}

	private static @Nullable UObject component(UpkWorld world, String pkg, @Nullable Object ref) {
		if (!(ref instanceof UpkRef r)) {
			return null;
		}
		UpkWorld.Loc loc = world.locate(r.path(), pkg);
		return loc == null ? null : world.obj(loc.pkg(), loc.index());
	}

	public static String group(String path) {
		String s = path.substring(path.indexOf(':') + 1);
		int dot = s.lastIndexOf('.');
		return dot < 0 ? "" : s.substring(0, dot);
	}

	static String classOf(UpkReader p, int i) {
		return p.className(p.exports().get(i - 1).classIndex());
	}

	static int levelExport(UpkReader p) {
		for (int i = 1; i <= p.exports().size(); i++) {
			if (classOf(p, i).equals("Level") && p.exports().get(i - 1).objectName().equals("PersistentLevel")) {
				return i;
			}
		}
		return 0;
	}

	public static @Nullable PerTriCache perTriCache(ByteBuffer body) {
		ByteBuffer b = body.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int end = b.limit();
		PerTriCache last = null;
		for (int at = 8; at + NXS.length <= end; at++) {
			if (!startsWith(b, at, NXS)) {
				continue;
			}
			int s = at - 12;
			if (s < 0 || b.getInt(at - 8) != 1) {
				continue;
			}
			int n = b.getInt(s);
			int mapAt = s - 4 - 20 * n;
			if (n <= 0 || n > 100_000 || mapAt < 0 || b.getInt(mapAt) != n) {
				continue;
			}
			List<CacheEntry> entries = new ArrayList<>(n);
			boolean ok = true;
			for (int k = 0; k < n && ok; k++) {
				int o = mapAt + 4 + 20 * k;
				CacheEntry e = new CacheEntry(b.getInt(o), b.getFloat(o + 4), b.getFloat(o + 8), b.getFloat(o + 12),
						b.getInt(o + 16));
				ok = e.mesh() != 0 && e.index() >= 0 && e.index() < n && Float.isFinite(e.sx()) && Float.isFinite(e.sy())
						&& Float.isFinite(e.sz());
				entries.add(e);
			}
			List<byte[]> store = new ArrayList<>(n);
			int o = s + 4;
			for (int k = 0; k < n && ok; k++) {
				int len = o + 8 <= end && b.getInt(o) == 1 ? b.getInt(o + 4) : -1;
				ok = len >= NXS.length && o + 8L + len <= end && startsWith(b, o + 8, NXS);
				if (ok) {
					byte[] m = new byte[len];
					b.get(o + 8, m);
					store.add(m);
					o += 8 + len;
				}
			}
			if (ok) {
				last = new PerTriCache(List.copyOf(entries), List.copyOf(store));
				at = o - 1;
			}
		}
		return last;
	}

	private static boolean startsWith(ByteBuffer b, int at, byte[] magic) {
		for (int i = 0; i < magic.length; i++) {
			if (b.get(at + i) != magic[i]) {
				return false;
			}
		}
		return true;
	}

	public static Cooked cooked(byte[] nxs) {
		ByteBuffer b = ByteBuffer.wrap(nxs).order(ByteOrder.LITTLE_ENDIAN);
		if (nxs.length < 36 || !startsWith(b, 0, NXS)) {
			throw new IllegalArgumentException("not a cooked PhysX triangle mesh");
		}
		int flags = b.getInt(12), nv = b.getInt(28), nt = b.getInt(32);
		int width = (flags & 8) != 0 ? 1 : (flags & 16) != 0 ? 2 : 4;
		if (nv <= 0 || nt <= 0 || 36L + 12L * nv + 3L * width * nt > nxs.length) {
			throw new IllegalArgumentException(nv + " vertices and " + nt + " triangles do not fit " + nxs.length + " bytes");
		}
		float[] verts = new float[3 * nv];
		for (int i = 0; i < verts.length; i++) {
			verts[i] = b.getFloat(36 + 4 * i);
		}
		int[] tris = new int[3 * nt];
		int o = 36 + 12 * nv;
		for (int i = 0; i < tris.length; i++) {
			int v = switch (width) {
				case 1 -> b.get(o + i) & 0xFF;
				case 2 -> b.getShort(o + 2 * i) & 0xFFFF;
				default -> b.getInt(o + 4 * i);
			};
			if (v < 0 || v >= nv) {
				throw new IllegalArgumentException("triangle " + i / 3 + " names vertex " + v + " of " + nv);
			}
			tris[i] = v;
		}
		return new Cooked(verts, tris);
	}

	public static byte[] cmf(Cooked c, float[] locationUU, int[] rotator) {
		float tx = (float) (locationUU[0] * U2P), ty = (float) (locationUU[1] * U2P), tz = (float) (locationUU[2] * U2P);
		double[] m = nonZero(rotator) ? rotation(rotator[0], rotator[1], rotator[2]) : null;
		int nv = c.vertexCount(), nt = c.triangleCount();
		ByteBuffer b = ByteBuffer.allocate(8 + 12 * nt + 12 * nv).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(nt).putInt(nv);
		for (int t : c.tris()) {
			b.putInt(t);
		}
		float[] v = c.verts();
		for (int i = 0; i < nv; i++) {
			float x = v[3 * i], y = v[3 * i + 1], z = v[3 * i + 2];
			if (m != null) {
				float rx = (float) (m[0] * x + m[1] * y + m[2] * z);
				float ry = (float) (m[3] * x + m[4] * y + m[5] * z);
				float rz = (float) (m[6] * x + m[7] * y + m[8] * z);
				x = rx;
				y = ry;
				z = rz;
			}
			b.putFloat(x + tx).putFloat(y + ty).putFloat(z + tz);
		}
		return b.array();
	}

	// UE3's FRotationMatrix; 65536 rotator units = 360 degrees.
	public static double[] rotation(int pitch, int yaw, int roll) {
		double p = pitch * (2 * Math.PI / 65536), y = yaw * (2 * Math.PI / 65536), r = roll * (2 * Math.PI / 65536);
		double sp = Math.sin(p), cp = Math.cos(p), sy = Math.sin(y), cy = Math.cos(y), sr = Math.sin(r), cr = Math.cos(r);
		double[][] rows = {
				{cp * cy, cp * sy, sp},
				{sr * sp * cy - cr * sy, sr * sp * sy + cr * cy, -sr * cp},
				{-(cr * sp * cy + sr * sy), cy * sr - cr * sp * sy, cr * cp}};
		double[] m = new double[9];
		for (int i = 0; i < 3; i++) {
			for (int j = 0; j < 3; j++) {
				double v = rows[j][i];
				m[3 * i + j] = Math.abs(v) < 1e-12 ? 0 : Math.abs(v - 1) < 1e-12 ? 1 : Math.abs(v + 1) < 1e-12 ? -1 : v;
			}
		}
		return m;
	}

	public static Acceptance accept(List<byte[]> cmf) {
		List<String> problems = new ArrayList<>(), perMesh = new ArrayList<>();
		int mask = 0, triangles = 0;
		if (cmf.size() != MESHES) {
			problems.add(cmf.size() + " meshes, RocketSim's SOCCAR set has " + MESHES);
		}
		for (int i = 0; i < cmf.size(); i++) {
			byte[] f = cmf.get(i);
			ByteBuffer b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN);
			int hash;
			try {
				hash = CmfHash.of(f);
			} catch (IllegalArgumentException e) {
				problems.add("mesh " + i + ": " + e.getMessage());
				continue;
			}
			int nt = b.getInt(0), nv = b.getInt(4), inward = interiorFacing(f);
			triangles += nt;
			int known = CmfHash.SOCCAR_KNOWN.indexOf(hash);
			perMesh.add(String.format(Locale.ROOT, "mesh %d: %d triangles, %d vertices, hash 0x%08X (%s), %d/%d facing in",
					i, nt, nv, hash, known < 0 ? "unknown" : "known " + known, inward, nt));
			if (known < 0) {
				problems.add(String.format(Locale.ROOT, "mesh %d: hash 0x%08X is not RocketSim's", i, hash));
			} else if ((mask & 1 << known) != 0) {
				problems.add(String.format(Locale.ROOT, "mesh %d: hash 0x%08X twice", i, hash));
			} else {
				mask |= 1 << known;
			}
			if (inward < MIN_INTERIOR * nt) {
				problems.add(String.format(Locale.ROOT, "mesh %d: %d of %d normals face the arena", i, inward, nt));
			}
		}
		if (triangles != TRIANGLES) {
			problems.add(triangles + " triangles, RocketSim's SOCCAR set has " + TRIANGLES);
		}
		return new Acceptance(mask, triangles, List.copyOf(perMesh), List.copyOf(problems));
	}

	static int interiorFacing(byte[] cmf) {
		ByteBuffer b = ByteBuffer.wrap(cmf).order(ByteOrder.LITTLE_ENDIAN);
		int nt = b.getInt(0), vAt = 8 + 12 * nt, inward = 0;
		for (int t = 0; t < nt; t++) {
			double[][] p = new double[3][];
			for (int k = 0; k < 3; k++) {
				int v = b.getInt(8 + 12 * t + 4 * k);
				p[k] = new double[] {b.getFloat(vAt + 12 * v), b.getFloat(vAt + 12 * v + 4), b.getFloat(vAt + 12 * v + 8)};
			}
			double ux = p[1][0] - p[0][0], uy = p[1][1] - p[0][1], uz = p[1][2] - p[0][2];
			double wx = p[2][0] - p[0][0], wy = p[2][1] - p[0][1], wz = p[2][2] - p[0][2];
			double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
			double mx = (p[0][0] + p[1][0] + p[2][0]) / 3, my = (p[0][1] + p[1][1] + p[2][1]) / 3,
					mz = (p[0][2] + p[1][2] + p[2][2]) / 3;
			if (nx * -mx + ny * -my + nz * (20.48 - mz) > 0) {
				inward++;
			}
		}
		return inward;
	}

	private static float[] vector(@Nullable Object v, float dflt) {
		if (v instanceof Map<?, ?> m) {
			return new float[] {number(m.get("X"), dflt), number(m.get("Y"), dflt), number(m.get("Z"), dflt)};
		}
		return new float[] {dflt, dflt, dflt};
	}

	private static int[] rotator(@Nullable Object v) {
		if (v instanceof Map<?, ?> m) {
			return new int[] {(int) number(m.get("Pitch"), 0), (int) number(m.get("Yaw"), 0), (int) number(m.get("Roll"), 0)};
		}
		return new int[3];
	}

	private static float number(@Nullable Object v, float dflt) {
		return v instanceof Number n ? n.floatValue() : dflt;
	}

	private static boolean nonZero(float[] v) {
		return v[0] != 0 || v[1] != 0 || v[2] != 0;
	}

	private static boolean nonZero(int[] v) {
		return v[0] != 0 || v[1] != 0 || v[2] != 0;
	}
}
