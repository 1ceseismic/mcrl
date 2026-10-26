package dev.mcrl.bot;

public final class BotPolicyException extends Exception {
	public BotPolicyException(String message) {
		super(message);
	}

	public BotPolicyException(String message, Throwable cause) {
		super(message, cause);
	}
}
