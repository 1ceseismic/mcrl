package dev.mcrl.rlassets;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public final class VgmstreamRunner {
	private final Path vgmstream;
	private final ToolRunner runner;

	public VgmstreamRunner(Path vgmstream, Path rlInstall) {
		this(vgmstream, ToolRunner.standard(Objects.requireNonNull(rlInstall, "rlInstall")));
	}

	public VgmstreamRunner(Path vgmstream, ToolRunner runner) {
		this.vgmstream = vgmstream;
		this.runner = runner;
	}

	public static Path binary(String configured) {
		return (configured != null && !configured.isBlank())
				? Path.of(configured)
				: McrlPaths.toolsDir().resolve("vgmstream").resolve("vgmstream-cli");
	}

	public Path executable() {
		return vgmstream;
	}

	public static @Nullable String identity(Path binary) {
		try {
			if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
				return null;
			}
			Path real = binary.toRealPath();
			return real + ";" + Files.size(real) + "@" + Files.getLastModifiedTime(real).toMillis();
		} catch (IOException e) {
			return null;
		}
	}

	List<String> decodeCommand(Path bnk, int subsong, Path outWav) {
		return List.of(vgmstream.toString(), "-i", "-s", Integer.toString(subsong), "-o", outWav.toString(), bnk.toString());
	}

	public ToolRunner.ToolResult decode(Path bnk, int subsong, Path outWav) throws IOException, InterruptedException {
		return runner.run(decodeCommand(bnk, subsong, outWav), runner.workDir().resolve("vgmstream"), Duration.ofSeconds(30));
	}

	public Path workDir() {
		return runner.workDir();
	}

	ToolRunner runner() {
		return runner;
	}
}
