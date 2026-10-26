package dev.mcrl.rlassets;

import java.nio.file.Path;

public final class UmodelFixtures {
	private UmodelFixtures() {
	}

	public static UmodelRunner runner(Path binary, Path cookedDir, Path workDir) {
		return new UmodelRunner(binary, cookedDir, new ToolRunner(workDir, null));
	}
}
