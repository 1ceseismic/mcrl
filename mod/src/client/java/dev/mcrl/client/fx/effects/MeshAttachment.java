package dev.mcrl.client.fx.effects;

import org.joml.Vector3f;

import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.FxSinks;
import dev.mcrl.rlassets.model.AttachmentDef;

public final class MeshAttachment implements FxSinks.ActorSink {
	private final ActorEffects.Registry effects;

	public MeshAttachment(ActorEffects.Registry effects) {
		this.effects = effects;
	}

	@Override
	public void activate(FxActorRef r, AttachmentDef a) {
		Vector3f at = effects.context().socket(r, a.socket()).add(Sockets.translation(a.component()));
		effects.get(r).attachStatic(r, a, ParticleAttachment.group(r.key(), null), at, Sockets.rotation(a.component()));
	}

	@Override
	public boolean deactivate(FxActorRef r, AttachmentDef a) {
		ActorEffects fx = effects.existing(r);
		if (fx != null) {
			fx.detach(r, a);
		}
		return true;
	}
}
