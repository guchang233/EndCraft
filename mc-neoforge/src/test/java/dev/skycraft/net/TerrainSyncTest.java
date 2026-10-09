package dev.skycraft.net;

import static org.junit.jupiter.api.Assertions.*;

import dev.skycraft.world.SkyCollision;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** LAN guests receive the host's terrain as packets and must end up with the same collision. */
class TerrainSyncTest {
	private static <T> T roundTrip(net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), null);
		codec.encode(buf, value);
		T decoded = codec.decode(buf);
		assertEquals(0, buf.readableBytes(), "every byte consumed");
		return decoded;
	}

	@Test void regionPacketKeepsEveryVoxelLayer() {
		long[] positions = {BlockPos.asLong(3, 64, -5), BlockPos.asLong(4, 64, -5), BlockPos.asLong(3, 65, -5)};
		long[] bits = new long[24];
		java.util.Arrays.fill(bits, 0, 8, -1L); // full block
		bits[8] = 0x00FF00FF00FF00FFL; // bottom layer only, a pattern
		bits[19] = 1L; // one sub-voxel in layer 3
		var sent = new SkyNet.TerrainRegion(42, new int[] {0, 64, -8, 7, 71, -1}, positions, bits);
		var got = roundTrip(SkyNet.TerrainRegion.CODEC, sent);
		assertEquals(42, got.epoch());
		assertArrayEquals(sent.bounds(), got.bounds());
		assertArrayEquals(positions, got.positions());
		assertArrayEquals(bits, got.bits());
	}

	@Test void trianglePacketKeepsVerticesAndFlags() {
		float[] vertices = {0, 64, 0, 1, 64, 0, 0, 64.5F, 1, -3.25F, 70, 2, 4, 70, 2, 4, 71, -1};
		int[] flags = {dev.skycraft.link.Proto.TRI_TERRAIN, dev.skycraft.link.Proto.TRI_DIGGABLE | (3 << dev.skycraft.link.Proto.TRI_MATERIAL_SHIFT)};
		var got = roundTrip(SkyNet.TerrainTris.CODEC, new SkyNet.TerrainTris(7, 0, 64, 0, vertices, flags));
		assertEquals(7, got.epoch());
		assertArrayEquals(vertices, got.vertices());
		assertArrayEquals(flags, got.flags());
	}

	@Test void guestCollidesWithReceivedTerrain() {
		SkyCollision.acceptClear(-1);
		long[] positions = {BlockPos.asLong(1, 70, 1)};
		long[] bits = new long[8];
		java.util.Arrays.fill(bits, -1L);
		SkyCollision.acceptRegion(99, 0, 64, 0, 7, 71, 7, positions, bits);
		assertNotNull(SkyCollision.shapeAt(1, 70, 1), "received voxel collides");
		assertNull(SkyCollision.shapeAt(2, 70, 1), "empty cells stay empty");
		assertTrue(SkyCollision.active());

		// A later message for the same box replaces it: the block is gone.
		SkyCollision.acceptRegion(99, 0, 64, 0, 7, 71, 7, new long[0], new long[0]);
		assertNull(SkyCollision.shapeAt(1, 70, 1));

		SkyCollision.acceptTris(99, 0, 64, 0, new float[] {0, 70, 0, 4, 70, 0, 0, 70, 4}, new int[] {dev.skycraft.link.Proto.TRI_TERRAIN});
		var near = new java.util.ArrayList<dev.skycraft.world.SkyTri>();
		SkyCollision.trianglesNear(new AABB(0, 69, 0, 2, 71, 2), near);
		assertEquals(1, near.size(), "received triangle is walkable terrain");

		// Messages from an older host world are ignored; a clear starts the next one.
		SkyCollision.acceptRegion(98, 0, 64, 0, 7, 71, 7, positions, bits);
		assertNull(SkyCollision.shapeAt(1, 70, 1));
		SkyCollision.acceptClear(-1);
		assertFalse(SkyCollision.active());
		assertTrue(SkyCollision.rawTris().isEmpty());
	}
}
