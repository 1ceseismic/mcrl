package dev.mcrl.client.link;

import org.jspecify.annotations.Nullable;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;

import dev.mcrl.client.camera.CameraHost;
import dev.mcrl.client.config.McrlConfig;
import dev.mcrl.client.input.InputMapper;
import dev.mcrl.entity.BallEntity;
import dev.mcrl.entity.CarEntity;
import dev.mcrl.math.Pose;
import dev.mcrl.net.McrlPayloads;
import dev.mcrl.sim.InputFrame;
import dev.mcrl.sim.PauseSource;
import dev.mcrl.sim.SimClock;
import dev.mcrl.sim.SimFrame;
import dev.mcrl.sim.SimHost;
import dev.mcrl.sim.SimHosts;

public final class LocalSimLink {
	private static final long AWAIT_NANOS = 3_000_000L;

	private static final InputMapper MAPPER = new InputMapper();
	private static int seq;
	private static @Nullable SimHost drivenHost;
	private static int drivenCar;
	private static @Nullable SimHost pausedHost;
	private static float throttle;
	private static int jumpPresses;
	private static @Nullable BallEntity ballEntity;
	private static long frameNanos;
	private static @Nullable SimHost drawnFrom;

	private LocalSimLink() {
	}

	public static void register() {
		ClientEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof BallEntity b) {
				ballEntity = b;
			}
		});
		ClientEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity == ballEntity) {
				ballEntity = null;
			}
		});
	}

	public static boolean ridingCar(Minecraft mc) {
		LocalPlayer p = mc.player;
		return p != null && p.getVehicle() instanceof CarEntity;
	}

	public static float throttle() {
		return throttle;
	}

	public static int jumpPresses() {
		return jumpPresses;
	}

	public static boolean pausing(Minecraft mc) {
		// MC sets isPaused after the frame; check it now
		IntegratedServer server = mc.getSingleplayerServer();
		return mc.isPaused() || server != null && mc.gui.isPausing() && !server.isPublished();
	}

	public static long frameNanos() {
		return frameNanos;
	}

	public static @Nullable CarEntity drivenCar(@Nullable LocalPlayer p) {
		return p != null && p.getVehicle() instanceof CarEntity car && car.getControllingPassenger() == p ? car : null;
	}

	static void setFrameNanos(long nanos) {
		frameNanos = nanos;
	}

	static void sendExitIfPressed(Minecraft mc, boolean exitPressed) {
		if (exitPressed && ridingCar(mc) && mc.gui.screen() == null
				&& ClientPlayNetworking.canSend(McrlPayloads.ExitCarC2S.TYPE)) {
			ClientPlayNetworking.send(McrlPayloads.ExitCarC2S.INSTANCE);
		}
	}

	static void abandon() {
		ClientPoses.clear();
		stopDriving();
	}

	static InputFrame driverInput(Minecraft mc, LinkInput in, @Nullable CarEntity car) {
		boolean screenOpen = mc.gui.screen() != null;
		InputFrame f = MAPPER.map(in.raw(screenOpen, car != null), ++seq, McrlConfig.get().controller);
		if (car == null) {
			throttle = 0f;
		} else if (!pausing(mc)) {
			throttle = InputFrame.axis(f.throttle());
		}
		CameraHost.controls(in.pad(), in.k().ballCamPressed(), in.k().ballCamHeld(),
				McrlConfig.get().controller.stickDeadzone(), car != null && !screenOpen);
		if (car != null) {
			if ((f.edges() & InputFrame.JUMP) != 0) {
				jumpPresses++;
			}
			if (!screenOpen && !in.exit() && in.reset() && ClientPlayNetworking.canSend(McrlPayloads.ResetCarC2S.TYPE)) {
				ClientPlayNetworking.send(McrlPayloads.ResetCarC2S.INSTANCE);
			}
		}
		return f;
	}

	static void frame(Minecraft mc, LinkInput in) {
		sendExitIfPressed(mc, in.exit());
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		SimHost host = player != null && level != null && mc.getSingleplayerServer() != null
				? SimHosts.get(level.dimension()) : null;
		if (host == null || host.isClosed()) {
			frameNanos = System.nanoTime();
			leave();
			return;
		}
		if (pausedHost != host) {
			unpause();
		}

		CarEntity car = drivenCar(player);
		if (drivenHost != null && (car == null || drivenHost != host || drivenCar != car.carId())) {
			stopDriving();
		}
		InputFrame f = driverInput(mc, in, car);
		if (car != null) {
			host.submitInput(car.carId(), f);
			drivenHost = host;
			drivenCar = car.carId();
		}

		SimFrame frame;
		if (mc.isPaused()) {
			if (pausedHost != host) {
				host.setPaused(PauseSource.CLIENT, true);
				pausedHost = host;
			}
			frame = host.latest();
		} else {
			unpause();
			frameNanos = System.nanoTime();
			if (host.mode() == SimClock.Mode.REALTIME) {
				frame = host.latest();
			} else {
				long paceSeq = host.pace(frameNanos);
				frame = host.awaitPace(paceSeq, AWAIT_NANOS);
			}
		}
		if (host != drawnFrom) {
			drawnFrom = host;
			ClientPoses.newSource();
		}
		ClientPoses.update(frame, host, frameNanos, null);
		moveEntities(level, frame);
	}

	private static void leave() {
		release();
		ClientPoses.clear();
		CameraHost.reset();
	}

	static void release() {
		if (drivenHost != null) {
			stopDriving();
		}
		unpause();
	}

	private static void unpause() {
		if (pausedHost != null) {
			pausedHost.setPaused(PauseSource.CLIENT, false);
			pausedHost = null;
		}
	}

	private static void stopDriving() {
		throttle = 0f;
		SimHost h = drivenHost;
		if (h != null) {
			h.submitInput(drivenCar, InputFrame.NEUTRAL);
		}
		drivenHost = null;
	}

	static void moveEntities(ClientLevel level, SimFrame frame) {
		for (SimFrame.CarSnap c : frame.cars()) {
			Pose p = ClientPoses.carRaw(c.carId());
			if (p != null && level.getEntity(c.carId()) instanceof CarEntity car && !car.isRemoved()) {
				car.setClientPose(p);
			}
		}
		Pose b = ClientPoses.ball();
		BallEntity ball = ballEntity;
		if (b != null && ball != null && !ball.isRemoved() && ball.level() == level) {
			ball.setPos(b.x(), b.y(), b.z());
		}
	}
}
