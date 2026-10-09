package dev.skycraft.net;

import dev.skycraft.platform.NeoPackets;
import dev.skycraft.world.SkyCollision;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * LAN guests without their own host link get the host's terrain from the host's server: the
 * collision voxels they walk on and the triangles they see (SkyCollision's raw messages), near
 * where they are, as it arrives or changes. Runs on the server thread.
 */
public final class SkyTerrainSync {
	private static final int INTERVAL_TICKS = 5;
	/** Horizontal distance, in blocks, of terrain sent around a guest. */
	private static final int RADIUS = 96;
	/** Bytes sent to one guest per interval: about 2 MB/s while catching up on a LAN. */
	private static final int BUDGET = 512 * 1024;
	/** At most 74 bytes a block: keeps a region packet well under the 1 MB payload limit. */
	private static final int MAX_BLOCKS_PER_PACKET = 12000;
	/** 8 x 8 x 32 blocks fit in one packet even when every layer is sent as it is. */
	private static final int SLICE_HEIGHT = 32;

	private static final class Guest {
		int epoch = Integer.MIN_VALUE;
		final Map<Long, Long> regions = new HashMap<>();
		final Map<Long, Long> tris = new HashMap<>();
	}

	private static final Map<UUID, Guest> GUESTS = new HashMap<>();
	/** Guests with their own host game: their terrain comes from it, not from this server. */
	private static final java.util.Set<UUID> HOST_LINKED = new java.util.HashSet<>();

	public static void setHostLinked(ServerPlayer player, boolean linked) {
		if (linked) HOST_LINKED.add(player.getUUID());
		else HOST_LINKED.remove(player.getUUID());
	}

	private SkyTerrainSync() {
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % INTERVAL_TICKS != 0) {
			return;
		}
		GUESTS.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		HOST_LINKED.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
		int epoch = SkyCollision.epoch();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (SkyNet.isHost(player) || HOST_LINKED.contains(player.getUUID()) || !NeoPackets.canSend(player, SkyNet.TerrainRegion.TYPE)) {
				continue;
			}
			Guest guest = GUESTS.computeIfAbsent(player.getUUID(), id -> new Guest());
			if (guest.epoch != epoch) {
				guest.epoch = epoch;
				guest.regions.clear();
				guest.tris.clear();
				NeoPackets.send(player, new SkyNet.TerrainClear(epoch));
			}
			if (epoch == -1) {
				continue; // no host terrain yet
			}
			send(player, guest, epoch);
		}
	}

	private static void send(ServerPlayer player, Guest guest, int epoch) {
		int budget = BUDGET;
		double px = player.getX(), pz = player.getZ();
		for (var entry : SkyCollision.rawRegions().entrySet()) {
			SkyCollision.RawRegion region = entry.getValue();
			if (!near(px, pz, region.minX(), region.minZ(), region.maxX(), region.maxZ())
				|| guest.regions.getOrDefault(entry.getKey(), -1L) >= region.revision()) {
				continue;
			}
			int bytes = 40 + region.positions().length * 74;
			if (bytes > budget && budget < BUDGET) {
				return; // the rest goes next interval
			}
			budget -= bytes;
			sendRegion(player, epoch, region);
			guest.regions.put(entry.getKey(), region.revision());
		}
		for (var entry : SkyCollision.rawTris().entrySet()) {
			SkyCollision.RawTris tris = entry.getValue();
			int max = SkyCollision.REGION_SIZE - 1;
			if (!near(px, pz, tris.minX(), tris.minZ(), tris.minX() + max, tris.minZ() + max)
				|| guest.tris.getOrDefault(entry.getKey(), -1L) >= tris.revision()) {
				continue;
			}
			int bytes = 32 + tris.flags().length * 40;
			if (bytes > budget && budget < BUDGET) {
				return;
			}
			budget -= bytes;
			NeoPackets.send(player, new SkyNet.TerrainTris(epoch, tris.minX(), tris.minY(), tris.minZ(), tris.vertices(), tris.flags()));
			guest.tris.put(entry.getKey(), tris.revision());
		}
	}

	/** Splits a region too large for one packet (1 MB) into height slices; each replaces only its own. */
	private static void sendRegion(ServerPlayer player, int epoch, SkyCollision.RawRegion region) {
		if (region.positions().length <= MAX_BLOCKS_PER_PACKET) {
			NeoPackets.send(player, new SkyNet.TerrainRegion(epoch,
				new int[] {region.minX(), region.minY(), region.minZ(), region.maxX(), region.maxY(), region.maxZ()},
				region.positions(), region.bits()));
			return;
		}
		for (int y0 = region.minY(); y0 <= region.maxY(); y0 += SLICE_HEIGHT) {
			int y1 = Math.min(region.maxY(), y0 + SLICE_HEIGHT - 1);
			java.util.List<Integer> inside = new java.util.ArrayList<>();
			for (int i = 0; i < region.positions().length; i++) {
				int y = net.minecraft.core.BlockPos.getY(region.positions()[i]);
				if (y >= y0 && y <= y1) inside.add(i);
			}
			long[] positions = new long[inside.size()];
			long[] bits = new long[inside.size() * 8];
			for (int k = 0; k < inside.size(); k++) {
				positions[k] = region.positions()[inside.get(k)];
				System.arraycopy(region.bits(), inside.get(k) * 8, bits, k * 8, 8);
			}
			NeoPackets.send(player, new SkyNet.TerrainRegion(epoch,
				new int[] {region.minX(), y0, region.minZ(), region.maxX(), y1, region.maxZ()}, positions, bits));
		}
	}

	private static boolean near(double px, double pz, int minX, int minZ, int maxX, int maxZ) {
		double dx = Math.max(Math.max(minX - px, px - (maxX + 1)), 0);
		double dz = Math.max(Math.max(minZ - pz, pz - (maxZ + 1)), 0);
		return dx * dx + dz * dz <= RADIUS * RADIUS;
	}
}
