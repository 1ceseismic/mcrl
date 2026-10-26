package dev.mcrl.client.fx.effects;

import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import net.minecraft.world.phys.Vec3;

import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.fx.particle.FxEmitterDef.Alignment;
import dev.mcrl.client.fx.particle.FxModule.Lock;
import dev.mcrl.client.fx.render.FxColor;
import dev.mcrl.client.fx.render.FxGeometry;
import dev.mcrl.client.fx.render.FxView;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.AttachmentDef;

public final class LensFlare implements FxSinks.ActorSink {
	private static final Vector3f X = new Vector3f(1, 0, 0), Y = new Vector3f(0, 1, 0), Z = new Vector3f(0, 0, 1);
	private final ActorEffects.Registry effects;

	public LensFlare(ActorEffects.Registry effects) {
		this.effects = effects;
	}

	@Override
	public void activate(FxActorRef r, AttachmentDef a) {
		FxContext ctx = effects.context();
		Vector3f translation = Sockets.translation(a.component());
		Quaternionf rot = Sockets.rotation(a.component());
		ActorEffects fx = effects.get(r);
		if (boostTrait(a)) {
			for (String socket : FxConstants.BOOST_EMITTER_SOCKETS) {
				fx.attachStatic(r, a, ParticleAttachment.group(r.key(), null), ctx.socket(r, socket).add(translation), rot);
			}
		} else {
			fx.attachStatic(r, a, ParticleAttachment.group(r.key(), null), ctx.socket(r, a.socket()).add(translation), rot);
		}
	}

	@Override
	public boolean deactivate(FxActorRef r, AttachmentDef a) {
		ActorEffects fx = effects.existing(r);
		if (fx != null) {
			fx.detach(r, a);
		}
		return true;
	}

	public static float alpha(double distanceUu, double angleDeg) {
		double d = distanceUu >= FxConstants.LENS_FLARE_DIST_UU ? 0
				: FxConstants.LENS_FLARE_DIST_ALPHA * (1 - Math.max(0, distanceUu) / FxConstants.LENS_FLARE_DIST_UU);
		double inner = FxConstants.LENS_FLARE_INNER_DEG, outer = FxConstants.LENS_FLARE_OUTER_DEG;
		double cone = angleDeg <= inner ? 1 : angleDeg >= outer ? 0 : 1 - (angleDeg - inner) / (outer - inner);
		return (float) (FxConstants.LENS_FLARE_ALPHA * d * cone);
	}

	public record Key(FxActorRef ref, AttachmentDef def, Vector3fc offset) {
	}

	public static Key key(FxDrawList.Static s) {
		return new Key(s.ref(), s.def(), s.offset());
	}

	public static Vector3d source(FxDrawList.Static s, Vector3d out) {
		Pose p = s.pose();
		Vector3f at = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()).transform(new Vector3f(s.offset()));
		return out.set(p.x() + at.x, p.y() + at.y, p.z() + at.z);
	}

	public static float alpha(FxDrawList.Static s, FxView view) {
		Pose p = s.pose();
		Quaternionf q = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw());
		Vector3f at = q.transform(new Vector3f(s.offset()));
		Vec3 cam = view.position();
		float x = (float) (p.x() + at.x - cam.x), y = (float) (p.y() + at.y - cam.y), z = (float) (p.z() + at.z - cam.z);
		double dist = Math.sqrt(x * x + y * y + z * z);
		Vector3f back = q.transform(new Vector3f(-1, 0, 0));
		double angle = dist < 1e-6 ? 0
				: Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -(back.x * x + back.y * y + back.z * z) / dist))));
		return alpha(dist * 50, angle);
	}

	public static void quad(FxDrawList.Static s, FxView view, float a, FxGeometry g, FxGeometry.Sink out) {
		if (!(a > 0)) {
			return;
		}
		Pose p = s.pose();
		Vector3f at = new Quaternionf(p.qx(), p.qy(), p.qz(), p.qw()).transform(new Vector3f(s.offset()));
		Vec3 cam = view.position();
		float x = (float) (p.x() + at.x - cam.x), y = (float) (p.y() + at.y - cam.y), z = (float) (p.z() + at.z - cam.z);
		float[] c = FxConstants.LENS_FLARE_COLOR;
		out.particle(c[0], c[1], c[2], a, 0, 0, 0, 0, (float) ((p.y() + at.y) * 50));
		g.sprite(x, y, z, FxConstants.LENS_FLARE_WIDTH_UU / 50, FxConstants.LENS_FLARE_HEIGHT_UU / 50, 0, 0, 0, 0,
				Alignment.RECTANGLE, Lock.NONE, X, Z, Y, view, 0, 0, 1, 1, FxColor.additive(c[0], c[1], c[2], a),
				FxGeometry.FULL_BRIGHT, out);
	}

	private static boolean boostTrait(AttachmentDef a) {
		JsonElement traits = a.props().get("Traits");
		if (traits != null && traits.isJsonArray()) {
			for (JsonElement t : (JsonArray) traits) {
				if (t.isJsonObject() && t.getAsJsonObject().has("$class")
						&& FxConstants.BOOST_PARTICLE_TRAIT.equals(t.getAsJsonObject().get("$class").getAsString())) {
					return true;
				}
			}
		}
		return false;
	}
}
