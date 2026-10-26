package dev.mcrl.match;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MatchBlockTest {
	@Test
	void wrongLengthRefused() {
		assertThrows(IllegalArgumentException.class, () -> MatchBlock.decode(new byte[109]));
		assertThrows(IllegalArgumentException.class, () -> new MatchBlock(new boolean[33], new float[33],
				MatchBlock.Header.lobby(true)));
	}
}
