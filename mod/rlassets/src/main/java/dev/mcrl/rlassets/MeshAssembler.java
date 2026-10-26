package dev.mcrl.rlassets;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntPredicate;
import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.bodies.BodySpec;

public final class MeshAssembler {
	private static final double UU_PER_BLOCK = 50;
	public static final int CHASSIS_MATERIAL = 0;
	public static final int SHELL_MATERIAL = 1;
	public static final String CHASSIS_MATERIAL_NAME = "OctaneChassis_MIC";
	public static final String SHELL_MATERIAL_NAME = "MIC_Body_Octane";
	// WHEEL_Star_SM's export bounds, only checked.
	public static final double WHEEL_MESH_RADIUS = 16.313;
	public static final double WHEEL_HALF_WIDTH = 7.264;
	public static final double BALL_RADIUS = 91.25; // RocketSim BALL_COLLISION_RADIUS_SOCCAR
	public static final double MAX_BONE_OFFSET_UU = 1.0;
	public static final double MAX_BODY_BONE_OFFSET_UU = 16.0;
	public static final double BALL_RADIUS_TOLERANCE = 0.05;
	public static final double MIN_OUTWARD_FRACTION = 0.99;
	public static final List<String> WHEELS = List.of("FL", "FR", "BL", "BR");
	public static final List<String> FRONT_WHEELS = List.of("FL", "FR");
	public static final List<String> SOCKETS = List.of("RocketBoost", "BoostEmitterLeft", "BoostEmitterRight");
	private static final double SMOOTH_COS = Math.cos(Math.toRadians(30));
	// RocketSim 2.2.1 Octane, UU: right wheel connections, suspension rest.
	private static final double[] FRONT_CONNECTION = {51.25, 25.90, 20.755}, BACK_CONNECTION = {-33.75, 29.50, 20.755};
	private static final double FRONT_SUSPENSION_REST = 38.755, BACK_SUSPENSION_REST = 37.055;
	private static final double MAX_SUSPENSION_TRAVEL = 12;

	private MeshAssembler() {
	}

	public static String discBone(String wheel) {
		return wheel + OctaneVisual.DISC_JOINT_SUFFIX;
	}

	public static String pivotBone(String wheel) {
		return wheel + OctaneVisual.PIVOT_JOINT_SUFFIX;
	}

	public record Placement(double[] scale, double[] centre, boolean mirrorY, double[] offset) {
		public static final Placement IDENTITY = new Placement(new double[] {1, 1, 1}, new double[3], false, new double[3]);

		public static Placement translate(double[] t) {
			return new Placement(new double[] {1, 1, 1}, new double[3], false, t.clone());
		}

		public static Placement uniform(double scale, double[] centre, boolean mirrorY, double[] offset) {
			return new Placement(new double[] {scale, scale, scale}, centre, mirrorY, offset);
		}

		double[] apply(double x, double y, double z) {
			double qx = scale[0] * (x - centre[0]), qy = scale[1] * (y - centre[1]), qz = scale[2] * (z - centre[2]);
			if (mirrorY) {
				qy = -qy;
			}
			return new double[] {qx + offset[0], qy + offset[1], qz + offset[2]};
		}
	}

	public record BodyFit(double[] translation, Map<String, double[]> residuals, double maxResidual, double rmsResidual) {
	}

	public record Body(MeshData shell, MeshData chassis, double tz, Map<String, double[]> discs, Map<String, double[]> pivots,
			@Nullable WheelRig rig, Map<String, double[]> boneOffsets, @Nullable BodyFit lsqFit, int shellMaterial,
			int chassisMaterial, Map<String, float[]> sockets, List<String> problems, List<String> warnings) {
	}

	public record Wheels(Map<String, MeshData> meshes, int components, int tyreTriangles, int rimTriangles, double tyreMaxRadial,
			double measuredRadius, double measuredHalfWidth, List<String> warnings) {
	}

