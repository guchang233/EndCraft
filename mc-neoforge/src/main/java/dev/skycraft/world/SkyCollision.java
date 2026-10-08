package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.MemorySegment;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Skyrim's world geometry as Minecraft sees it: an 8x8x8 sub-voxel collision shape per block
 * position, streamed from the SKSE plugin. These are not blocks; they are merged into block
 * collision queries (see BlockCollisionsMixin) so vanilla movement code collides with them.
 */
public final class SkyCollision {
	/** Skyrim regions are streamed as cubes of this many blocks. Must match the SKSE side. */
	public static final int REGION_SIZE = 8;

	private static final ConcurrentHashMap<Long, VoxelShape> SHAPES = new ConcurrentHashMap<>();
	// Per block: sub-voxel count (bits 0-9), any in the lower half (bit 10), any in the upper half (bit 11).
	private static final ConcurrentHashMap<Long, Integer> FILL = new ConcurrentHashMap<>();
	private static final int FILL_LOWER = 1 << 10;
	private static final int FILL_UPPER = 1 << 11;
	private static final int FILL_TOP_SHIFT = 12; // highest occupied of the 8 voxel layers (3 bits)
	private static final ConcurrentHashMap<Long, SkyTri[]> TRIS = new ConcurrentHashMap<>();
	// Diggable surfaces as they were before blocks were dug out of them (Proto.TRI_GHOST).
	private static final ConcurrentHashMap<Long, SkyTri[]> GHOSTS = new ConcurrentHashMap<>();
	// A hash of each region's triangles as last received, and the regions whose triangles changed
	// since the client last looked (the walls of dug holes are drawn from them).
	private static final ConcurrentHashMap<Long, Long> TRI_HASH = new ConcurrentHashMap<>();
	private static final java.util.concurrent.ConcurrentLinkedQueue<Long> CHANGED = new java.util.concurrent.ConcurrentLinkedQueue<>();

	/** Regions (min corner, as BlockPos longs) whose triangles changed since the last call. */
	public static void takeChangedRegions(java.util.function.LongConsumer out) {
		Long key;
		while ((key = CHANGED.poll()) != null) {
			out.accept(key);
		}
	}
	private static volatile java.util.function.Predicate<net.minecraft.world.entity.Entity> smoothCollider = e -> false;
	private static final Set<Long> KNOWN_REGIONS = ConcurrentHashMap.newKeySet();
	private static volatile int epoch = -1;
	// Physics engines with their own static terrain (Sable) mirror the voxels: bounds of a changed
	// region as {minX, minY, minZ, maxX, maxY, maxZ}, or null when everything was cleared.
	private static final java.util.List<java.util.function.Consumer<int[]>> VOXEL_LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();
	private static Thread consumer;

	private SkyCollision() {
	}

	public static @Nullable VoxelShape shapeAt(BlockPos pos) {
		return SHAPES.isEmpty() ? null : SHAPES.get(pos.asLong());
	}

	public static @Nullable VoxelShape shapeAt(int x, int y, int z) {
		return SHAPES.isEmpty() ? null : SHAPES.get(BlockPos.asLong(x, y, z));
	}

	/** Called on the collision thread after the stored voxels change; see VOXEL_LISTENERS. */
	public static void addVoxelListener(java.util.function.Consumer<int[]> listener) {
		VOXEL_LISTENERS.add(listener);
	}

	/** Entities (the local player) that collide with Skyrim's exact triangles instead of its voxels. */
	public static void setSmoothCollider(java.util.function.Predicate<net.minecraft.world.entity.Entity> predicate) {
		smoothCollider = predicate;
	}

	public static boolean usesSmoothCollider(net.minecraft.world.entity.@Nullable Entity entity) {
		return entity != null && smoothCollider.test(entity);
	}

