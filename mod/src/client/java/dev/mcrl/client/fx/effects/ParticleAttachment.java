package dev.mcrl.client.fx.effects;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mcrl.client.fx.EffectsSettings.Group;
import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxActors;
import dev.mcrl.client.fx.FxConstants;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.client.fx.particle.FxParams;
import dev.mcrl.rlassets.model.AttachmentDef;

public final class ParticleAttachment implements FxSinks.ActorSink {
	private final ActorEffects.Registry effects;

	public ParticleAttachment(ActorEffects.Registry effects) {
		this.effects = effects;
	}

	@Override
	public void activate(FxActorRef r, AttachmentDef a) {
		String template = template(a.component());
		if (template == null) {
			return;
		}
		FxContext ctx = effects.context();
		ActorEffects fx = effects.get(r);
		FxParams params = new FxParams(r.params());
		setAll(a.component().get("InstanceParameters"), params);
		boolean boost = false;
		JsonElement traits = a.props().get("Traits");
		if (traits != null && traits.isJsonArray()) {
			for (JsonElement t : (JsonArray) traits) {
				if (t.isJsonObject()) {
					JsonObject o = t.getAsJsonObject();
					boost |= FxConstants.BOOST_PARTICLE_TRAIT.equals(string(o, "$class"));
					setAll(o.get("SharedParameters"), params);
				}
			}
		}
		Group group = group(r.key(), template);
		Vector3f translation = Sockets.translation(a.component());
		Quaternionf rot = Sockets.rotation(a.component());
		JsonElement abs = a.component().get("AbsoluteRotation");
		boolean absolute = abs != null && abs.isJsonPrimitive() && abs.getAsBoolean();
		if (boost) {
			for (String socket : FxConstants.BOOST_EMITTER_SOCKETS) {
				fx.attachParticles(r, a, group, template, params, ctx.socket(r, socket).add(translation), rot, absolute);
			}
		} else {
			fx.attachParticles(r, a, group, template, params, ctx.socket(r, a.socket()).add(translation), rot, absolute);
		}
	}

	@Override
	public boolean deactivate(FxActorRef r, AttachmentDef a) {
		ActorEffects fx = effects.existing(r);
		return fx == null || fx.detach(r, a);
	}

	public static @Nullable Group group(String key, @Nullable String template) {
		if (key.startsWith(SupersonicTrails.KEY)) {
			return Group.SUPERSONIC;
		}
		return switch (key) {
			case FxActors.CAR -> Group.MOVEMENT;
			case FxActors.BOOST -> Group.BOOST;
			case FxActors.BODY -> template != null && template.contains("Supersonic_Team") ? Group.SUPERSONIC
					: Group.BOOST;
			case FxActors.BALL -> Group.BALL;
			case "spawn", "leave", "demolish" -> Group.SPAWN;
			default -> null;
		};
	}

	static @Nullable String template(JsonObject component) {
		String t = string(component, "Template");
		return t == null || t.isEmpty() || t.equals("None") ? null : t;
	}

	public static void setAll(@Nullable JsonElement list, FxParams into) {
		if (list == null || !list.isJsonArray()) {
			return;
		}
		for (JsonElement e : (JsonArray) list) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject p = e.getAsJsonObject();
			String name = string(p, "Name");
			String type = string(p, "ParamType");
			if (name == null || type == null) {
				continue;
			}
			switch (type) {
				case "PSPT_Scalar" -> into.setScalar(name, number(p, "Scalar"));
				case "PSPT_ScalarRand" -> into.setScalarRand(name, number(p, "Scalar_Low"), number(p, "Scalar"));
				case "PSPT_Vector" -> {
					float[] v = vector(p, "Vector");
					into.setVector(name, v[0], v[1], v[2]);
				}
				case "PSPT_VectorRand" -> into.setVectorRand(name, vector(p, "Vector_Low"), vector(p, "Vector"));
				case "PSPT_Color" -> {
					JsonElement c = p.get("Color");
					if (c != null && c.isJsonObject()) {
						JsonObject o = c.getAsJsonObject();
						into.setColor(name, number(o, "R") / 255, number(o, "G") / 255, number(o, "B") / 255,
								number(o, "A") / 255);
					}
				}
				default -> {
				}
			}
		}
	}

	private static float[] vector(JsonObject p, String key) {
		JsonElement v = p.get(key);
		if (v == null || !v.isJsonObject()) {
			return new float[3];
		}
		JsonObject o = v.getAsJsonObject();
		return new float[] {number(o, "X"), number(o, "Y"), number(o, "Z")};
	}

	private static float number(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsFloat() : 0;
	}

	private static @Nullable String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}
}