	public record Ball(MeshData mesh, double[] centre, double fittedRadius, double scale, double outwardFraction,
			List<String> problems) {
	}

	// umodel mirrors Unreal's y: PSK y points left.
	public static float[] toMc(double[] p) {
		return new float[] {(float) (p[0] / UU_PER_BLOCK), (float) (p[2] / UU_PER_BLOCK), (float) (-p[1] / UU_PER_BLOCK)};
	}

	public static Map<String, double[]> simWheelCentres() {
		Map<String, double[]> out = new LinkedHashMap<>();
		for (String k : WHEELS) {
			boolean front = k.charAt(0) == 'F', left = k.charAt(1) == 'L';
			double[] c = front ? FRONT_CONNECTION : BACK_CONNECTION;
			double rest = front ? FRONT_SUSPENSION_REST : BACK_SUSPENSION_REST;
			out.put(k, new double[] {c[0], left ? c[1] : -c[1], c[2] - (rest - MAX_SUSPENSION_TRAVEL)});
		}
		return out;
	}

	public static Map<String, double[]> presetRest(BodySpec spec) {
		Map<String, double[]> out = new LinkedHashMap<>();
		for (String k : WHEELS) {
			double[] u = spec.presetRest(k.charAt(0) == 'F', k.charAt(1) == 'L');
			out.put(k, new double[] {u[0], -u[1], u[2]});
		}
		return out;
	}

