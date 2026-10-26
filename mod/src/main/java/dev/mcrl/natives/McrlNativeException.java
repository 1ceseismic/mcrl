package dev.mcrl.natives;

public final class McrlNativeException extends RuntimeException {
	private final int code;

	public McrlNativeException(int code, String message) {
		super("mcrl native error " + code + ": " + message);
		this.code = code;
	}

	public int code() {
		return code;
	}
}
