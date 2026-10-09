package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer: every player has their own Skyrim, talking to their own Minecraft client. The host's
 * Skyrim reaches the host's integrated server through shared memory; a guest's Skyrim reaches the
 * host's server through these packets instead.
 */
public final class SkyNet {
	private SkyNet() {
	}

	/** Guest -> server: the guest's Skyrim hit them (as proto::InputEvent kInHurt). */
	public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) implements CustomPacketPayload {
		public static final Type<Hurt> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "hurt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hurt> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hurt::kind,
			ByteBufCodecs.FLOAT, Hurt::skyrimDamage,
			ByteBufCodecs.INT, Hurt::attackerFormId,
			ByteBufCodecs.VAR_INT, Hurt::flags,
			Hurt::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> guest: the guest died in Minecraft, so their Skyrim player dies too. */
	public record Died(int attackerFormId) implements CustomPacketPayload {
		public static final Type<Died> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "died"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Died> CODEC = StreamCodec.composite(ByteBufCodecs.INT, Died::attackerFormId, Died::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: the player hit Skyrim's geometry in this cell (SkyDig.open). */
	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Client -> server: cells around a broken dug block that are inside Skyrim's geometry (SkyDig.reveal). */
	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Server -> LAN guest: the host's terrain changed completely ({@code epoch}); drop what the
	 * guest has. Guests without their own host link collide with and see the host's terrain.
	 */
	public record TerrainClear(int epoch) implements CustomPacketPayload {
		public static final Type<TerrainClear> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "terrain_clear"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TerrainClear> CODEC = StreamCodec.composite(ByteBufCodecs.INT, TerrainClear::epoch, TerrainClear::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> LAN guest: the 8x8x8 collision voxels of every block in a box (SkyCollision.RawRegion). */
	public record TerrainRegion(int epoch, int[] bounds, long[] positions, long[] bits) implements CustomPacketPayload {
		public static final Type<TerrainRegion> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "terrain_region"));
		// Per block: its position, then 2 bits per voxel layer (0 empty, 1 full, 2 as sent) and the
		// layers sent as they are. Solid ground below the surface is mostly full layers.
		public static final StreamCodec<RegistryFriendlyByteBuf, TerrainRegion> CODEC = StreamCodec.of((buf, region) -> {
			buf.writeInt(region.epoch());
			for (int value : region.bounds()) buf.writeInt(value);
			buf.writeVarInt(region.positions().length);
			for (int i = 0; i < region.positions().length; i++) {
				buf.writeLong(region.positions()[i]);
				int mask = 0;
				for (int y = 0; y < 8; y++) {
					long layer = region.bits()[i * 8 + y];
					mask |= (layer == 0L ? 0 : layer == -1L ? 1 : 2) << (y * 2);
				}
				buf.writeShort(mask);
				for (int y = 0; y < 8; y++) {
					if ((mask >>> (y * 2) & 3) == 2) buf.writeLong(region.bits()[i * 8 + y]);
				}
			}
		}, buf -> {
			int epoch = buf.readInt();
			int[] bounds = new int[6];
			for (int i = 0; i < 6; i++) bounds[i] = buf.readInt();
			int count = buf.readVarInt();
			if (count < 0 || count > buf.readableBytes() / 10) throw new IllegalArgumentException("terrain region too large");
			long[] positions = new long[count];
			long[] bits = new long[count * 8];
			for (int i = 0; i < count; i++) {
				positions[i] = buf.readLong();
				int mask = buf.readUnsignedShort();
				for (int y = 0; y < 8; y++) {
					int kind = mask >>> (y * 2) & 3;
					bits[i * 8 + y] = kind == 0 ? 0L : kind == 1 ? -1L : buf.readLong();
				}
			}
			return new TerrainRegion(epoch, bounds, positions, bits);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** Server -> LAN guest: the exact surface triangles of one terrain region (SkyCollision.RawTris). */
	public record TerrainTris(int epoch, int minX, int minY, int minZ, float[] vertices, int[] flags) implements CustomPacketPayload {
		public static final Type<TerrainTris> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "terrain_tris"));
		public static final StreamCodec<RegistryFriendlyByteBuf, TerrainTris> CODEC = StreamCodec.of((buf, tris) -> {
			buf.writeInt(tris.epoch());
			buf.writeInt(tris.minX());
			buf.writeInt(tris.minY());
			buf.writeInt(tris.minZ());
			buf.writeVarInt(tris.flags().length);
			for (float value : tris.vertices()) buf.writeFloat(value);
			for (int value : tris.flags()) buf.writeInt(value);
		}, buf -> {
			int epoch = buf.readInt(), minX = buf.readInt(), minY = buf.readInt(), minZ = buf.readInt();
			int count = buf.readVarInt();
			if (count < 0 || count > buf.readableBytes() / 40) throw new IllegalArgumentException("terrain triangles too large");
			float[] vertices = new float[count * 9];
			for (int i = 0; i < vertices.length; i++) vertices[i] = buf.readFloat();
			int[] flags = new int[count];
			for (int i = 0; i < count; i++) flags[i] = buf.readInt();
			return new TerrainTris(epoch, minX, minY, minZ, vertices, flags);
		});

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client -> server: this player has their own host game and terrain, so the host's terrain is
	 * not sent to them (SkyTerrainSync).
	 */
	public record HostLinked(boolean linked) implements CustomPacketPayload {
		public static final Type<HostLinked> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "host_linked"));
		public static final StreamCodec<RegistryFriendlyByteBuf, HostLinked> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, HostLinked::linked, HostLinked::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/**
	 * Client -> server: a guest's own host game moved them (fast travel, respawn, recovery). Players
	 * on a LAN world are trusted with their own position, as with the host on its own server.
	 */
	public record Teleport(double x, double y, double z, float yaw, float pitch) implements CustomPacketPayload {
		public static final Type<Teleport> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SkyCraft.MOD_ID, "teleport"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Teleport> CODEC = StreamCodec.composite(
			ByteBufCodecs.DOUBLE, Teleport::x,
			ByteBufCodecs.DOUBLE, Teleport::y,
			ByteBufCodecs.DOUBLE, Teleport::z,
			ByteBufCodecs.FLOAT, Teleport::yaw,
			ByteBufCodecs.FLOAT, Teleport::pitch,
			Teleport::new
		);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("12");
        registrar.playToServer(Hurt.TYPE, Hurt.CODEC, (payload, context) -> context.enqueueWork(() ->
            SkyCombat.hurtPlayer((ServerPlayer) context.player(), payload.kind(), Math.clamp(payload.skyrimDamage(), 0, 10000), payload.attackerFormId(), payload.flags())));
        registrar.playToServer(DigOpen.TYPE, DigOpen.CODEC, (payload, context) -> context.enqueueWork(() ->
            SkyDig.open((ServerPlayer) context.player(), payload.world(), payload.pos(), payload.material())));
        registrar.playToServer(DigReveal.TYPE, DigReveal.CODEC, (payload, context) -> context.enqueueWork(() ->
            SkyDig.reveal((ServerPlayer) context.player(), payload.world(), payload.cells(), payload.materials().stream().mapToInt(Integer::intValue).toArray())));
        registrar.playToServer(HostLinked.TYPE, HostLinked.CODEC, (payload, context) -> context.enqueueWork(() ->
            SkyTerrainSync.setHostLinked((ServerPlayer) context.player(), payload.linked())));
        registrar.playToServer(Teleport.TYPE, Teleport.CODEC, (payload, context) -> context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!Double.isFinite(payload.x()) || !Double.isFinite(payload.y()) || !Double.isFinite(payload.z())) return;
            player.stopFallFlying();
            player.teleportTo(player.serverLevel(), payload.x(), payload.y(), payload.z(), payload.yaw(), payload.pitch());
            player.fallDistance = 0.0F;
        }));
        registrar.playToClient(Died.TYPE, Died.CODEC, (payload, context) -> context.enqueueWork(() -> dev.skycraft.client.SkyClient.requestRecovery()));
        // A guest with its own host link already has terrain from it; only plain LAN guests take the host's.
        registrar.playToClient(TerrainClear.TYPE, TerrainClear.CODEC, (payload, context) -> {
            if (!dev.skycraft.link.SkyLink.active()) dev.skycraft.world.SkyCollision.acceptClear(payload.epoch());
        });
        registrar.playToClient(TerrainRegion.TYPE, TerrainRegion.CODEC, (payload, context) -> {
            int[] b = payload.bounds();
            if (!dev.skycraft.link.SkyLink.active())
                dev.skycraft.world.SkyCollision.acceptRegion(payload.epoch(), b[0], b[1], b[2], b[3], b[4], b[5], payload.positions(), payload.bits());
        });
        registrar.playToClient(TerrainTris.TYPE, TerrainTris.CODEC, (payload, context) -> {
            if (!dev.skycraft.link.SkyLink.active())
                dev.skycraft.world.SkyCollision.acceptTris(payload.epoch(), payload.minX(), payload.minY(), payload.minZ(), payload.vertices(), payload.flags());
        });
    }

	/** True if this player plays on this machine (their Skyrim is on the shared-memory link). */
	public static boolean isHost(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.isSingleplayerOwner(player.getGameProfile());
	}
}
