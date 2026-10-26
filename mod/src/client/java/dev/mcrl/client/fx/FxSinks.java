package dev.mcrl.client.fx;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mcrl.rlassets.model.AttachmentDef;

public final class FxSinks {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl");
	private static final Set<Object> LOGGED = ConcurrentHashMap.newKeySet();

	public interface ActorSink {
		void activate(FxActorRef r, AttachmentDef a);

		boolean deactivate(FxActorRef r, AttachmentDef a);
	}

	private static final FxActor.Sink NONE = new FxActor.Sink() {
		@Override
		public void activate(AttachmentDef a) {
		}

		@Override
		public boolean deactivate(AttachmentDef a) {
			return true;
		}
	};
	private static volatile Map<String, ActorSink> actorSinks = Map.of();

	private FxSinks() {
	}

	public static synchronized void registerActorSink(String componentClassPrefix, ActorSink sink) {
		Map<String, ActorSink> m = new HashMap<>(actorSinks);
		m.put(Objects.requireNonNull(componentClassPrefix), Objects.requireNonNull(sink));
		actorSinks = Map.copyOf(m);
	}

	public static FxActor.Sink none() {
		return NONE;
	}

	public static FxActor.Sink forActor(FxActorRef r) {
		return new FxActor.Sink() {
			@Override
			public void activate(AttachmentDef a) {
				ActorSink s = route(a.componentClass());
				if (s != null) {
					try {
						s.activate(r, a);
					} catch (RuntimeException e) {
						failed(s, "activate", a, e);
					}
				}
			}

			@Override
			public boolean deactivate(AttachmentDef a) {
				ActorSink s = route(a.componentClass());
				if (s == null) {
					return true;
				}
				try {
					return s.deactivate(r, a);
				} catch (RuntimeException e) {
					failed(s, "deactivate", a, e);
					return true;
				}
			}
		};
	}

	private static @Nullable ActorSink route(String componentClass) {
		ActorSink best = null;
		int len = -1;
		for (Map.Entry<String, ActorSink> e : actorSinks.entrySet()) {
			String prefix = e.getKey();
			if (prefix.length() > len && componentClass.startsWith(prefix)) {
				best = e.getValue();
				len = prefix.length();
			}
		}
		return best;
	}

	private static void failed(Object s, String call, AttachmentDef a, RuntimeException e) {
		if (LOGGED.add(s)) {
			LOG.error("mcrl: an FX sink ({}) failed to {} {} ({}); the call is dropped, and this sink's later failures are"
					+ " not logged", s.getClass().getName(), call, a.name(), a.componentClass(), e);
		}
	}
}