	/** Adds every Skyrim triangle whose bounds overlap {@code box}. */
	public static void trianglesNear(net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		if (TRIS.isEmpty()) {
			return;
		}
		int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					SkyTri[] tris = TRIS.get(regionKey(rx, ry, rz));
					if (tris == null) {
						continue;
					}
					for (SkyTri t : tris) {
						if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
							out.add(t);
						}
					}
				}
			}
		}
	}

	/**
	 * Every Skyrim surface whose bounds overlap {@code box} as it was before anything was dug out
	 * of it: what's behind these is inside Skyrim's geometry (SkyDig).
	 */
	public static void originalSurfacesNear(net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		trianglesNear(box, out);
		near(GHOSTS, box, out);
	}

	private static void near(ConcurrentHashMap<Long, SkyTri[]> store, net.minecraft.world.phys.AABB box, java.util.List<SkyTri> out) {
		if (store.isEmpty()) {
			return;
		}
		int rx0 = Math.floorDiv((int) Math.floor(box.minX), REGION_SIZE), rx1 = Math.floorDiv((int) Math.floor(box.maxX), REGION_SIZE);
		int ry0 = Math.floorDiv((int) Math.floor(box.minY), REGION_SIZE), ry1 = Math.floorDiv((int) Math.floor(box.maxY), REGION_SIZE);
		int rz0 = Math.floorDiv((int) Math.floor(box.minZ), REGION_SIZE), rz1 = Math.floorDiv((int) Math.floor(box.maxZ), REGION_SIZE);
		for (int rx = rx0; rx <= rx1; rx++) {
			for (int ry = ry0; ry <= ry1; ry++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					SkyTri[] tris = store.get(regionKey(rx, ry, rz));
					if (tris == null) {
						continue;
					}
					for (SkyTri t : tris) {
						if (t.maxX >= box.minX && t.minX <= box.maxX && t.maxY >= box.minY && t.minY <= box.maxY && t.maxZ >= box.minZ && t.minZ <= box.maxZ) {
							out.add(t);
						}
					}
				}
			}
		}
	}

	/** True once Skyrim has sent the region containing this block (even if it was empty). */
	public static boolean isKnown(int x, int y, int z) {
		return KNOWN_REGIONS.contains(regionKey(Math.floorDiv(x, REGION_SIZE), Math.floorDiv(y, REGION_SIZE), Math.floorDiv(z, REGION_SIZE)));
	}

	/** True if any Skyrim geometry exists in the 3x3 column below (x, y, z), down to {@code depth} blocks. */
	public static boolean hasSolidBelow(int x, int y, int z, int depth) {
		for (int dy = 0; dy <= depth; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (SHAPES.containsKey(BlockPos.asLong(x + dx, y - dy, z + dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Fraction (0..1) of this block's volume that is Skyrim geometry. */
	public static float solidFraction(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0.0F : (fill & 0x3FF) / 512.0F;
	}

	/** True if any Skyrim geometry is in this cell. */
	public static boolean hasGeometry(BlockPos pos) {
		return !FILL.isEmpty() && FILL.containsKey(pos.asLong());
	}

	/**
	 * How high (0..1) Skyrim geometry reaches in this cell: the top of its highest part. Terrain
	 * arrives as a thin surface, so what lies below that surface counts as ground too.
	 */
	public static float groundTop(BlockPos pos) {
		Integer fill = FILL.isEmpty() ? null : FILL.get(pos.asLong());
		return fill == null ? 0.0F : (((fill >> FILL_TOP_SHIFT) & 7) + 1) / 8.0F;
	}

	/** True if Skyrim ground holds up whatever is in this cell (terrain in its lower half or the top of the cell below). */
	public static boolean supportsFromBelow(BlockPos pos) {
		if (FILL.isEmpty()) {
			return false;
		}
		Integer here = FILL.get(pos.asLong());
		if (here != null && (here & FILL_LOWER) != 0) {
			return true;
		}
		Integer below = FILL.get(BlockPos.asLong(pos.getX(), pos.getY() - 1, pos.getZ()));
		return below != null && (below & FILL_UPPER) != 0;
	}

	public static int blockCount() {
		return SHAPES.size();
	}

	public static int regionCount() {
		return KNOWN_REGIONS.size();
	}

	/** Skyrim is describing its world around the player (false in a plain Minecraft world). */
	public static boolean active() {
		return !KNOWN_REGIONS.isEmpty();
	}

	private static long regionKey(int rx, int ry, int rz) {
		return BlockPos.asLong(rx, ry, rz);
	}

	public static synchronized void startConsumer() {
		if (consumer != null) {
			return;
		}
		consumer = new Thread(SkyCollision::consumeLoop, "SkyCraft collision");
		consumer.setDaemon(true);
		consumer.start();
	}

	private static void consumeLoop() {
		while (true) {
			try {
				if (!drainOnce()) {
					Thread.sleep(2);
				}
			} catch (InterruptedException e) {
				return;
			} catch (Throwable t) {
				SkyCraft.LOG.error("SkyCraft: collision consumer error", t);
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					return;
				}
			}
		}
	}

	/** Processes all pending collision messages. Returns true if anything was consumed. */
	private static boolean drainOnce() {
		return SkyLink.withSegment(SkyCollision::drainMapped);
	}
	private static boolean drainMapped(MemorySegment s) {
		if (s == null) {
			return false;
		}
		long head = SkyLink.collisionHead();
		long tail = SkyLink.collisionTail();
		if (tail >= head) {
			return false;
		}
		long data = OFF_COLLISION_RING + CR_DATA;
		while (tail < head) {
			long pos = tail % CR_DATA_BYTES;
			int type = s.get(JAVA_INT, data + pos);
			int payloadBytes = s.get(JAVA_INT, data + pos + 4);
			if (type == COL_PAD) {
				tail += CR_DATA_BYTES - pos;
				continue;
			}
			long payload = data + pos + 8;
			switch (type) {
				case COL_CLEAR -> clear(s.get(JAVA_INT, payload));
				case COL_REGION -> readRegion(s, payload);
				case COL_TRIS -> readTris(s, payload);
				default -> SkyCraft.LOG.warn("SkyCraft: unknown collision message {}", type);
			}
			tail += align8(8 + payloadBytes);
		}
		SkyLink.setCollisionTail(tail);
		return true;
	}

	private static long align8(long v) {
		return (v + 7) & ~7L;
	}

	/** A freshly started client joins whatever collision epoch Skyrim is already on. */
	private static void adoptEpochIfFresh(int msgEpoch) {
		if (epoch == -1) {
			epoch = msgEpoch;
			SkyCraft.LOG.info("SkyCraft: joined collision epoch {} already in progress", msgEpoch);
		}
	}

	private static void clear(int newEpoch) {
		SHAPES.clear();
		FILL.clear();
		TRIS.clear();
		GHOSTS.clear();
		TRI_HASH.clear();
		KNOWN_REGIONS.clear();
		epoch = newEpoch;
		for (var listener : VOXEL_LISTENERS) listener.accept(null);
		SkyCraft.LOG.info("SkyCraft: collision cleared (epoch {})", newEpoch);
	}

	private static void readRegion(MemorySegment s, long p) {
		int minX = s.get(JAVA_INT, p);
		int minY = s.get(JAVA_INT, p + 4);
		int minZ = s.get(JAVA_INT, p + 8);
		int maxX = s.get(JAVA_INT, p + 12);
		int maxY = s.get(JAVA_INT, p + 16);
		int maxZ = s.get(JAVA_INT, p + 20);
		int msgEpoch = s.get(JAVA_INT, p + 24);
		int count = s.get(JAVA_INT, p + 28);
		adoptEpochIfFresh(msgEpoch);
		if (msgEpoch != epoch) {
			return; // stale region from before a world change
		}

		// Build the new shapes first so readers never see a half-empty region.
		java.util.HashMap<Long, VoxelShape> fresh = new java.util.HashMap<>(count * 2);
		java.util.HashMap<Long, Integer> freshFill = new java.util.HashMap<>(count * 2);
		long e = p + COL_REGION_HEADER_BYTES;
		for (int i = 0; i < count; i++, e += COL_BLOCK_BYTES) {
			int x = s.get(JAVA_INT, e);
			int y = s.get(JAVA_INT, e + 4);
			int z = s.get(JAVA_INT, e + 8);
			VoxelShape shape = buildShape(s, e + 16);
			if (shape != null) {
				long key = BlockPos.asLong(x, y, z);
				fresh.put(key, shape);
				freshFill.put(key, fillInfo(s, e + 16));
			}
		}

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					long key = BlockPos.asLong(x, y, z);
					VoxelShape shape = fresh.get(key);
					if (shape != null) {
						SHAPES.put(key, shape);
						FILL.put(key, freshFill.get(key));
					} else {
						SHAPES.remove(key);
						FILL.remove(key);
					}
				}
			}
		}

		for (int rx = Math.floorDiv(minX, REGION_SIZE); rx <= Math.floorDiv(maxX, REGION_SIZE); rx++) {
			for (int ry = Math.floorDiv(minY, REGION_SIZE); ry <= Math.floorDiv(maxY, REGION_SIZE); ry++) {
				for (int rz = Math.floorDiv(minZ, REGION_SIZE); rz <= Math.floorDiv(maxZ, REGION_SIZE); rz++) {
					KNOWN_REGIONS.add(regionKey(rx, ry, rz));
				}
			}
		}
		for (var listener : VOXEL_LISTENERS) listener.accept(new int[] {minX, minY, minZ, maxX, maxY, maxZ});
	}

	private static void readTris(MemorySegment s, long p) {
		int minX = s.get(JAVA_INT, p);
		int minY = s.get(JAVA_INT, p + 4);
		int minZ = s.get(JAVA_INT, p + 8);
		int msgEpoch = s.get(JAVA_INT, p + 24);
		int count = s.get(JAVA_INT, p + 28);
		adoptEpochIfFresh(msgEpoch);
		if (msgEpoch != epoch) {
			return;
		}
		SkyTri[] tris = new SkyTri[count];
		java.util.List<SkyTri> ghosts = new java.util.ArrayList<>();
		float[] v = new float[9];
		int kept = 0;
		long hash = count;
		long e = p + COL_REGION_HEADER_BYTES;
		for (int i = 0; i < count; i++, e += COL_TRI_BYTES) {
			for (int k = 0; k < 9; k++) {
				v[k] = s.get(JAVA_FLOAT, e + k * 4L);
				hash = hash * 31 + Float.floatToRawIntBits(v[k]);
			}
			int flags = s.get(JAVA_INT, e + 36);
			hash = hash * 31 + flags;
			SkyTri t = new SkyTri(v, 0, flags);
			if (t.degenerate()) {
				continue;
			}
			if ((flags & TRI_GHOST) != 0) {
				ghosts.add(t);
			} else {
				tris[kept++] = t;
			}
		}
		long region = regionKey(Math.floorDiv(minX, REGION_SIZE), Math.floorDiv(minY, REGION_SIZE), Math.floorDiv(minZ, REGION_SIZE));
		if (ghosts.isEmpty()) {
			GHOSTS.remove(region);
		} else {
			GHOSTS.put(region, ghosts.toArray(new SkyTri[0]));
		}
		TRIS.put(region, java.util.Arrays.copyOf(tris, kept));
		Long before = TRI_HASH.put(region, hash);
		if (before == null || before != hash) {
			CHANGED.add(BlockPos.asLong(minX, minY, minZ));
		}
	}

	public static int triangleCount() {
		int n = 0;
		for (SkyTri[] t : TRIS.values()) {
			n += t.length;
		}
		return n;
	}

	private static int fillInfo(MemorySegment s, long bitsOff) {
		int count = 0;
		int info = 0;
		int top = 0;
		for (int y = 0; y < 8; y++) {
			long layer = s.get(JAVA_LONG, bitsOff + y * 8L);
			count += Long.bitCount(layer);
			if (layer != 0) {
				info |= y < 4 ? FILL_LOWER : FILL_UPPER;
				top = y;
			}
		}
		return info | count | top << FILL_TOP_SHIFT;
	}

	private static @Nullable VoxelShape buildShape(MemorySegment s, long bitsOff) {
		boolean any = false;
		boolean full = true;
		long[] layers = new long[8];
		for (int y = 0; y < 8; y++) {
			layers[y] = s.get(JAVA_LONG, bitsOff + y * 8L);
			any |= layers[y] != 0;
			full &= layers[y] == -1L;
		}
		if (!any) {
			return null;
		}
		if (full) {
			return Shapes.block();
		}
		BitSetDiscreteVoxelShape discrete = new BitSetDiscreteVoxelShape(8, 8, 8);
		for (int y = 0; y < 8; y++) {
			long layer = layers[y];
			while (layer != 0) {
				int bit = Long.numberOfTrailingZeros(layer);
				layer &= layer - 1;
				discrete.fill(bit & 7, y, bit >>> 3);
			}
		}
		double[] points = new double[9];
		for (int i = 0; i <= 8; i++) points[i] = i / 8.0;
		return new net.minecraft.world.phys.shapes.ArrayVoxelShape(discrete, points, points, points) {};
	}
}
