package dev.mcrl.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import dev.mcrl.match.MatchBlock;
import dev.mcrl.match.MatchSize;
import dev.mcrl.math.Pose;
import dev.mcrl.net.MatchPayloads.MatchEventS2C;
import dev.mcrl.net.MatchPayloads.MatchS2C;
import dev.mcrl.net.MatchPayloads.MatchSetupS2C;
import dev.mcrl.net.MatchPayloads.SlotInfo;
import dev.mcrl.net.McrlPayloads.CarChoiceC2S;
import dev.mcrl.net.McrlPayloads.ExitCarC2S;
import dev.mcrl.net.McrlPayloads.FxEventS2C;
import dev.mcrl.net.McrlPayloads.KnockS2C;
import dev.mcrl.net.McrlPayloads.ResetCarC2S;
import dev.mcrl.net.McrlPayloads.RulesS2C;
import dev.mcrl.net.NetPayloads.HelloAckC2S;
import dev.mcrl.net.NetPayloads.HelloS2C;
import dev.mcrl.net.NetPayloads.InputC2S;
import dev.mcrl.net.NetPayloads.SnapshotS2C;
import dev.mcrl.sim.CarFx;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.Hit;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimFrame;

class WireFormatTest {
	private static final String GOLDEN = """
			mcrl:hello
			02060123456789abcdef86a43c78020000000000000000ffffedcba9876544
			mcrl:hello_ack
			0206fffffffffffffffb020000000000000000000000000000004d
			mcrl:input
			808080808020c9027fc120008105000000000000
			mcrl:snap
			c101959aef3a80024090f8ffff0f01ac020740200000c9bc99ef3afd020103012a137fc1200081053f00000040c80000
			40e800003f0000003f000000bf0000003f00000041bc0000bfa0000040e000003f000000c0300000405000004301c50b
			3600be00bd00bc00ba00b800b4000000340038003a003c003d000102013f00000040c8000040e800003f0000003f0000
			00bf0000003f000000c222000041440000404000003fc0000000000000c0c8000000000011444b200001010304050601
			020708
			mcrl:exit_car
			mcrl:reset_car
			mcrl:car_choice
			0b426f64795f4f6374616e650301
			mcrl:knock
			293ff80000000000004050000000000000c00a0000000000003fe00000000000003fe8000000000000bff00000000000
			00
			mcrl:rules
			020c6d63726c3a737465705f7570010f6d63726c3a6361725f64616d6167650001
			mcrl:fx_event
			29033ff80000000000004050000000000000c00a0000000000003f800000000000003f000000000000002b
			mcrl:match
			6e000000010000010000003a00010000010000003e000100000100000040800100000100000042000100000100000043
			800100000100000044800100000100000045400100000100000046000100000100000046c00100000100000047800100
			000100000048200201010f03010000
			mcrl:match_setup
			010040f8ffffff0f033fc0000040000000c040000000000000000000010200002904416c6578000101ffffffff0f0001
			mcrl:match_event
			000129ffffffff0f449a500004416c65780553746576653ff80000000000004050000000000000c00a000000000000
			""";

	private final Map<String, String> encodings = new LinkedHashMap<>();
	private final Set<Class<?>> covered = new HashSet<>();

	private <T extends CustomPacketPayload> void put(StreamCodec<RegistryFriendlyByteBuf, T> codec, T payload) {
		RegistryFriendlyByteBuf b = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		codec.encode(b, payload);
		byte[] out = new byte[b.readableBytes()];
		b.readBytes(out);
		encodings.put(payload.type().id().toString(), HexFormat.of().formatHex(out));
		covered.add(payload.getClass());
	}

	private static byte[] snapshot(Controls applied) {
		int[] o = {256, 64, -1008};
		Pose pose = new Pose(256.5, 70.25, -1000.75, 0.5f, 0.5f, -0.5f, 0.5f);
		float[] wheels = new float[12];
		for (int i = 0; i < 12; i++) {
			wheels[i] = (i - 6) * 0.25f;
		}
		SimFrame.CarSnap car = new SimFrame.CarSnap(42, pose, pose, 23.5, -1.25, 7, 0.5, -2.75, 3.25, 66.6f, 0x1C5,
				wheels, 0.375f, 0b1011, 0, 0f, 0f, CarFx.EMPTY, 1, 3, applied);
		SimFrame.BallSnap ball = new SimFrame.BallSnap(pose, pose, -40.5, 12.25, 3, 1.5, 0, -6.25, 17, 812.5f, 1, true,
				Hit.NONE);
		SnapshotCodec.Header h = new SnapshotCodec.Header(123_456_789L, o, SimFrame.FROZEN, 300, 2.5f)
				.withInput(201, 123_456_700L, -3).withBlobs(2, 1, 3);
		return SnapshotCodec.assemble(h, List.of(SnapshotCodec.carRecord(car, o, new byte[] {1, 2})),
				SnapshotCodec.ballRecord(ball, o, new byte[] {3}), new byte[] {4, 5, 6},
				new ArenaBlock(ArenaBlock.KIND_SOCCAR, new byte[] {7, 8}));
	}

