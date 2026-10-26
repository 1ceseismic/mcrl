package dev.mcrl.test;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.gametest.framework.GameTestHelper;

public class McrlServerSmokeTest {
	private static final Logger LOG = LoggerFactory.getLogger("mcrl-test");

	@GameTest
	public void smoke(GameTestHelper helper) {
		LOG.info("mcrl server gametest smoke: mcrl.clock={}", System.getProperty("mcrl.clock"));
		helper.assertTrue("lockstep".equals(System.getProperty("mcrl.clock")),
				"-Dmcrl.clock=lockstep did not reach the gametest server (got " + System.getProperty("mcrl.clock") + ")");
		helper.succeed();
	}
}
