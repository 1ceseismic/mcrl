package dev.mcrl.bot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public record BotPolicy(Path source, int[] dims, float[][] params) {
	public static final int OBS = 89, ACTIONS = 90;
	public static final int MAX_LAYERS = 32, MAX_WIDTH = 4096;
	public static final long MAX_PARAMS = 16L << 20;
	private static final Pattern BLOB = Pattern.compile("^([^/]+)/data/(\\d+)$");

	public BotPolicy {
		dims = dims.clone();
		params = params.clone();
	}

	@Override
	public int[] dims() {
		return dims.clone();
	}

	@Override
	public float[][] params() {
		return params.clone();
	}

	public int paramCount() {
		int n = 0;
		for (float[] p : params) {
			n += p.length;
		}
		return n;
	}

	private static BotPolicyException bad(Path file, String what) {
		return new BotPolicyException("botPolicy: " + file + ": " + what);
	}

	public static BotPolicy read(Path policyLt) throws BotPolicyException {
		if (!Files.isRegularFile(policyLt)) {
			throw bad(policyLt, "no such file");
		}
		try (ZipFile z = new ZipFile(policyLt.toFile())) {
			Map<Integer, ZipEntry> blobs = new HashMap<>();
			String root = null;
			for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				Matcher m = BLOB.matcher(entry.getName());
				if (!m.matches()) {
					continue;
				}
				if (root != null && !root.equals(m.group(1))) {
					throw bad(policyLt, "weights under two roots, " + root + " and " + m.group(1));
				}
				root = m.group(1);
				String digits = m.group(2);
				long index = digits.length() > 3 ? Long.MAX_VALUE : Long.parseLong(digits);
				if (index >= 2L * MAX_LAYERS) {
					throw bad(policyLt, entry.getName() + ": more weights than " + MAX_LAYERS + " layers hold (data/0.."
							+ (2 * MAX_LAYERS - 1) + ")");
				}
				if (blobs.put((int) index, entry) != null) {
					throw bad(policyLt, "data/" + index + " twice");
				}
			}
			if (blobs.isEmpty()) {
				throw bad(policyLt, "no data/0 weights: not a GigaLearn POLICY.lt");
			}
			int last = blobs.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
			int count = last + 1 + (last % 2 == 0 ? 1 : 0);
			String prefix = root + "/";
			for (int i = 0; i < count; i++) {
				if (!blobs.containsKey(i)) {
					throw bad(policyLt, prefix + "data/" + i + " is missing");
				}
			}
			int layers = count / 2;
			int[] dims = new int[layers + 1];
			dims[0] = OBS;
			long total = 0;
			for (int l = 0; l < layers; l++) {
				ZipEntry b = blobs.get(2 * l + 1);
				long bytes = b.getSize();
				if (bytes < 4 || bytes % 4 != 0 || bytes / 4 > MAX_WIDTH) {
					throw bad(policyLt, b.getName() + " holds " + bytes + " bytes: layer " + l + "'s bias must be 1.."
							+ MAX_WIDTH + " float32 values");
				}
				dims[l + 1] = (int) (bytes / 4);
				total += (long) dims[l + 1] * dims[l] + dims[l + 1];
			}
			if (total > MAX_PARAMS) {
				throw bad(policyLt, "the layers hold " + total + " parameters, more than " + MAX_PARAMS);
			}
			long w0 = blobs.get(0).getSize();
			if (w0 != 4L * OBS * dims[1]) {
				String in = w0 >= 0 && w0 % (4L * dims[1]) == 0 ? Long.toString(w0 / (4L * dims[1])) : w0 + " bytes of";
				throw bad(policyLt, "the first layer takes " + in + " inputs, not 89 (a GigaLearn DefaultObs policy; a "
						+ "python PPO checkpoint has 92)");
			}
			for (int l = 1; l < layers; l++) {
				long want = 4L * dims[l + 1] * dims[l];
				if (blobs.get(2 * l).getSize() != want) {
					throw bad(policyLt, "layer " + l + "'s weights hold " + blobs.get(2 * l).getSize() + " bytes, not "
							+ dims[l + 1] + " x " + dims[l] + " float32 values");
				}
			}
			if (dims[layers] != ACTIONS) {
				throw bad(policyLt, "the last layer gives " + dims[layers] + " outputs, not 90 (the action rows)");
			}
			float[][] params = new float[count][];
			for (int i = 0; i < count; i++) {
				ZipEntry entry = blobs.get(i);
				params[i] = floats(z, entry, entry.getSize(), policyLt);
				for (float v : params[i]) {
					if (!Float.isFinite(v)) {
						throw bad(policyLt, "data/" + i + " holds a value that is not finite");
					}
				}
			}
			return new BotPolicy(policyLt, dims, params);
		} catch (ZipException e) {
			throw new BotPolicyException("botPolicy: " + policyLt + ": not a zip (" + e.getMessage() + ")", e);
		} catch (IOException e) {
			throw new BotPolicyException("botPolicy: " + policyLt + ": unreadable (" + e.getMessage() + ")", e);
		}
	}

	private static float[] floats(ZipFile z, ZipEntry entry, long bytes, Path file) throws IOException,
			BotPolicyException {
		byte[] data;
		try (InputStream in = z.getInputStream(entry)) {
			data = in.readNBytes((int) bytes + 1);
		}
		if (data.length != bytes) {
			throw bad(file, entry.getName() + " holds " + (data.length > bytes ? "more" : "fewer") + " bytes than its "
					+ "zip header says (" + bytes + ")");
		}
		float[] out = new float[data.length / 4];
		ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
		return out;
	}

	public static Path resolve(Path setting) throws BotPolicyException {
		return candidates(setting).get(0);
	}

	public static List<Path> candidates(Path setting) throws BotPolicyException {
		if (Files.isRegularFile(setting)) {
			return List.of(setting);
		}
		if (!Files.isDirectory(setting)) {
			throw new BotPolicyException("botPolicy: " + setting + ": no such file or folder");
		}
		Path direct = setting.resolve("POLICY.lt");
		if (Files.isRegularFile(direct)) {
			return List.of(direct);
		}
		List<Path> numbered = new ArrayList<>();
		try (Stream<Path> s = Files.list(setting)) {
			for (Path sub : (Iterable<Path>) s::iterator) {
				if (sub.getFileName().toString().matches("\\d{1,18}") && Files.isRegularFile(sub.resolve("POLICY.lt"))) {
					numbered.add(sub);
				}
			}
		} catch (IOException e) {
			throw new BotPolicyException("botPolicy: " + setting + ": unreadable folder (" + e.getMessage() + ")", e);
		}
		if (numbered.isEmpty()) {
			throw new BotPolicyException("botPolicy: " + setting + ": no POLICY.lt, nor a numbered checkpoint folder "
					+ "holding one");
		}
		numbered.sort(Comparator.comparingLong((Path p) -> Long.parseLong(p.getFileName().toString())).reversed());
		return numbered.stream().map(p -> p.resolve("POLICY.lt")).toList();
	}
}
