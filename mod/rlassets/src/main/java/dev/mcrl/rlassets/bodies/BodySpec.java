package dev.mcrl.rlassets.bodies;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import dev.mcrl.rlassets.OctaneVisual;
import dev.mcrl.rlassets.upk.UObject;
import dev.mcrl.rlassets.upk.UpkRef;

public record BodySpec(String id, String pkg, String mesh, int preset, String shellMaterial, String chassisMaterial,
		double[] frontRest, double[] backRest, double presetFrontRadius, double presetBackRadius, Axle front, Axle back,
		boolean legacyOffsets, double travelMin, double travelMax, double xOffset, double zOffset,
		@Nullable String fxActor) {
	public record Axle(double meshRadius, double width, double meshOffsetSide, double wheelRadius) {
	}

	public static final BodySpec OCTANE = new BodySpec(BodyIds.OCTANE, OctaneVisual.BODY_PACKAGE, OctaneVisual.BODY_MESH, 0,
			"MIC_Body_Octane", "OctaneChassis_MIC", OctaneVisual.PRESET_FRONT_REST.clone(),
			OctaneVisual.PRESET_BACK_REST.clone(), OctaneVisual.PRESET_FRONT_WHEEL_RADIUS,
			OctaneVisual.PRESET_BACK_WHEEL_RADIUS,
			new Axle(OctaneVisual.FRONT_WHEEL_MESH_RADIUS, OctaneVisual.FRONT_WHEEL_WIDTH,
					OctaneVisual.FRONT_WHEEL_MESH_OFFSET_SIDE, OctaneVisual.FRONT_WHEEL_RADIUS),
			new Axle(OctaneVisual.BACK_WHEEL_MESH_RADIUS, OctaneVisual.BACK_WHEEL_WIDTH,
					OctaneVisual.BACK_WHEEL_MESH_OFFSET_SIDE, OctaneVisual.BACK_WHEEL_RADIUS),
			true, OctaneVisual.SUSPENSION_TRAVEL_MIN, OctaneVisual.SUSPENSION_TRAVEL_MAX,
			OctaneVisual.HANDLING_PRESET_X_OFFSET, OctaneVisual.HANDLING_PRESET_Z_OFFSET,
			"Body_Octane_SF:Body_Octane.FXActor");

	public BodySpec {
		frontRest = frontRest.clone();
		backRest = backRest.clone();
	}

	public static BodySpec of(String pkg, UObject product, UObject handlingPreset, int preset, String shellMaterial,
			String chassisMaterial) {
		Map<String, Object> p = product.props(), h = handlingPreset.props();
		if (!(p.get("Mesh") instanceof UpkRef mesh)) {
			throw new IllegalArgumentException("the product has no Mesh");
		}
		String fx = p.get("FXActor") instanceof UpkRef r ? r.path().indexOf(':') >= 0 ? r.path()
				: BodyScanner.stem(pkg) + ":" + r.path() : null;
		return new BodySpec(BodyIds.id(pkg), pkg, BodyScanner.lastName(mesh.path()), preset, shellMaterial,
				chassisMaterial, vector(h, "FrontAxle", "LocalRestPosition"), vector(h, "BackAxle", "LocalRestPosition"),
				number(struct(h, "FrontAxle"), "FrontAxle", "WheelRadius"),
				number(struct(h, "BackAxle"), "BackAxle", "WheelRadius"), axle(p, "FrontAxle"), axle(p, "BackAxle"),
				p.get("bUseLegacySuspensionOffsets") instanceof Boolean b && b, optional(p, "SuspensionTravelMin"),
				number(p, "", "SuspensionTravelMax"), optional(p, "HandlingPresetXOffset"),
				optional(p, "HandlingPresetZOffset"), fx);
	}

	public double radialScale(boolean front) {
		return (front ? this.front : back).meshRadius() / OctaneVisual.WHEEL_PRODUCT_MESH_RADIUS;
	}

	public double axialScale(boolean front) {
		return (front ? this.front : back).width() / OctaneVisual.WHEEL_PRODUCT_MESH_WIDTH;
	}

	public double meshOffsetSide(boolean front) {
		return (front ? this.front : back).meshOffsetSide();
	}

	public double[] presetRest(boolean front, boolean left) {
		double[] r = (front ? frontRest : backRest).clone();
		if (left) {
			r[1] = -r[1];
		}
		return r;
	}

	// RL's CarMeshComponentBase_TA.CalculatePresetSuspensionOffset.
	public double presetSuspensionOffsetZ(double frontRightDiscZ, double backRightDiscZ) {
		double fr = legacyOffsets ? front.wheelRadius() : front.meshRadius();
		double br = legacyOffsets ? back.wheelRadius() : back.meshRadius();
		double f = frontRest[2] - frontRightDiscZ + 2 * (fr - presetFrontRadius);
		double b = backRest[2] - backRightDiscZ + 2 * (br - presetBackRadius);
		return Math.max(f, b) + (legacyOffsets ? 0 : travelMin) + zOffset;
	}

	private static Axle axle(Map<String, Object> product, String name) {
		Map<?, ?> a = struct(product, name);
		return new Axle(number(a, name, "WheelMeshRadius"), number(a, name, "WheelWidth"),
				number(a, name, "WheelMeshOffsetSide"), number(a, name, "WheelRadius"));
	}

	private static Map<?, ?> struct(Map<String, Object> props, String name) {
		if (!(props.get(name) instanceof Map<?, ?> m)) {
			throw new IllegalArgumentException("no " + name);
		}
		return m;
	}

	private static double[] vector(Map<String, Object> props, String struct, String name) {
		if (!(struct(props, struct).get(name) instanceof Map<?, ?> v && v.get("X") instanceof Number x
				&& v.get("Y") instanceof Number y && v.get("Z") instanceof Number z)) {
			throw new IllegalArgumentException("no " + struct + "." + name);
		}
		return new double[] {x.doubleValue(), y.doubleValue(), z.doubleValue()};
	}

	private static double number(Map<?, ?> m, String owner, String name) {
		if (!(m.get(name) instanceof Number n)) {
			throw new IllegalArgumentException("no " + (owner.isEmpty() ? "" : owner + ".") + name);
		}
		return n.doubleValue();
	}

	private static double optional(Map<String, Object> m, String name) {
		return m.get(name) instanceof Number n ? n.doubleValue() : 0;
	}
}