	private static MatchBlock matchBlock() {
		boolean[] active = new boolean[MatchBlock.PADS];
		float[] cooldown = new float[MatchBlock.PADS];
		for (int i = 0; i < MatchBlock.PADS; i++) {
			active[i] = i % 3 != 0;
			cooldown[i] = active[i] ? 0f : i * 0.25f;
		}
		return new MatchBlock(active, cooldown, new MatchBlock.Header(2, true, 271, 3, 1, 0, false));
	}

	Map<String, String> encodings() {
		Controls c = new Controls(1f, -0.5f, 0.25f, 0f, -1f, true, false, true);
		Map<String, Boolean> rules = new LinkedHashMap<>();
		rules.put("mcrl:step_up", true);
		rules.put("mcrl:car_damage", false);
		put(HelloS2C.CODEC, new HelloS2C(NetProtocol.VERSION, 6, 0x0123_4567_89AB_CDEFL, 987_654L,
				SimClock.TICK_RATE, NetProtocol.SNAP_INTERVAL_TICKS, -0x1234_5678_9ABCL));
		put(HelloAckC2S.CODEC, new HelloAckC2S(NetProtocol.VERSION, 6, -5L, NetMode.PREDICT, 77L));
		put(InputC2S.CODEC, new InputC2S(1L << 40, 201, new Controls[] {c, Controls.NEUTRAL}));
		put(SnapshotS2C.CODEC, new SnapshotS2C(snapshot(c)));
		put(ExitCarC2S.CODEC, ExitCarC2S.INSTANCE);
		put(ResetCarC2S.CODEC, ResetCarC2S.INSTANCE);
		put(CarChoiceC2S.CODEC, new CarChoiceC2S("Body_Octane", 3, 1));
		put(KnockS2C.CODEC, new KnockS2C(41, 1.5, 64, -3.25, 0.5, 0.75, -1));
		put(RulesS2C.CODEC, new RulesS2C(rules, true));
		put(FxEventS2C.CODEC, new FxEventS2C(41, FxEventS2C.Kind.CAR_DEMOLISHED, 1.5, 64, -3.25, 1f, 0f, 0.5f, 0f, 43));
		put(MatchS2C.CODEC, new MatchS2C(matchBlock().encode()));
		put(MatchSetupS2C.CODEC, new MatchSetupS2C(MatchSize.DOUBLES, 0, 64, -8, new float[] {1.5f, 2f, -3f}, 1L,
				List.of(new SlotInfo(0, 0, 41, "Alex", false), new SlotInfo(1, 1, -1, "", true))));
		put(MatchEventS2C.CODEC, MatchEventS2C.goal(1, 41, 1234.5f, "Alex", "Steve", 1.5, 64, -3.25));
		return encodings;
	}

	private static Map<String, String> golden() {
		Map<String, String> m = new LinkedHashMap<>();
		String id = null;
		for (String token : GOLDEN.strip().split("\\s+")) {
			if (token.contains(":")) {
				id = token;
				m.put(id, "");
			} else if (id != null) {
				m.merge(id, token, String::concat);
			}
		}
		return m;
	}

	private static String format(Map<String, String> m) {
		return m.entrySet().stream().map(e -> e.getKey() + Stream.iterate(0, i -> i < e.getValue().length(), i -> i + 96)
				.map(i -> "\n" + e.getValue().substring(i, Math.min(i + 96, e.getValue().length())))
				.collect(Collectors.joining())).collect(Collectors.joining("\n"));
	}

	@Test
	void everyPayloadEncodesAsPinnedForThisProtocolVersion() {
		Map<String, String> actual = encodings();
		assertEquals(golden(), actual, "the wire changed: bump NetProtocol.VERSION, then pin these bytes:\n"
				+ format(actual));
		for (Class<?> holder : List.of(NetPayloads.class, McrlPayloads.class, MatchPayloads.class)) {
			for (Class<?> c : holder.getDeclaredClasses()) {
				if (CustomPacketPayload.class.isAssignableFrom(c)) {
					assertTrue(covered.contains(c), c.getSimpleName() + " has no pinned sample");
				}
			}
		}
	}
}
