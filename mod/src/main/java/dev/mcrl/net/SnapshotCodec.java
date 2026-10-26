package dev.mcrl.net;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;

import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;

import dev.mcrl.math.Pose;
import dev.mcrl.net.Snapshot.Ball;
import dev.mcrl.net.Snapshot.BallHead;
import dev.mcrl.net.Snapshot.Car;
import dev.mcrl.net.Snapshot.CarHead;
import dev.mcrl.sim.Controls;
import dev.mcrl.sim.SimFrame;

public final class SnapshotCodec {
	public static final int CAR_RECORD_HEAD_BYTES = 1 + 6 + 82;
	public static final int BALL_RECORD_BYTES = 62;
	static final int MAX_CARS = 4096;
	static final int BLOBS = 1;
	static final int INPUT = 2;
	static final int RATE = 4;

	private SnapshotCodec() {
	}

	public record Header(long tick, int ox, int oy, int oz, int frameFlags, int hostId, float rate, int inputEpoch,
			long ackTick, int slack, boolean hasBlobs, int carBlobSize, int ballBlobSize, int arenaBlobSize) {
		public Header(long tick, int[] origin, int frameFlags, int hostId, float rate) {
			this(tick, origin[0], origin[1], origin[2], frameFlags, hostId, rate, -1, 0, 0, false, 0, 0, 0);
		}

		public Header withInput(int epoch, long ack, int slk) {
			return new Header(tick, ox, oy, oz, frameFlags, hostId, rate, epoch & 0xFF, ack, slk, hasBlobs, carBlobSize,
					ballBlobSize, arenaBlobSize);
		}

		public Header withBlobs(int carBlob, int ballBlob, int arenaBlob) {
			return new Header(tick, ox, oy, oz, frameFlags, hostId, rate, inputEpoch, ackTick, slack, true, carBlob,
					ballBlob, arenaBlob);
		}
	}

	public static byte[] carRecord(SimFrame.CarSnap s, int[] origin, byte @Nullable [] blob) {
		ByteBuf b = Unpooled.buffer(5 + CAR_RECORD_HEAD_BYTES + (blob == null ? 0 : blob.length));
		VarInt.write(b, s.carId());
		b.writeByte((s.team() & 0xF) << 4 | (s.preset() & 0xF));
		NetPayloads.writeControls(b, s.applied());
		writeMotion(b, s.cur(), origin, s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
		b.writeByte(Math.round(Math.max(0f, Math.min(255f, s.boost()))));
		b.writeShort(s.carFlags());
		b.writeByte(s.wheelContact());
		b.writeShort(Float.floatToFloat16(s.steerAngle()));
		for (float w : s.wheelLocal()) {
			b.writeShort(Float.floatToFloat16(w));
		}
		if (blob != null) {
			b.writeBytes(blob);
		}
		return bytes(b);
	}

	public static byte[] ballRecord(SimFrame.BallSnap s, int[] origin, byte @Nullable [] blob) {
		ByteBuf b = Unpooled.buffer(BALL_RECORD_BYTES + (blob == null ? 0 : blob.length));
		writeMotion(b, s.cur(), origin, s.vx(), s.vy(), s.vz(), s.wx(), s.wy(), s.wz());
		b.writeInt(s.bounces());
		b.writeFloat(s.bounceSpeed());
		b.writeByte(Math.max(-128, Math.min(127, s.lastTouchTeam())));
		b.writeByte(s.worldContact() ? 1 : 0);
		if (blob != null) {
			b.writeBytes(blob);
		}
		return bytes(b);
	}

	public static byte[] assemble(Header h, List<byte[]> cars, byte @Nullable [] ball, byte @Nullable [] arenaBlob,
			ArenaBlock arena) {
		int arenaBlobSize = h.hasBlobs() ? h.arenaBlobSize() : 0;
		if ((arenaBlob == null ? 0 : arenaBlob.length) != arenaBlobSize) {
			throw new IllegalArgumentException("arena blob of " + (arenaBlob == null ? 0 : arenaBlob.length)
					+ " bytes, the header says " + arenaBlobSize);
		}
		int size = 64 + arena.length() + arenaBlobSize + (ball == null ? 0 : ball.length);
		for (byte[] c : cars) {
			size += c.length;
		}
		ByteBuf b = Unpooled.buffer(size);
		VarLong.write(b, h.tick());
		VarInt.write(b, h.ox());
		VarInt.write(b, h.oy());
		VarInt.write(b, h.oz());
		VarInt.write(b, h.frameFlags());
		VarInt.write(b, h.hostId());
		boolean input = h.inputEpoch() >= 0;
		boolean rate = h.rate() != 1f;
		b.writeByte((h.hasBlobs() ? BLOBS : 0) | (input ? INPUT : 0) | (rate ? RATE : 0));
		if (rate) {
			b.writeFloat(h.rate());
		}
		if (input) {
			b.writeByte(h.inputEpoch());
			VarLong.write(b, h.ackTick());
			b.writeByte(Math.max(-128, Math.min(127, h.slack())));
		}
		if (h.hasBlobs()) {
			VarInt.write(b, h.carBlobSize());
			VarInt.write(b, h.ballBlobSize());
			VarInt.write(b, arenaBlobSize);
		}
		VarInt.write(b, cars.size());
		for (byte[] c : cars) {
			b.writeBytes(c);
		}
		b.writeByte(ball == null ? 0 : 1);
		if (ball != null) {
			b.writeBytes(ball);
		}
		if (arenaBlob != null) {
			b.writeBytes(arenaBlob);
		}
		b.writeByte(arena.kind());
		VarInt.write(b, arena.length());
		b.writeBytes(arena.body());
		return bytes(b);
	}

	public static Snapshot decode(ByteBuf b) {
		try {
			long tick = VarLong.read(b);
			int ox = VarInt.read(b), oy = VarInt.read(b), oz = VarInt.read(b);
			int frameFlags = VarInt.read(b);
			int hostId = VarInt.read(b);
			int bits = b.readUnsignedByte();
			float rate = (bits & RATE) != 0 ? b.readFloat() : 1f;
			int inputEpoch = -1;
			long ackTick = 0;
			int slack = 0;
			if ((bits & INPUT) != 0) {
				inputEpoch = b.readUnsignedByte();
				ackTick = VarLong.read(b);
				slack = b.readByte();
			}
			int carBlob = 0, ballBlob = 0, arenaBlobSize = 0;
			if ((bits & BLOBS) != 0) {
				carBlob = size(VarInt.read(b), b, "car blob");
				ballBlob = size(VarInt.read(b), b, "ball blob");
				arenaBlobSize = size(VarInt.read(b), b, "arena blob");
			}
			int n = VarInt.read(b);
			if (n < 0 || n > MAX_CARS) {
				throw new DecoderException("snapshot with " + n + " cars");
			}
			List<Car> cars = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				int id = VarInt.read(b);
				if (id < 0) {
					throw new DecoderException("car id " + id);
				}
				int tp = b.readUnsignedByte();
				Controls applied = NetPayloads.readControls(b);
				CarHead head = readCarHead(b);
				cars.add(new Car(id, tp >> 4, tp & 0xF, applied, head, carBlob > 0 ? read(b, carBlob) : null));
			}
			Ball ball = null;
			if (b.readUnsignedByte() != 0) {
				BallHead head = readBallHead(b);
				ball = new Ball(head, ballBlob > 0 ? read(b, ballBlob) : null);
			}
			byte[] arenaBlob = arenaBlobSize > 0 ? read(b, arenaBlobSize) : null;
			int kind = b.readUnsignedByte();
			ArenaBlock arena = new ArenaBlock(kind, read(b, size(VarInt.read(b), b, "arena block")));
			return new Snapshot(tick, ox, oy, oz, frameFlags, hostId, rate, inputEpoch, ackTick, slack, cars, ball,
					arenaBlob, arena);
		} catch (IndexOutOfBoundsException e) {
			throw new DecoderException("snapshot ends early", e);
		}
	}

