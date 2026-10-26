package dev.mcrl.rlassets;

import static dev.mcrl.rlassets.PskFixtures.argb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

public final class BodyFixtures {
	private BodyFixtures() {
	}

	public static void fakeUmodel(Path binary, Path fixtures, Path log) throws IOException {
		Files.createDirectories(binary.getParent());
		Files.writeString(binary, """
				#!/bin/sh
				out=""; obj=""; unpack=0; pkgs=""
				for a in "$@"; do
				  case "$a" in -out=*) out="${a#-out=}" ;; -mcrl-unpack) unpack=1 ;; -pkg=*) pkgs="$pkgs ${a#-pkg=}" ;; esac
				  obj="$a"
				done
				echo "$*" >> '%s'
				if [ $unpack = 1 ]; then
				  mkdir -p "$out"
				  for p in $obj $pkgs; do echo "image $p" > "$out/$p.bin"; done
				  exit 0
				fi
				objs=""
				for a in "$@"; do case "$a" in -obj=*) objs="$objs ${a#-obj=}" ;; esac; done
				if [ -n "$objs" ]; then
				  for o in $objs; do [ -d '%s'/"$o" ] || { echo "Export \\"$o\\" was not found in specified package(s)"; exit 1; }; done
				  mkdir -p "$out"; for o in $objs; do cp -r '%s'/"$o"/. "$out"/; done
				  exit 0
				fi
				[ -d '%s'/"$obj" ] || { echo "object $obj not found" >&2; exit 1; }
				mkdir -p "$out" && cp -r '%s'/"$obj"/. "$out"/
				""".formatted(log, fixtures, fixtures, fixtures, fixtures));
		Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
	}

	public static void body(Path fixtures, String pkgStem, String mesh, List<String> materials, double frontDx)
			throws IOException {
		Path dir = fixtures.resolve(mesh).resolve(pkgStem).resolve("SkeletalMesh3");
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.materials.addAll(materials);
		PskFixtures.box(b, new double[] {-60, -36, -12}, new double[] {80, 36, 43}, 1, 0);
		PskFixtures.octaneWheelBones(b, true, frontDx, 0);
		b.write(dir.resolve(mesh + ".psk"));
		Files.writeString(dir.resolve(mesh + ".props.txt"), "Sockets[3] =\n{\n"
				+ socket(0, "RocketBoost", -44.75, 0, 7.5) + socket(1, "BoostEmitterLeft", -46, -8, 6.5)
				+ socket(2, "BoostEmitterRight", -46, 8, 6.5) + "}\n");
	}

	public static void wheel(Path fixtures) throws IOException {
		PskFixtures.Builder w = new PskFixtures.Builder();
		w.materials.add("Wheel_OEM_MIC");
		PskFixtures.ring(w, 10, 5, 12, 0);
		PskFixtures.ring(w, 16.313, 7.264, 16, 0);
		w.write(fixtures.resolve("WHEEL_Star_SM/WHEEL_Star/StaticMesh3/WHEEL_Star_SM.pskx"));
	}

	public static void staticMesh(Path fixtures, String object, String group, String material) throws IOException {
		PskFixtures.Builder b = new PskFixtures.Builder();
		b.materials.add(material);
		PskFixtures.box(b, new double[] {-10, -5, -2}, new double[] {0, 5, 2}, 0, 0);
		b.write(fixtures.resolve(object).resolve(group).resolve("StaticMesh3").resolve(object + ".pskx"));
	}

	public static void texture(Path fixtures, String object, String group, int w, int h) throws IOException {
		PskFixtures.png(fixtures.resolve(object).resolve(group).resolve("Texture2D").resolve(object + ".png"), w, h,
				(x, y) -> argb(255, x * 30 & 0xFF, y * 50 & 0xFF, 90));
	}

	private static String socket(int i, String name, double x, double y, double z) {
		return "    Sockets[" + i + "] =\n    {\n        SocketName = " + name + "\n        BoneName = chassis_jnt\n"
				+ "        RelativeLocation = { X=" + x + ", Y=" + y + ", Z=" + z + " }\n    }\n";
	}
}
