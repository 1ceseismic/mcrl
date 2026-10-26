package dev.mcrl.rlassets;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class UmodelRunner {
	private final Path umodel;
	private final Path cookedDir;
	private final ToolRunner runner;

	public UmodelRunner(Path umodel, Path cookedDir) {
		this(umodel, cookedDir, ToolRunner.standard(installOf(cookedDir)));
	}

	UmodelRunner(Path umodel, Path cookedDir, ToolRunner runner) {
		this.umodel = umodel;
		this.cookedDir = cookedDir;
		this.runner = runner;
	}

	public Path workDir() {
		return runner.workDir();
	}

	public static Path binary(String configured) {
		return (configured != null && !configured.isBlank()) ? Path.of(configured) : McrlPaths.toolsDir().resolve("umodel");
	}

	private static Path installOf(Path cooked) {
		Path a = cooked.getParent();
		return a == null ? null : a.getParent();
	}

	List<String> exportCommand(String pkg, String obj, Path out) {
		return List.of(umodel.toString(), "-game=rocketleague", "-path=" + cookedDir, "-out=" + out,
				"-export", "-png", "-uncook", "-nooverwrite", "-noanim", "-nolightmap", pkg, obj);
	}

	List<String> exportObjectsCommand(String pkg, List<String> objects, Path out) {
		if (objects.isEmpty()) {
			throw new IllegalArgumentException("no objects to export");
		}
		List<String> cmd = new ArrayList<>(List.of(umodel.toString(), "-game=rocketleague", "-path=" + cookedDir, "-out=" + out,
				"-export", "-png", "-uncook", "-nooverwrite", "-noanim", "-nolightmap", "-mcrl-keep-alpha"));
		for (String o : objects) {
			cmd.add("-obj=" + o);
		}
		cmd.add(pkg);
		return cmd;
	}

	List<String> listCommand(String pkg) {
		return List.of(umodel.toString(), "-game=rocketleague", "-path=" + cookedDir, "-list", pkg);
	}

	List<String> unpackCommand(List<String> packages, Path out) {
		if (packages.isEmpty()) {
			throw new IllegalArgumentException("no packages to unpack");
		}
		List<String> cmd = new ArrayList<>(List.of(umodel.toString(), "-game=rocketleague", "-path=" + cookedDir,
				"-out=" + out, "-mcrl-unpack"));
		for (String p : packages.subList(1, packages.size())) {
			cmd.add("-pkg=" + p);
		}
		cmd.add(packages.get(0));
		return cmd;
	}

	public ToolRunner.ToolResult export(String pkg, String obj, Path out, Duration timeout) throws IOException, InterruptedException {
		return runner.run(exportCommand(pkg, obj, out), runner.workDir().resolve("umodel"), timeout);
	}

	public ToolRunner.ToolResult export(String pkg, String obj, Path out) throws IOException, InterruptedException {
		return export(pkg, obj, out, Duration.ofMinutes(10));
	}

	public ToolRunner.ToolResult exportObjects(String pkg, List<String> objects, Path out) throws IOException, InterruptedException {
		return runner.run(exportObjectsCommand(pkg, objects, out), runner.workDir().resolve("umodel"), Duration.ofMinutes(20));
	}

	public ToolRunner.ToolResult list(String pkg) throws IOException, InterruptedException {
		return runner.run(listCommand(pkg), runner.workDir().resolve("umodel"), Duration.ofMinutes(5));
	}

	public ToolRunner.ToolResult unpack(List<String> packages, Path out, Duration timeout) throws IOException, InterruptedException {
		return runner.run(unpackCommand(packages, out), runner.workDir().resolve("umodel"), timeout);
	}

	public ToolRunner.ToolResult unpack(List<String> packages, Path out) throws IOException, InterruptedException {
		return unpack(packages, out, Duration.ofMinutes(20));
	}
}
