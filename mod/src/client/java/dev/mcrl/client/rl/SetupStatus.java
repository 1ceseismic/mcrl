package dev.mcrl.client.rl;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.Converter;
import dev.mcrl.rlassets.LocateResult;
import dev.mcrl.rlassets.VgmstreamRunner;

public record SetupStatus(LocateResult rl, ToolStatus umodel, ToolStatus vgmstream, CacheStatus cache) {
	public record ToolStatus(@Nullable Path path, boolean found) {
	}

	public record CacheStatus(State state, boolean sounds, @Nullable String reason) {
		public enum State { CURRENT, STALE, MISSING, INVALID, UNCHECKED }
	}

	public static SetupStatus compute(RlConversion.Host host) {
		LocateResult loc = host.locate();
		Path umodel = host.umodelBinary();
		Path vgm = host.vgmstreamBinary();
		ToolStatus umodelStatus = new ToolStatus(umodel, Files.isRegularFile(umodel) && Files.isExecutable(umodel));
		ToolStatus vgmStatus = new ToolStatus(vgm, VgmstreamRunner.identity(vgm) != null);
		return new SetupStatus(loc, umodelStatus, vgmStatus, cache(host, loc));
	}

	private static CacheStatus cache(RlConversion.Host host, LocateResult loc) {
		Path dir = host.cacheDir();
		if (!Files.isRegularFile(dir.resolve("manifest.json"))) {
			return new CacheStatus(CacheStatus.State.MISSING, false, null);
		}
		if (CacheWriter.fingerprintOf(dir) == null) {
			return new CacheStatus(CacheStatus.State.INVALID, false, "its manifest is unreadable or of another format");
		}
		CacheWriter.SoundsStatus st = CacheWriter.soundsStatus(dir);
		boolean sounds = CacheWriter.reusableSounds(dir) != null;
		if (loc.state() != LocateResult.State.FOUND) {
			return new CacheStatus(CacheStatus.State.UNCHECKED, sounds, null);
		}
		boolean current = Converter.isCurrent(loc, dir, host.decoder(loc));
		return new CacheStatus(current ? CacheStatus.State.CURRENT : CacheStatus.State.STALE, sounds, st.missing());
	}
}