	private static void writeMotion(ByteBuf b, Pose p, int[] origin, double vx, double vy, double vz, double wx,
			double wy, double wz) {
		b.writeFloat((float) (p.x() - origin[0]));
		b.writeFloat((float) (p.y() - origin[1]));
		b.writeFloat((float) (p.z() - origin[2]));
		b.writeFloat(p.qw());
		b.writeFloat(p.qx());
		b.writeFloat(p.qy());
		b.writeFloat(p.qz());
		b.writeFloat((float) vx);
		b.writeFloat((float) vy);
		b.writeFloat((float) vz);
		b.writeFloat((float) wx);
		b.writeFloat((float) wy);
		b.writeFloat((float) wz);
	}

	private static CarHead readCarHead(ByteBuf b) {
		float px = b.readFloat(), py = b.readFloat(), pz = b.readFloat();
		float qw = b.readFloat(), qx = b.readFloat(), qy = b.readFloat(), qz = b.readFloat();
		float vx = b.readFloat(), vy = b.readFloat(), vz = b.readFloat();
		float wx = b.readFloat(), wy = b.readFloat(), wz = b.readFloat();
		int boost = b.readUnsignedByte();
		int flags = b.readUnsignedShort();
		int wheelContact = b.readUnsignedByte();
		float steer = Float.float16ToFloat(b.readShort());
		float[] wheels = new float[12];
		for (int i = 0; i < 12; i++) {
			wheels[i] = Float.float16ToFloat(b.readShort());
		}
		return new CarHead(px, py, pz, qw, qx, qy, qz, vx, vy, vz, wx, wy, wz, boost, flags, wheelContact, steer, wheels);
	}

	private static BallHead readBallHead(ByteBuf b) {
		float px = b.readFloat(), py = b.readFloat(), pz = b.readFloat();
		float qw = b.readFloat(), qx = b.readFloat(), qy = b.readFloat(), qz = b.readFloat();
		float vx = b.readFloat(), vy = b.readFloat(), vz = b.readFloat();
		float wx = b.readFloat(), wy = b.readFloat(), wz = b.readFloat();
		int bounces = b.readInt();
		float bounceSpeed = b.readFloat();
		int lastTouchTeam = b.readByte();
		boolean worldContact = b.readUnsignedByte() != 0;
		return new BallHead(px, py, pz, qw, qx, qy, qz, vx, vy, vz, wx, wy, wz, bounces, bounceSpeed, lastTouchTeam,
				worldContact);
	}

	private static int size(int n, ByteBuf b, String what) {
		if (n < 0 || n > b.readableBytes()) {
			throw new DecoderException(what + " of " + n + " bytes, " + b.readableBytes() + " left");
		}
		return n;
	}

	private static byte[] read(ByteBuf b, int n) {
		byte[] out = new byte[size(n, b, "field")];
		b.readBytes(out);
		return out;
	}

	private static byte[] bytes(ByteBuf b) {
		byte[] out = new byte[b.readableBytes()];
		b.readBytes(out);
		return out;
	}
}
