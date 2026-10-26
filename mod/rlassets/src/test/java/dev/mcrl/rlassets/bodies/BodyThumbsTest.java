package dev.mcrl.rlassets.bodies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcrl.rlassets.CacheWriter;
import dev.mcrl.rlassets.UmodelFixtures;
import dev.mcrl.rlassets.UmodelRunner;

class BodyThumbsTest {
	@TempDir
	Path tmp;

	private UmodelRunner umodel(Path cooked, Path png) throws Exception {
		Path script = tmp.resolve("umodel");
		Files.writeString(script, "#!/bin/sh\necho \"$@\" >> '" + tmp.resolve("calls") + "'\nout=\"\"\n"
				+ "for a in \"$@\"; do case \"$a\" in -out=*) out=\"${a#-out=}\" ;; esac; done\n"
				+ "mkdir -p \"$out/Body_X_T_SF/Texture2D\"\ncp '" + png + "' \"$out/Body_X_T_SF/Texture2D/Body_X_TThumbnail.png\"\n");
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
		return UmodelFixtures.runner(script, cooked, Files.createDirectories(tmp.resolve("work")));
	}

	@Test
	void refusesNonCaches() throws Exception {
		Path cooked = Files.createDirectories(tmp.resolve("cooked"));
		Files.writeString(cooked.resolve("Body_X_T_SF.upk"), "x");
		Path png = tmp.resolve("thumb.png");
		ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
		assertThrows(CacheWriter.TargetRefusedException.class, () -> BodyThumbs.export(umodel(cooked, png), cooked,
				"Body_X", tmp.resolve("not-a-cache"), CacheWriter.Guard.standard(null)));
		assertFalse(Files.exists(tmp.resolve("calls")), "the fake umodel was never called");
		assertFalse(Files.exists(tmp.resolve("not-a-cache")));
		assertFalse(Files.exists(tmp.resolve("not-a-cache.lock")));
	}
}
