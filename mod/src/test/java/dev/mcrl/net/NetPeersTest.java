package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.mcrl.net.NetPayloads.HelloAckC2S;

class NetPeersTest {
	private static final int ABI = 6;
	private static final long HASH = 0x1234_5678_9ABC_DEF0L;

	private static NetMode granted(int protocol, int abi, long hash, NetMode chose) {
		return NetPeers.granted(new HelloAckC2S(protocol, abi, hash, chose, 0), ABI, HASH);
	}

	@Test
	void grantedDowngrades() {
		assertEquals(NetMode.PREDICT, granted(NetProtocol.VERSION, ABI, HASH, NetMode.PREDICT), "the same build");
		assertEquals(NetMode.INTERPOLATE, granted(NetProtocol.VERSION, ABI + 1, HASH, NetMode.PREDICT), "another ABI");
		assertEquals(NetMode.INTERPOLATE, granted(NetProtocol.VERSION, ABI, HASH + 1, NetMode.PREDICT),
				"another native build: no blobs");
		assertEquals(NetMode.INTERPOLATE, granted(NetProtocol.VERSION, ABI + 1, HASH, NetMode.INTERPOLATE),
				"INTERPOLATE needs no native match");
		assertEquals(NetMode.NONE, granted(NetProtocol.VERSION + 1, ABI, HASH, NetMode.PREDICT), "another protocol");
		assertEquals(NetMode.NONE, granted(NetProtocol.VERSION + 1, ABI, HASH, NetMode.INTERPOLATE));
		assertEquals(NetMode.NONE, granted(NetProtocol.VERSION, ABI, HASH, NetMode.NONE), "what the client chose");
	}
}
