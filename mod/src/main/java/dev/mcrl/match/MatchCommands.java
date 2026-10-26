package dev.mcrl.match;

import java.util.function.Function;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import dev.mcrl.match.MatchManager.JoinAs;
import dev.mcrl.match.MatchManager.Result;

public final class MatchCommands {
	private MatchCommands() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
				Commands.literal("mcrl").then(Commands.literal("match")
						.then(Commands.literal("create")
								.executes(c -> asPlayer(c, p -> MatchManager.create(p, null)))
								.then(Commands.argument("size", StringArgumentType.word())
										.suggests((c, b) -> SharedSuggestionProvider.suggest(
												java.util.Arrays.stream(MatchSize.values()).map(MatchSize::label), b))
										.executes(MatchCommands::createSized)))
						.then(Commands.literal("join")
								.executes(c -> asPlayer(c, p -> MatchManager.join(p, JoinAs.AUTO)))
								.then(Commands.literal("blue").executes(c -> asPlayer(c, p -> MatchManager.join(p,
										JoinAs.BLUE))))
								.then(Commands.literal("orange").executes(c -> asPlayer(c, p -> MatchManager.join(p,
										JoinAs.ORANGE))))
								.then(Commands.literal("spectate").executes(c -> asPlayer(c, p -> MatchManager.join(p,
										JoinAs.SPECTATE)))))
						.then(Commands.literal("start").executes(c -> report(c.getSource(),
								MatchManager.start(c.getSource()))))
						.then(Commands.literal("leave").executes(c -> asPlayer(c, MatchManager::leave)))
						.then(Commands.literal("end").executes(c -> report(c.getSource(),
								MatchManager.end(c.getSource())))))));
	}

	private static int createSized(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
		String text = StringArgumentType.getString(c, "size");
		MatchSize size = MatchSize.parse(text);
		if (size == null) {
			c.getSource().sendFailure(Component.translatableWithFallback("mcrl.match.bad_size",
					"Unknown match size %s: 1v1, 2v2 or 3v3", text));
			return 0;
		}
		return asPlayer(c, p -> MatchManager.create(p, size));
	}

	private static int asPlayer(CommandContext<CommandSourceStack> c, Function<ServerPlayer, Result> f)
			throws CommandSyntaxException {
		ServerPlayer p = c.getSource().getPlayerOrException();
		return report(c.getSource(), f.apply(p));
	}

	private static int report(CommandSourceStack src, Result r) {
		if (r.ok()) {
			src.sendSuccess(r::message, false);
			return 1;
		}
		src.sendFailure(r.message());
		return 0;
	}
}
