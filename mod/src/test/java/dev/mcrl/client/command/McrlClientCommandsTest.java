package dev.mcrl.client.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.mcrl.client.config.McrlConfig.Visuals;

class McrlClientCommandsTest {
	private final List<Visuals> set = new ArrayList<>();
	private final List<Boolean> prediction = new ArrayList<>();
	private int shown, predictionShown;
	private final CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();

	McrlClientCommandsTest() {
		dispatcher.register(McrlClientCommands.tree(new McrlClientCommands.Actions<Object>() {
			@Override
			public void set(Object source, Visuals v) {
				set.add(v);
			}

			@Override
			public void show(Object source) {
				shown++;
			}

			@Override
			public void setPrediction(Object source, boolean on) {
				prediction.add(on);
			}

			@Override
			public void showPrediction(Object source) {
				predictionShown++;
			}
		}));
	}

	@Test
	void visuals_runs_on_the_client_and_the_rest_reaches_the_server() throws CommandSyntaxException {
		dispatcher.execute("mcrl visuals hitbox", new Object());
		dispatcher.execute("mcrl visuals simple", new Object());
		dispatcher.execute("mcrl visuals rl", new Object());
		dispatcher.execute("mcrl visuals", new Object());
		assertEquals(List.of(Visuals.HITBOX, Visuals.SIMPLE, Visuals.RL), set);
		assertEquals(1, shown);
		for (String serverCommand : List.of("mcrl car", "mcrl ball", "mcrl physics status", "mcrl stepup true", "mcrl")) {
			CommandSyntaxException e = assertThrows(CommandSyntaxException.class,
					() -> dispatcher.execute(serverCommand, new Object()));
			assertTrue(sentToServer(e.getType()), serverCommand + " stays on the client: " + e.getMessage());
		}
		CommandSyntaxException bad = assertThrows(CommandSyntaxException.class,
				() -> dispatcher.execute("mcrl visuals fancy", new Object()));
		assertFalse(sentToServer(bad.getType()), "a bad mode is the client command's own error");
		assertEquals(3, set.size());
	}

	private static boolean sentToServer(CommandExceptionType t) {
		return t == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand()
				|| t == CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException();
	}
}
