package dev.mcrl.client.fx.effects;

public interface FxEffect {
	void update(FxFrame f);

	void collect(FxDrawList out);

	boolean done();

	void stop();

	default void removed() {
	}
}
