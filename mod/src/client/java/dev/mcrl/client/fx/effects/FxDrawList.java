package dev.mcrl.client.fx.effects;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.mcrl.client.fx.FxActorRef;
import dev.mcrl.client.fx.particle.EmitterInstance;
import dev.mcrl.client.fx.particle.SystemInstance;
import dev.mcrl.client.fx.render.FxSkin;
import dev.mcrl.math.Pose;
import dev.mcrl.rlassets.model.AttachmentDef;

public final class FxDrawList {
	public record Static(FxActorRef ref, AttachmentDef def, Pose pose, Vector3f offset, Quaternionf rot) {
	}

	private final List<EmitterInstance> emitters = new ArrayList<>();
	private final List<@Nullable FxSkin> skins = new ArrayList<>();
	private final List<Static> statics = new ArrayList<>();

	public void add(SystemInstance s) {
		add(s, null);
	}

	public void add(SystemInstance s, @Nullable FxSkin skin) {
		for (int i = 0; i < s.emitterCount(); i++) {
			add(s.emitter(i), skin);
		}
	}

	public void add(EmitterInstance e, @Nullable FxSkin skin) {
		if (e.live() > 0) {
			emitters.add(e);
			skins.add(skin);
		}
	}

	public void addStatic(Static s) {
		statics.add(s);
	}

	public int size() {
		return emitters.size();
	}

	public List<Static> statics() {
		return statics;
	}

	public EmitterInstance get(int i) {
		return emitters.get(i);
	}

	public @Nullable FxSkin skin(int i) {
		return skins.get(i);
	}

	public void clear() {
		emitters.clear();
		skins.clear();
		statics.clear();
	}
}
