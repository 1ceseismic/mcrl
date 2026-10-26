package dev.mcrl.rlassets;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import javax.imageio.ImageIO;

final class PskFixtures {
	static final String MAGIC = "ACTR" + "HEAD";

	private PskFixtures() {
	}

	record Bone(String name, int parent, float[] quat, float[] pos) {
	}

	static final class Builder {
		final List<float[]> points = new ArrayList<>();
		final List<float[]> wedges = new ArrayList<>(); // point, u, v, mat
		final List<int[]> faces = new ArrayList<>(); // w0, w1, w2, mat, smoothing
		final List<String> materials = new ArrayList<>();
		final List<Bone> bones = new ArrayList<>();
		boolean extraChunks;
		boolean face32;
		float[] uv1;

		int point(double x, double y, double z) {
			points.add(new float[] {(float) x, (float) y, (float) z});
			return points.size() - 1;
		}

		int wedge(int point, double u, double v, int mat) {
			wedges.add(new float[] {point, (float) u, (float) v, mat});
			return wedges.size() - 1;
		}

		void face(int w0, int w1, int w2, int mat, int smoothing) {
			faces.add(new int[] {w0, w1, w2, mat, smoothing});
		}

		void outwardTri(int wa, int wb, int wc, double[] outward, int mat) {
			double[] a = pos(wa), b = pos(wb), c = pos(wc);
			double[] n = cross(sub(b, a), sub(c, a));
			if (dot(n, outward) < 0) {
				int t = wb;
				wb = wc;
				wc = t;
			}
			face(wb, wa, wc, mat, 1);
		}

		double[] pos(int wedge) {
			float[] p = points.get((int) wedges.get(wedge)[0]);
			return new double[] {p[0], p[1], p[2]};
		}

		void bone(String name, int parent, double x, double y, double z) {
			bones.add(new Bone(name, parent, new float[] {0, 0, 0, 1}, new float[] {(float) x, (float) y, (float) z}));
		}

		byte[] bytes() {
			List<byte[]> chunks = new ArrayList<>();
			chunks.add(chunk(MAGIC, 0, 0, new byte[0]));
			ByteBuffer p = le(points.size() * 12);
			for (float[] q : points) {
				p.putFloat(q[0]).putFloat(q[1]).putFloat(q[2]);
			}
			chunks.add(chunk("PNTS0000", 12, points.size(), p.array()));
			ByteBuffer w = le(wedges.size() * 16);
			for (float[] q : wedges) {
				w.putInt((int) q[0]).putFloat(q[1]).putFloat(q[2]).put((byte) q[3]).put((byte) 0).putShort((short) 0);
			}
			chunks.add(chunk("VTXW0000", 16, wedges.size(), w.array()));
			if (face32) {
				ByteBuffer f = le(faces.size() * 18);
				for (int[] q : faces) {
					f.putInt(q[0]).putInt(q[1]).putInt(q[2]).put((byte) q[3]).put((byte) 0).putInt(q[4]);
				}
				chunks.add(chunk("FACE3200", 18, faces.size(), f.array()));
			} else {
				ByteBuffer f = le(faces.size() * 12);
				for (int[] q : faces) {
					f.putShort((short) q[0]).putShort((short) q[1]).putShort((short) q[2]).put((byte) q[3]).put((byte) 0)
							.putInt(q[4]);
				}
				chunks.add(chunk("FACE0000", 12, faces.size(), f.array()));
			}
			ByteBuffer m = le(materials.size() * 88);
			for (String s : materials) {
				m.put(name64(s)).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
			}
			chunks.add(chunk("MATT0000", 88, materials.size(), m.array()));
			ByteBuffer b = le(bones.size() * 120);
			for (Bone bn : bones) {
				int children = 0;
				for (Bone o : bones) {
					if (o != bn && bones.get(o.parent()) == bn) {
						children++;
					}
				}
				b.put(name64(bn.name())).putInt(0).putInt(children).putInt(bn.parent());
				for (float f : bn.quat()) {
					b.putFloat(f);
				}
				for (float f : bn.pos()) {
					b.putFloat(f);
				}
				b.putFloat(1).putFloat(1).putFloat(1).putFloat(1);
			}
			chunks.add(chunk("REFSKELT", 120, bones.size(), b.array()));
			if (extraChunks) {
				chunks.add(chunk("RAWWEIGHTS", 12, 1, new byte[12]));
				ByteBuffer u = le(8 * wedges.size());
				for (int i = 0; uv1 != null && i < 2 * wedges.size(); i++) {
					u.putFloat(uv1[i]);
				}
				chunks.add(chunk("EXTRAUVS0", 8, wedges.size(), u.array()));
				chunks.add(chunk("VERTEXCOLOR", 4, wedges.size(), new byte[4 * wedges.size()]));
			}
			int n = 0;
			for (byte[] c : chunks) {
				n += c.length;
			}
			ByteBuffer all = ByteBuffer.allocate(n);
			for (byte[] c : chunks) {
				all.put(c);
			}
			return all.array();
		}