	public static @Nullable BodyFit fitBody(Map<String, double[]> bones) {
		Map<String, double[]> sim = simWheelCentres();
		double[] t = new double[3];
		for (String k : WHEELS) {
			double[] b = bones.get(k);
			if (b == null) {
				return null;
			}
			for (int i = 0; i < 3; i++) {
				t[i] += (sim.get(k)[i] - b[i]) / WHEELS.size();
			}
		}
		Map<String, double[]> residuals = new LinkedHashMap<>();
		double max = 0, sq = 0;
		for (String k : WHEELS) {
			double[] b = bones.get(k), s = sim.get(k);
			double[] r = {b[0] + t[0] - s[0], b[1] + t[1] - s[1], b[2] + t[2] - s[2]};
			residuals.put(k, r);
			double len = Math.sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2]);
			max = Math.max(max, len);
			sq += len * len;
		}
		return new BodyFit(t, residuals, max, Math.sqrt(sq / WHEELS.size()));
	}

	public static Body body(PskMesh m, PropsReader.Props props, BodySpec spec) {
		List<String> problems = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		boolean octane = spec == BodySpec.OCTANE;
		int shellMat = m.materials().indexOf(spec.shellMaterial()), chassisMat = m.materials().indexOf(spec.chassisMaterial());
		if (m.materials().size() < 2) {
			problems.add("body mesh has " + m.materials().size() + " materials, expected 2 (chassis, shell)");
		}
		if (!octane && (shellMat < 0 || chassisMat < 0 || shellMat == chassisMat)) {
			problems.add("body materials are " + m.materials() + ", expected " + spec.shellMaterial() + " and "
					+ spec.chassisMaterial());
		} else if (shellMat < 0 || chassisMat < 0 || shellMat == chassisMat) {
			if (m.materials().size() >= 2) {
				warnings.add("body materials are " + m.materials() + ", expected " + CHASSIS_MATERIAL_NAME + " and "
						+ SHELL_MATERIAL_NAME + ": using index " + SHELL_MATERIAL + " for body_shell and " + CHASSIS_MATERIAL
						+ " for chassis");
			}
			shellMat = SHELL_MATERIAL;
			chassisMat = CHASSIS_MATERIAL;
		}
		Map<String, double[]> discs = new LinkedHashMap<>(), pivots = new LinkedHashMap<>();
		for (String k : WHEELS) {
			int i = m.boneIndex(discBone(k));
			if (i < 0) {
				problems.add("wheel joint " + discBone(k) + " not found in the body mesh");
			} else {
				discs.put(k, m.boneBindPosition(i));
			}
		}
		for (String k : FRONT_WHEELS) {
			int i = m.boneIndex(pivotBone(k));
			if (i < 0) {
				problems.add("steer joint " + pivotBone(k) + " not found in the body mesh");
			} else {
				pivots.put(k, m.boneBindPosition(i));
			}
		}
		Map<String, double[]> preset = presetRest(spec), boneOffsets = new LinkedHashMap<>();
		double limit = octane ? MAX_BONE_OFFSET_UU : MAX_BODY_BONE_OFFSET_UU;
		discs.forEach((k, d) -> {
			double[] p = preset.get(k);
			double[] o = {d[0] - p[0], d[1] - p[1], d[2] - p[2]};
			boneOffsets.put(k, o);
			double len = Math.sqrt(o[0] * o[0] + o[1] * o[1] + o[2] * o[2]);
			if (!(len <= limit)) {
				problems.add(String.format(Locale.ROOT,
						"%s is %.3f UU from the preset rest position (%.2f, %.2f, %.2f) (limit %.1f UU: an axis, mirror or "
								+ "bone composition error?)", discBone(k), len, p[0], p[1], p[2], limit));
			}
			if (!octane && (Math.signum(d[0]) != Math.signum(p[0]) || Math.signum(d[1]) != Math.signum(p[1]))) {
				problems.add(String.format(Locale.ROOT, "%s at (%.2f, %.2f) is not on the side of its preset rest (%.2f, "
						+ "%.2f): an axis or mirror error?", discBone(k), d[0], d[1], p[0], p[1]));
			}
		});
		if (!octane) {
			for (String[] axle : new String[][] {{"FL", "BL"}, {"FR", "BR"}}) {
				double[] f = discs.get(axle[0]), b = discs.get(axle[1]);
				if (f != null && b != null && !(f[0] > b[0])) {
					problems.add(String.format(Locale.ROOT, "%s (x %.2f) is not ahead of %s (x %.2f): a swapped axle?",
							discBone(axle[0]), f[0], discBone(axle[1]), b[0]));
				}
			}
			for (String[] pair : new String[][] {{"FL", "FR"}, {"BL", "BR"}}) {
				double[] l = discs.get(pair[0]), r = discs.get(pair[1]);
				if (l != null && r != null && !(Math.abs(l[0] - r[0]) <= MAX_BONE_OFFSET_UU
						&& Math.abs(l[1] + r[1]) <= MAX_BONE_OFFSET_UU && Math.abs(l[2] - r[2]) <= MAX_BONE_OFFSET_UU)) {
					problems.add(String.format(Locale.ROOT, "%s (%.2f, %.2f, %.2f) and %s (%.2f, %.2f, %.2f) are not "
							+ "mirrored within %.1f UU", discBone(pair[0]), l[0], l[1], l[2], discBone(pair[1]), r[0], r[1], r[2],
							MAX_BONE_OFFSET_UU));
				}
			}
		}
		double tz = discs.containsKey("FR") && discs.containsKey("BR")
				? spec.presetSuspensionOffsetZ(discs.get("FR")[2], discs.get("BR")[2]) : 0;
		double[] t = {spec.xOffset(), 0, tz};
		Placement place = Placement.translate(t);
		MeshData shell = mesh(m, sameMaterial(m, shellMat), place);
		MeshData chassis = mesh(m, sameMaterial(m, chassisMat), place);
		WheelRig rig = null;
		if (discs.size() == WHEELS.size() && pivots.size() == FRONT_WHEELS.size()) {
			float[][] d = new float[4][], p = new float[4][];
			double[] restZ = new double[4];
			for (int i = 0; i < 4; i++) {
				String k = WheelRig.ORDER.get(i);
				d[i] = toMc(plus(discs.get(k), t));
				p[i] = pivots.containsKey(k) ? toMc(plus(pivots.get(k), t)) : null;
				restZ[i] = preset.get(k)[2];
			}
			rig = new WheelRig(d, p, restZ, tz, spec.travelMin(), spec.travelMax(), OctaneVisual.WHEEL_BLEND_RATE);
		}
		Map<String, float[]> sockets = new LinkedHashMap<>();
		for (String name : SOCKETS) {
			PropsReader.Socket s = props.sockets().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
			if (s == null) {
				warnings.add("socket " + name + " not found in the body's props");
				continue;
			}
			int bi = m.boneIndex(s.bone());
			double[] base = new double[3];
			double[] rot = {0, 0, 0, 1};
			if (bi >= 0) {
				base = m.boneBindPosition(bi);
				rot = m.boneBindRotation(bi);
			} else {
				warnings.add("socket " + name + " bone " + s.bone() + " not found, placed relative to the origin");
			}
			double[] off = PskMesh.rotate(rot, new double[] {s.x(), -s.y(), s.z()});
			sockets.put(name, toMc(plus(plus(base, off), t)));
		}
		return new Body(shell, chassis, tz, discs, pivots, rig, boneOffsets, octane ? fitBody(discs) : null, shellMat,
				chassisMat, sockets, problems, warnings);
	}

	private static IntPredicate sameMaterial(PskMesh m, int slot) {
		int[] mat = m.faceMat();
		List<String> names = m.materials();
		if (slot < 0 || slot >= names.size()) {
			return f -> mat[f] == slot;
		}
		String name = names.get(slot);
		return f -> mat[f] == slot || mat[f] >= 0 && mat[f] < names.size() && names.get(mat[f]).equals(name);
	}

	public static double maskFraction(MeshData m, TextureBaker.Texels mask) {
		float[] v = m.vertices();
		int[] ix = m.indices();
		int n = ix.length / 3, hit = 0;
		for (int t = 0; t < n; t++) {
			double u = 0, w = 0;
			for (int k = 0; k < 3; k++) {
				u += v[MeshData.STRIDE * ix[3 * t + k] + 6] / 3.0;
				w += v[MeshData.STRIDE * ix[3 * t + k] + 7] / 3.0;
			}
			int x = Math.min(mask.width() - 1, (int) Math.floor((u - Math.floor(u)) * mask.width()));
			int y = Math.min(mask.height() - 1, (int) Math.floor((w - Math.floor(w)) * mask.height()));
			if ((mask.argb()[y * mask.width() + x] >> 16 & 0xFF) >= 128) {
				hit++;
			}
		}
		return n == 0 ? Double.NaN : (double) hit / n;
	}

	public static Wheels wheels(PskMesh m) {
		return wheels(m, BodySpec.OCTANE);
	}

	public static Wheels wheels(PskMesh m, BodySpec spec) {
		int nf = m.faceCount();
		float[] pts = m.points();
		int[] comp = components(m);
		Map<Integer, double[]> stats = new LinkedHashMap<>();
		double radius = 0, halfWidth = 0;
		for (int f = 0; f < nf; f++) {
			double[] st = stats.computeIfAbsent(comp[f], k -> new double[2]);
			st[1]++;
			for (int k = 0; k < 3; k++) {
				int p = m.wedgePoint()[m.faces()[3 * f + k]];
				double r = Math.hypot(pts[3 * p], pts[3 * p + 2]);
				st[0] = Math.max(st[0], r);
				radius = Math.max(radius, r);
				halfWidth = Math.max(halfWidth, Math.abs(pts[3 * p + 1]));
			}
		}
		int tyre = -1;
		double tyreR = -1;
		for (Map.Entry<Integer, double[]> e : stats.entrySet()) {
			if (e.getValue()[0] > tyreR) {
				tyreR = e.getValue()[0];
				tyre = e.getKey();
			}
		}
		int tyreRoot = tyre;
		int tyreTris = tyre < 0 ? 0 : (int) stats.get(tyre)[1];
		List<String> warnings = new ArrayList<>();
		if (Math.abs(radius / WHEEL_MESH_RADIUS - 1) > 0.01) {
			warnings.add(String.format(Locale.ROOT, "wheel radius %.3f UU differs from the expected %.3f", radius, WHEEL_MESH_RADIUS));
		}
		if (Math.abs(halfWidth - WHEEL_HALF_WIDTH) > 0.05) {
			warnings.add(String.format(Locale.ROOT, "wheel half width %.3f UU differs from the expected %.3f", halfWidth,
					WHEEL_HALF_WIDTH));
		}
		Map<String, MeshData> meshes = new LinkedHashMap<>();
		for (String part : new String[] {"rim", "tyre"}) {
			IntPredicate keep = part.equals("tyre") ? f -> comp[f] == tyreRoot : f -> comp[f] != tyreRoot;
			for (boolean front : new boolean[] {true, false}) {
				String axle = front ? "front" : "back";
				meshes.put("wheel_" + part + "_" + axle + "_right", mesh(m, keep, wheelPlacement(spec, front, false)));
				meshes.put("wheel_" + part + "_" + axle + "_left", mesh(m, keep, wheelPlacement(spec, front, true)));
			}
		}
		return new Wheels(meshes, stats.size(), tyreTris, nf - tyreTris, tyreR, radius, halfWidth, warnings);
	}

	public static Placement wheelPlacement(boolean front, boolean left) {
		return wheelPlacement(BodySpec.OCTANE, front, left);
	}

	public static Placement wheelPlacement(BodySpec spec, boolean front, boolean left) {
		double rs = spec.radialScale(front), ws = spec.axialScale(front), side = spec.meshOffsetSide(front);
		return new Placement(new double[] {rs, ws, rs}, new double[3], left, new double[] {0, left ? side : -side, 0});
	}

	private static double[] plus(double[] a, double[] b) {
		return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
	}

	static int[] components(PskMesh m) {
		int[] parent = new int[m.pointCount()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
		int[] wp = m.wedgePoint(), faces = m.faces();
		for (int f = 0; f < m.faceCount(); f++) {
			int a = find(parent, wp[faces[3 * f]]);
			for (int k = 1; k < 3; k++) {
				int b = find(parent, wp[faces[3 * f + k]]);
				if (a != b) {
					parent[b] = a;
				}
			}
		}
		int[] out = new int[m.faceCount()];
		for (int f = 0; f < out.length; f++) {
			out[f] = find(parent, wp[faces[3 * f]]);
		}
		return out;
	}

	private static int find(int[] parent, int a) {
		while (parent[a] != a) {
			parent[a] = parent[parent[a]];
			a = parent[a];
		}
		return a;
	}

	public static Ball ball(PskMesh m) {
		double[] fit = fitSphere(m.points());
		double[] c = {fit[0], fit[1], fit[2]};
		double r = fit[3];
		float[] pts = m.points();
		int[] wp = m.wedgePoint(), faces = m.faces();
		int out = 0, total = 0;
		for (int f = 0; f < m.faceCount(); f++) {
			int a = wp[faces[3 * f + 1]], b = wp[faces[3 * f]], d = wp[faces[3 * f + 2]];
			double[] pa = point(pts, a), pb = point(pts, b), pd = point(pts, d);
			double[] n = cross(sub(pb, pa), sub(pd, pa));
			if (n[0] == 0 && n[1] == 0 && n[2] == 0) {
				continue;
			}
			total++;
			double[] g = {(pa[0] + pb[0] + pd[0]) / 3 - c[0], (pa[1] + pb[1] + pd[1]) / 3 - c[1], (pa[2] + pb[2] + pd[2]) / 3 - c[2]};
			if (dot(n, g) > 0) {
				out++;
			}
		}
		double outward = total == 0 ? 0 : (double) out / total;
		List<String> problems = new ArrayList<>();
		if (!(Math.abs(r / BALL_RADIUS - 1) <= BALL_RADIUS_TOLERANCE)) {
			problems.add(String.format(Locale.ROOT, "ball fitted radius %.3f UU is not within %.0f%% of %.2f UU", r,
					100 * BALL_RADIUS_TOLERANCE, BALL_RADIUS));
		}
		if (outward < MIN_OUTWARD_FRACTION) {
			problems.add(String.format(Locale.ROOT, "only %.2f%% of the ball's normals point outward (need %.0f%%)",
					100 * outward, 100 * MIN_OUTWARD_FRACTION));
		}
		double scale = r > 0 ? BALL_RADIUS / r : 1;
		MeshData mesh = mesh(m, f -> true, Placement.uniform(scale, c, false, new double[3]));
		return new Ball(mesh, c, r, scale, outward, problems);
	}

	static double[] fitSphere(float[] pts) {
		int n = pts.length / 3;
		double[] mean = new double[3];
		for (int i = 0; i < n; i++) {
			for (int k = 0; k < 3; k++) {
				mean[k] += pts[3 * i + k];
			}
		}
		for (int k = 0; k < 3; k++) {
			mean[k] /= Math.max(1, n);
		}
		double[][] a = new double[4][5];
		for (int i = 0; i < n; i++) {
			double x = pts[3 * i] - mean[0], y = pts[3 * i + 1] - mean[1], z = pts[3 * i + 2] - mean[2];
			double[] row = {2 * x, 2 * y, 2 * z, 1};
			double rhs = x * x + y * y + z * z;
			for (int r = 0; r < 4; r++) {
				for (int c = 0; c < 4; c++) {
					a[r][c] += row[r] * row[c];
				}
				a[r][4] += row[r] * rhs;
			}
		}
		double[] s = solve(a);
		if (s == null) {
			return new double[] {mean[0], mean[1], mean[2], Double.NaN};
		}
		double r2 = s[3] + s[0] * s[0] + s[1] * s[1] + s[2] * s[2];
		return new double[] {s[0] + mean[0], s[1] + mean[1], s[2] + mean[2], r2 > 0 ? Math.sqrt(r2) : Double.NaN};
	}

	private static double[] solve(double[][] a) {
		int n = a.length;
		for (int col = 0; col < n; col++) {
			int piv = col;
			for (int r = col + 1; r < n; r++) {
				if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
					piv = r;
				}
			}
			if (Math.abs(a[piv][col]) < 1e-12) {
				return null;
			}
			double[] t = a[col];
			a[col] = a[piv];
			a[piv] = t;
			for (int r = 0; r < n; r++) {
				if (r != col) {
					double f = a[r][col] / a[col][col];
					for (int c = col; c <= n; c++) {
						a[r][c] -= f * a[col][c];
					}
				}
			}
		}
		double[] x = new double[n];
		for (int i = 0; i < n; i++) {
			x[i] = a[i][n] / a[i][i];
		}
		return x;
	}

	public static MeshData mesh(PskMesh m, IntPredicate faceFilter, Placement place) {
		int nf = m.faceCount();
		int[] faces = m.faces();
		int[] idx = new int[3 * nf];
		int ni = 0;
		for (int f = 0; f < nf; f++) {
			if (!faceFilter.test(f)) {
				continue;
			}
			int w0 = faces[3 * f], w1 = faces[3 * f + 1], w2 = faces[3 * f + 2];
			int[] order = place.mirrorY() ? new int[] {w0, w1, w2} : new int[] {w1, w0, w2};
			for (int w : order) {
				idx[ni++] = w;
			}
		}
		boolean[] used = new boolean[m.wedgeCount()];
		for (int i = 0; i < ni; i++) {
			used[idx[i]] = true;
		}
		int nv = 0;
		int[] map = new int[used.length];
		int[] vertexWedge = new int[used.length];
		for (int w = 0; w < used.length; w++) {
			if (used[w]) {
				vertexWedge[nv] = w;
				map[w] = nv++;
			}
		}
		int[] indices = new int[ni];
		for (int i = 0; i < ni; i++) {
			indices[i] = map[idx[i]];
		}
		float[] v = new float[nv * MeshData.STRIDE];
		float[] pts = m.points(), uv = m.wedgeUv();
		int[] vertexPoint = new int[nv];
		for (int i = 0; i < nv; i++) {
			int w = vertexWedge[i];
			int p = m.wedgePoint()[w];
			vertexPoint[i] = p;
			float[] q = toMc(place.apply(pts[3 * p], pts[3 * p + 1], pts[3 * p + 2]));
			v[MeshData.STRIDE * i] = q[0];
			v[MeshData.STRIDE * i + 1] = q[1];
			v[MeshData.STRIDE * i + 2] = q[2];
			v[MeshData.STRIDE * i + 6] = uv[2 * w];
			v[MeshData.STRIDE * i + 7] = uv[2 * w + 1];
		}
		normals(v, indices, vertexPoint);
		float[] uv1 = new float[m.wedgeUv1().length == 0 ? 0 : 2 * nv];
		for (int i = 0; uv1.length > 0 && i < nv; i++) {
			uv1[2 * i] = m.wedgeUv1()[2 * vertexWedge[i]];
			uv1[2 * i + 1] = m.wedgeUv1()[2 * vertexWedge[i] + 1];
		}
		return new MeshData(v, indices, uv1);
	}

	private static void normals(float[] v, int[] indices, int[] vertexPoint) {
		int nv = vertexPoint.length;
		double[] raw = new double[3 * nv];
		for (int t = 0; t < indices.length; t += 3) {
			double[] a = pos(v, indices[t]), b = pos(v, indices[t + 1]), c = pos(v, indices[t + 2]);
			double[] n = cross(sub(b, a), sub(c, a));
			for (int k = 0; k < 3; k++) {
				int i = indices[t + k];
				raw[3 * i] += n[0];
				raw[3 * i + 1] += n[1];
				raw[3 * i + 2] += n[2];
			}
		}
		Integer[] order = new Integer[nv];
		for (int i = 0; i < nv; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (x, y) -> Integer.compare(vertexPoint[x], vertexPoint[y]));
		for (int s = 0; s < nv; ) {
			int e = s;
			while (e < nv && vertexPoint[order[e]] == vertexPoint[order[s]]) {
				e++;
			}
			for (int i = s; i < e; i++) {
				int vi = order[i];
				double[] own = unit(raw[3 * vi], raw[3 * vi + 1], raw[3 * vi + 2]);
				double[] sum = new double[3];
				for (int j = s; j < e; j++) {
					int vj = order[j];
					double[] other = unit(raw[3 * vj], raw[3 * vj + 1], raw[3 * vj + 2]);
					if (vj == vi || (own != null && other != null && dot(own, other) >= SMOOTH_COS)) {
						sum[0] += raw[3 * vj];
						sum[1] += raw[3 * vj + 1];
						sum[2] += raw[3 * vj + 2];
					}
				}
				double[] n = unit(sum[0], sum[1], sum[2]);
				if (n == null) {
					n = new double[] {0, 1, 0};
				}
				v[MeshData.STRIDE * vi + 3] = (float) n[0];
				v[MeshData.STRIDE * vi + 4] = (float) n[1];
				v[MeshData.STRIDE * vi + 5] = (float) n[2];
			}
			s = e;
		}
	}

	private static double[] pos(float[] v, int i) {
		return new double[] {v[MeshData.STRIDE * i], v[MeshData.STRIDE * i + 1], v[MeshData.STRIDE * i + 2]};
	}

	private static double[] point(float[] pts, int p) {
		return new double[] {pts[3 * p], pts[3 * p + 1], pts[3 * p + 2]};
	}

	private static double[] unit(double x, double y, double z) {
		double l = Math.sqrt(x * x + y * y + z * z);
		return l > 0 ? new double[] {x / l, y / l, z / l} : null;
	}

	private static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	private static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	private static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}
}
