package dev.mcrl.client.command;

import java.util.Locale;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.config.McrlConfig.Visuals;
import dev.mcrl.client.rl.RlConversion;
import dev.mcrl.client.screen.tabs.OnlineTab;

public final class McrlClientCommands {
	private McrlClientCommands() {
	}

	public interface Actions<S> {
		void set(S source, Visuals v);

		void show(S source);

		void setPrediction(S source, boolean on);

		void showPrediction(S source);
	}

	// parse exceptions make Fabric forward to the server
	private static final ArgumentType<String> TO_SERVER = new ArgumentType<>() {
		@Override
		public String parse(StringReader reader) throws CommandSyntaxException {
			throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
					.createWithContext(reader, "not a client command");
		}
	};

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
				tree(new Actions<FabricClientCommandSource>() {
					@Override
					public void set(FabricClientCommandSource source, Visuals v) {
						RlConversion.setVisuals(v);
						show(source);
					}

					@Override
					public void setPrediction(FabricClientCommandSource source, boolean on) {
						OnlineTab.setPrediction(on);
						showPrediction(source);
					}

					@Override
					public void showPrediction(FabricClientCommandSource source) {
						source.sendFeedback(Component.translatableWithFallback("mcrl.command.prediction",
								"Prediction: %s", McrlConfig.get().onlinePrediction ? "on" : "off"));
					}

					@Override
					public void show(FabricClientCommandSource source) {
						Visuals want = McrlConfig.get().visuals, drawn = RlConversion.effectiveVisuals();
						if (want != drawn) {
							source.sendFeedback(Component.translatableWithFallback("mcrl.command.visuals_fallback",
									"Visuals: %s (drawing %s until the RL cache is ready)", want.name(), drawn.name()));
						} else {
							source.sendFeedback(Component.translatableWithFallback("mcrl.command.visuals_set",
									"Visuals: %s", want.name()));
						}
					}
				})));
	}

	public static <S> LiteralArgumentBuilder<S> tree(Actions<S> actions) {
		LiteralArgumentBuilder<S> visuals = LiteralArgumentBuilder.<S>literal("visuals").executes(c -> {
			actions.show(c.getSource());
			return 1;
		});
		for (Visuals v : Visuals.values()) {
			visuals.then(LiteralArgumentBuilder.<S>literal(v.name().toLowerCase(Locale.ROOT)).executes(c -> {
				actions.set(c.getSource(), v);
				return 1;
			}));
		}
		LiteralArgumentBuilder<S> prediction = LiteralArgumentBuilder.<S>literal("prediction").executes(c -> {
			actions.showPrediction(c.getSource());
			return 1;
		});
		for (boolean on : new boolean[] {true, false}) {
			prediction.then(LiteralArgumentBuilder.<S>literal(on ? "on" : "off").executes(c -> {
				actions.setPrediction(c.getSource(), on);
				return 1;
			}));
		}
		return LiteralArgumentBuilder.<S>literal("mcrl")
				.then(visuals)
				.then(LiteralArgumentBuilder.<S>literal("online").then(prediction))
				.then(RequiredArgumentBuilder.<S, String>argument("subcommand", TO_SERVER));
	}
}