		Path write(Path f) throws IOException {
			Files.createDirectories(f.getParent());
			Files.write(f, bytes());
			return f;
		}
	}

	static ByteBuffer le(int n) {
		return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
	}

	static byte[] name64(String s) {
		byte[] o = new byte[64];
		byte[] b = s.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(b, 0, o, 0, Math.min(63, b.length));
		return o;
	}

	static byte[] chunk(String id, int size, int count, byte[] data) {
		ByteBuffer h = le(32 + data.length);
		byte[] idb = new byte[20];
		byte[] s = id.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(s, 0, idb, 0, s.length);
		h.put(idb).putInt(20100422).putInt(size).putInt(count).put(data);
		return h.array();
	}

	static double[] sub(double[] a, double[] b) {
		return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
	}

	static double[] cross(double[] a, double[] b) {
		return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
	}

	static double dot(double[] a, double[] b) {
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	static void ring(Builder b, double r, double hw, int segments, int mat) {
		int[] lo = new int[segments];
		int[] hi = new int[segments];
		for (int k = 0; k < segments; k++) {
			double a = 2 * Math.PI * k / segments;
			lo[k] = b.wedge(b.point(r * Math.cos(a), -hw, r * Math.sin(a)), (double) k / segments, 0, mat);
			hi[k] = b.wedge(b.point(r * Math.cos(a), hw, r * Math.sin(a)), (double) k / segments, 1, mat);
		}
		for (int k = 0; k < segments; k++) {
			int k1 = (k + 1) % segments;
			double a = 2 * Math.PI * (k + 0.5) / segments;
			double[] out = {Math.cos(a), 0, Math.sin(a)};
			b.outwardTri(lo[k], lo[k1], hi[k1], out, mat);
			b.outwardTri(lo[k], hi[k1], hi[k], out, mat);
		}
	}

	static void sphere(Builder b, double[] c, double r, int stacks, int slices, int mat) {
		int[][] w = new int[stacks + 1][slices];
		for (int i = 0; i <= stacks; i++) {
			double th = Math.PI * i / stacks;
			for (int j = 0; j < slices; j++) {
				double ph = 2 * Math.PI * j / slices;
				if ((i == 0 || i == stacks) && j > 0) {
					w[i][j] = w[i][0];
					continue;
				}
				w[i][j] = b.wedge(b.point(c[0] + r * Math.sin(th) * Math.cos(ph), c[1] + r * Math.sin(th) * Math.sin(ph),
						c[2] + r * Math.cos(th)), (double) j / slices, (double) i / stacks, mat);
			}
		}
		for (int i = 0; i < stacks; i++) {
			for (int j = 0; j < slices; j++) {
				int j1 = (j + 1) % slices;
				int a = w[i][j], bb = w[i][j1], cc = w[i + 1][j1], d = w[i + 1][j];
				double[] mid = sub(centroid(b, a, cc), c);
				if (i > 0) {
					b.outwardTri(a, bb, cc, mid, mat);
				}
				if (i < stacks - 1) {
					b.outwardTri(a, cc, d, mid, mat);
				}
			}
		}
	}

	private static double[] centroid(Builder b, int w0, int w1) {
		double[] p = b.pos(w0), q = b.pos(w1);
		return new double[] {(p[0] + q[0]) / 2, (p[1] + q[1]) / 2, (p[2] + q[2]) / 2};
	}

	static void octaneWheelBones(Builder b, boolean premium, double frontDx, double backDx) {
		b.bone("root_jnt", 0, 0, 0, 0);
		b.bone("chassis_jnt", 0, 0, 0, 0);
		// translation x, y (left wheel), z; pivot y; disc y, z
		double[] front = premium ? new double[] {51.2805, 24.822, -5.9717, 0.4523, 1.1812, 0.0001}
				: new double[] {51.2416, 18.6339, -5.9671, 5.3212, 1.9685, 0.0001};
		double[] back = premium ? new double[] {-34.3206, 26.595, -4.3198, 0, 2.8, 0}
				: new double[] {-34.2945, 21.0217, -4.3165, 0, 8.5059, 0};
		for (String k : new String[] {"FL", "FR", "BL", "BR"}) {
			boolean f = k.charAt(0) == 'F';
			double s = k.charAt(1) == 'L' ? 1 : -1;
			double[] a = f ? front : back;
			int t = b.bones.size();
			double y = a[1];
			if (premium && k.equals("FR")) {
				y = 24.4726;
			}
			b.bone(k + "_WheelTranslation_jnt", 0, a[0] + (f ? frontDx : backDx), s * y, a[2]);
			if (f) {
				b.bone(k + "_Pivot_jnt", t, 0, s * a[3], 0);
				b.bone(k + "_Disc_jnt", t + 1, 0, s * a[4], a[5]);
			} else {
				b.bone(k + "_Disc_jnt", t, 0, s * a[4], a[5]);
			}
		}
	}

	static void disc(Builder b, double r, double y, int segments, double normalY, int mat) {
		int c = b.wedge(b.point(0, y, 0), 0.5, 0.5, mat);
		int[] rim = new int[segments];
		for (int k = 0; k < segments; k++) {
			double a = 2 * Math.PI * k / segments;
			rim[k] = b.wedge(b.point(r * Math.cos(a), y, r * Math.sin(a)), 0.5 + 0.5 * Math.cos(a), 0.5 + 0.5 * Math.sin(a), mat);
		}
		for (int k = 0; k < segments; k++) {
			b.outwardTri(c, rim[k], rim[(k + 1) % segments], new double[] {0, normalY, 0}, mat);
		}
	}

	static void box(Builder b, double[] lo, double[] hi, int topMat, int otherMat) {
		double[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (double[] nn : n) {
			int axis = nn[0] != 0 ? 0 : nn[1] != 0 ? 1 : 2;
			int u = (axis + 1) % 3, v = (axis + 2) % 3;
			double fixed = nn[axis] > 0 ? hi[axis] : lo[axis];
			int mat = (axis == 2 && nn[2] > 0) ? topMat : otherMat;
			int[] q = new int[4];
			double[][] uv = {{lo[u], lo[v]}, {hi[u], lo[v]}, {hi[u], hi[v]}, {lo[u], hi[v]}};
			for (int k = 0; k < 4; k++) {
				double[] p = new double[3];
				p[axis] = fixed;
				p[u] = uv[k][0];
				p[v] = uv[k][1];
				q[k] = b.wedge(b.point(p[0], p[1], p[2]), k == 1 || k == 2 ? 1 : 0, k >= 2 ? 1 : 0, mat);
			}
			b.outwardTri(q[0], q[1], q[2], nn, mat);
			b.outwardTri(q[0], q[2], q[3], nn, mat);
		}
	}

	static Path png(Path f, int w, int h, IntBinaryOperator argb) throws IOException {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				img.setRGB(x, y, argb.applyAsInt(x, y));
			}
		}
		Files.createDirectories(f.getParent());
		ImageIO.write(img, "png", f.toFile());
		return f;
	}

	static int argb(int a, int r, int g, int b) {
		return a << 24 | r << 16 | g << 8 | b;
	}
}
