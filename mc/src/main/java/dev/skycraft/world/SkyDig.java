package dev.skycraft.world;

import static dev.skycraft.link.Proto.*;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.skycraft.SkyCraft;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import dev.skycraft.link.SkyLink;
import org.jspecify.annotations.Nullable;

/**
 * Digging into Skyrim's world. Skyrim's ground, rocks and cave walls are geometry, not blocks; this
 * turns them into blocks one at a time, as they're dug:
 * <ul>
 * <li><b>open</b>: mining a Skyrim surface (as long as mining the block it's made of takes) takes
 * the cell the surface is in out: Skyrim's geometry in it is gone (not drawn, no collision,
 * nothing for NPCs to stand on), with the drops of that block. Nothing sticks up out of the
 * ground: the cell is empty.</li>
 * <li><b>reveal</b>: the cells around a mined one that are wholly inside Skyrim's geometry become
 * blocks (dirt under grass, stone with ores, bedrock a few blocks under the land; endless stone
 * inside rocks and cave walls). Cells only partly inside keep Skyrim's surface, and the part
 * under it shows as Minecraft walls, so a hole is always closed.</li>
 * </ul>
 * Which cells are dug is kept per chunk (saved with the world, synced to every player) and per
 * Skyrim world (worldspace or interior cell: interiors share coordinates), and sent to Skyrim.
 * Opening and revealing are decided by the digging player's client, which knows Skyrim's geometry
 * around them; the server just applies it.
 */
public final class SkyDig {
	private SkyDig() {
	}

	/** One section's dug cells in one Skyrim world: bit x + 16z + 256y. */
	public record DugSection(int world, int sectionY, long[] bits) {
		static final Codec<DugSection> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.fieldOf("world").forGetter(DugSection::world),
			Codec.INT.fieldOf("y").forGetter(DugSection::sectionY),
			Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream).fieldOf("bits").forGetter(DugSection::bits)
		).apply(i, DugSection::new));
	}

	/** A chunk's dug cells. Immutable: changes make a new one (so the attachment syncs). */
	public record DugColumn(List<DugSection> sections) {
		public static final DugColumn EMPTY = new DugColumn(List.of());
		static final Codec<DugColumn> CODEC = DugSection.CODEC.listOf().xmap(DugColumn::new, DugColumn::sections);
		static final StreamCodec<ByteBuf, DugColumn> STREAM_CODEC = new StreamCodec<>() {
			@Override
			public DugColumn decode(ByteBuf buf) {
				int n = ByteBufCodecs.VAR_INT.decode(buf);
				List<DugSection> sections = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					int world = buf.readInt();
					int y = ByteBufCodecs.VAR_INT.decode(buf);
					long[] bits = new long[64];
					for (int k = 0; k < 64; k++) {
						bits[k] = buf.readLong();
					}
					sections.add(new DugSection(world, y, bits));
				}
				return new DugColumn(List.copyOf(sections));
			}

			@Override
			public void encode(ByteBuf buf, DugColumn column) {
				ByteBufCodecs.VAR_INT.encode(buf, column.sections.size());
				for (DugSection s : column.sections) {
					buf.writeInt(s.world);
					ByteBufCodecs.VAR_INT.encode(buf, s.sectionY);
					for (int k = 0; k < 64; k++) {
						buf.writeLong(s.bits.length == 64 ? s.bits[k] : 0L);
					}
				}
			}
		};

		public long @Nullable [] bits(int world, int sectionY) {
			for (DugSection s : this.sections) {
				if (s.world == world && s.sectionY == sectionY && s.bits.length == 64) {
					return s.bits;
				}
			}
			return null;
		}

		public boolean isDug(int world, int x, int y, int z) {
			long[] bits = bits(world, y >> 4);
			int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
			return bits != null && ((bits[bit >> 6] >>> (bit & 63)) & 1L) != 0;
		}

		DugColumn with(int world, int x, int y, int z) {
			int sy = y >> 4;
			int bit = (x & 15) + 16 * (z & 15) + 256 * (y & 15);
			List<DugSection> out = new ArrayList<>(this.sections.size() + 1);
			boolean found = false;
			for (DugSection s : this.sections) {
				if (s.world == world && s.sectionY == sy && s.bits.length == 64) {
					long[] bits = s.bits.clone();
					bits[bit >> 6] |= 1L << (bit & 63);
					out.add(new DugSection(world, sy, bits));
					found = true;
				} else {
					out.add(s);
				}
			}
			if (!found) {
				long[] bits = new long[64];
				bits[bit >> 6] |= 1L << (bit & 63);
				out.add(new DugSection(world, sy, bits));
			}
			return new DugColumn(List.copyOf(out));
		}
	}

	public static final AttachmentType<DugColumn> DUG = AttachmentRegistry.<DugColumn>builder()
		.persistent(DugColumn.CODEC)
		.syncWith(DugColumn.STREAM_CODEC, AttachmentSyncPredicate.all())
		.buildAndRegister(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dug"));

	/** Loads the class (registers the attachment) at mod start. */
	public static void init() {
		SkyCraft.LOG.info("SkyCraft: digging into Skyrim registered ({})", DUG.identifier());
	}

	public static DugColumn column(LevelChunk chunk) {
		DugColumn column = chunk.getAttached(DUG);
		return column != null ? column : DugColumn.EMPTY;
	}

	public static boolean isDug(Level level, int world, BlockPos pos) {
		return column(level.getChunkAt(pos)).isDug(world, pos.getX(), pos.getY(), pos.getZ());
	}

	// ---- server --------------------------------------------------------------------------------

	private static final double REACH = 8.0;

	private static boolean inReach(ServerPlayer player, BlockPos pos, double reach) {
		return player.getEyePosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= reach * reach;
	}

	/**
	 * A player mined Skyrim's geometry out of this cell (the cell the surface is in): it's gone,
	 * with the drops and the sound of mining what it was made of.
	 */
	public static void open(ServerPlayer player, int world, BlockPos pos, int material) {
		ServerLevel level = player.level();
		if (!destruction || !inReach(player, pos, REACH) || !level.isLoaded(pos) || player.isSpectator()) {
			return;
		}
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return;
		}
		chunk.setAttached(DUG, column.with(world, pos.getX(), pos.getY(), pos.getZ()));
		BlockState state = materialState(material);
		if (!player.isCreative()) {
			ItemStack tool = player.getMainHandItem();
			ItemStack used = tool.copy();
			boolean harvest = player.hasCorrectToolForDrops(state);
			tool.mineBlock(level, state, pos, player);
			if (harvest) {
				state.getBlock().playerDestroy(level, player, pos, state, null, used);
			}
		}
		level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_DESTROY_BLOCK, pos, Block.getId(state));
	}

	/** Cells around a mined one that are wholly inside Skyrim's geometry: blocks now. */
	public static void reveal(ServerPlayer player, int world, List<BlockPos> cells, int[] materials) {
		if (!destruction) {
			return;
		}
		ServerLevel level = player.level();
		for (int i = 0; i < cells.size() && i < materials.length; i++) {
			BlockPos pos = cells.get(i);
			if (inReach(player, pos, REACH + 4.0) && level.isLoaded(pos)) {
				digCell(level, world, pos, materials[i]);
			}
		}
	}

	/**
	 * Whether Minecraft digs into Skyrim at all: the pause menu's "Skyrim destruction" button, saved
	 * in config/skycraft.properties. Off, mining Skyrim's surfaces and explosions leave it alone (and
	 * breaking blocks in old holes digs no further); holes already dug stay. In a friend's world it's
	 * the host's setting that counts (their server does the digging).
	 */
	public static volatile boolean destruction = true;

	/** Marks a cell dug out of Skyrim's geometry (in that Skyrim world). Returns false if it was already. */
	public static boolean markDug(ServerLevel level, int world, BlockPos pos) {
		LevelChunk chunk = level.getChunkAt(pos);
		DugColumn column = column(chunk);
		if (column.isDug(world, pos.getX(), pos.getY(), pos.getZ())) {
			return false;
		}
		chunk.setAttached(DUG, column.with(world, pos.getX(), pos.getY(), pos.getZ()));
		return true;
	}

	/** A cell wholly inside Skyrim's geometry: dug out of it, and the block it's made of put there. */
	public static void digCell(ServerLevel level, int world, BlockPos pos, int material) {
		if (!markDug(level, world, pos)) {
			return;
		}
		BlockState here = level.getBlockState(pos);
		// Keep whatever Minecraft block is already there (someone built into the ground).
		if (material > 0 && (here.isAir() || here.canBeReplaced())) {
			level.setBlock(pos, blockFor(level, pos, material), 3);
		}
	}

	/** The block a Proto.DIG_* material stands for (stone without ores). */
	public static BlockState materialState(int material) {
		return switch (material) {
			case DIG_GRASS -> Blocks.GRASS_BLOCK.defaultBlockState();
			case DIG_DIRT -> Blocks.DIRT.defaultBlockState();
			case DIG_COBBLE -> Blocks.COBBLESTONE.defaultBlockState();
			case DIG_SNOW -> Blocks.SNOW_BLOCK.defaultBlockState();
			case DIG_ICE -> Blocks.PACKED_ICE.defaultBlockState();
			case DIG_SAND -> Blocks.SAND.defaultBlockState();
			case DIG_GRAVEL -> Blocks.GRAVEL.defaultBlockState();
			case DIG_MUD -> Blocks.MUD.defaultBlockState();
			case DIG_OAK_LOG -> Blocks.OAK_LOG.defaultBlockState();
			case DIG_SPRUCE_LOG -> Blocks.SPRUCE_LOG.defaultBlockState();
			case DIG_BIRCH_LOG -> Blocks.BIRCH_LOG.defaultBlockState();
			case DIG_PLANKS -> Blocks.SPRUCE_PLANKS.defaultBlockState();
			case DIG_METAL -> Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState();
			case DIG_GLASS -> Blocks.GLASS.defaultBlockState();
			case DIG_ORGANIC -> Blocks.MOSS_BLOCK.defaultBlockState();
			case DIG_CLOTH -> Blocks.WOOL.pick(DyeColor.BROWN).defaultBlockState();
			case DIG_BONE -> Blocks.BONE_BLOCK.defaultBlockState();
			case DIG_WEB -> Blocks.COBWEB.defaultBlockState();
			case DIG_ASH -> Blocks.CONCRETE_POWDER.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
			case DIG_BEDROCK -> Blocks.BEDROCK.defaultBlockState();
			default -> Blocks.STONE.defaultBlockState();
		};
	}

	/** The block a Proto.DIG_* material becomes here. Stone has ores in it. */
	public static BlockState blockFor(ServerLevel level, BlockPos pos, int material) {
		return material == DIG_STONE || material == DIG_NONE ? stoneOrOre(level.getSeed(), pos) : materialState(material);
	}

	/** Ores come in little clusters (a 2x2x2 cell picks one ore, then each block in it maybe). */
	static BlockState stoneOrOre(long seed, BlockPos pos) {
		long cell = mix(seed ^ mix(((long) (pos.getX() >> 1) * 73856093L) ^ ((long) (pos.getY() >> 1) * 19349663L) ^ ((long) (pos.getZ() >> 1) * 83492791L)));
		long block = mix(cell ^ ((pos.getX() & 1) | (pos.getY() & 1) << 1 | (pos.getZ() & 1) << 2));
		if ((block & 0xFF) >= 150) { // about 60% of a vein cell's blocks
			return Blocks.STONE.defaultBlockState();
		}
		int roll = (int) ((cell >>> 16) % 10000L);
		// Chances per vein cell, in 1/10000.
		if ((roll -= 330) < 0) {
			return Blocks.COAL_ORE.defaultBlockState();
		}
		if ((roll -= 220) < 0) {
			return Blocks.IRON_ORE.defaultBlockState();
		}
		if ((roll -= 170) < 0) {
			return Blocks.COPPER_ORE.defaultBlockState();
		}
		if ((roll -= 80) < 0) {
			return Blocks.REDSTONE_ORE.defaultBlockState();
		}
		if ((roll -= 45) < 0) {
			return Blocks.GOLD_ORE.defaultBlockState();
		}
		if ((roll -= 35) < 0) {
			return Blocks.LAPIS_ORE.defaultBlockState();
		}
		if ((roll -= 14) < 0) {
			return Blocks.DIAMOND_ORE.defaultBlockState();
		}
		if ((roll -= 5) < 0) {
			return Blocks.EMERALD_ORE.defaultBlockState();
		}
		return Blocks.STONE.defaultBlockState();
	}

	private static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	// ---- client: what's inside Skyrim's geometry ----------------------------------------------

	/** Leave the cell alone: Skyrim geometry that can't be dug (a building) is there. */
	public static final int KEEP = -1;
	/** Open air, or only partly inside Skyrim's geometry (its surface stays; walls are drawn). */
	public static final int AIR = 0;
	/** Blocks this far under the land's surface are bedrock. */
	public static final double BEDROCK_DEPTH = 5.0;
	/** A cell becomes a block only when this many of its 27 sample points are inside. */
	private static final int WHOLE = 25;

	private static final double SEARCH = 2.5;
	private static final double LAND_REACH = 16.0; // how far above a point the land is looked for
	private static final double[] SAMPLE = { 1.0 / 6.0, 0.5, 5.0 / 6.0 };

	/** Is a point inside Skyrim's geometry? Its nearest original surface (within SEARCH) decides. */
	public static final class Probe {
		/** The land (terrain) around: a height field, so "below it" is "inside". */
		public final List<SkyTri> land = new ArrayList<>();
		/** Everything else (rocks, cliffs, cave walls, trees, buildings). */
		public final List<SkyTri> tris = new ArrayList<>();
		private final List<SkyTri> gathered = new ArrayList<>();
		private final double[] q = new double[3];
		/** After {@link #test}: the surface deciding it (null: none near, deep inside). */
		public @Nullable SkyTri surface;
		/** After {@link #test}: how far behind that surface (infinite if none near). */
		public double depth;
		private double boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;
		// Far above and below the box (gathered only if a point is far from every surface), and
		// that by block column.
		private @Nullable List<SkyTri> column;
		private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<SkyTri>> columns = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
		// Per block: the surfaces that could be within SEARCH of a point in it. A blast's probe holds
		// thousands, nearly all far from any one point; kept in this.tris's order (ties go the same way).
		private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<SkyTri>> nearCells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

		/** Gathers the surfaces around a box (MC coords) once, for many tests inside it. */
		public Probe around(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			this.gathered.clear();
			this.land.clear();
			this.tris.clear();
			this.column = null;
			this.columns.clear();
			this.nearCells.clear();
			this.boxMinX = minX;
			this.boxMinY = minY;
			this.boxMinZ = minZ;
			this.boxMaxX = maxX;
			this.boxMaxY = maxY;
			this.boxMaxZ = maxZ;
			// The land well above too: a point deep under it is still under it.
			SkyCollision.originalSurfacesNear(new AABB(minX - SEARCH, minY - SEARCH, minZ - SEARCH, maxX + SEARCH, maxY + SEARCH + LAND_REACH, maxZ + SEARCH), this.gathered);
			for (SkyTri t : this.gathered) {
				if (t.stairHelper) {
					continue;
				}
				if (t.terrain) {
					this.land.add(t);
				} else if (t.maxY >= minY - SEARCH && t.minY <= maxY + SEARCH) {
					this.tris.add(t);
				}
			}
			return this;
		}

		/** The land's height at (x, z), or NaN where there's none near. */
		public double landHeight(double x, double z) {
			double best = Double.NaN;
			for (SkyTri t : this.land) {
				double h = t.heightAt(x, z);
				if (!Double.isNaN(h) && (Double.isNaN(best) || h > best)) {
					best = h;
					this.surface = t;
				}
			}
			return best;
		}

		/** KEEP (inside something that stays), AIR (in front of a surface) or a material (inside). */
		public int test(double px, double py, double pz) {
			// Under the land: inside, whatever else is buried nearby (rocks, roots).
			double ground = landHeight(px, pz);
			SkyTri landSurface = this.surface;
			if (!Double.isNaN(ground) && py < ground) {
				this.surface = landSurface;
				this.depth = ground - py;
				return landSurface.material == DIG_NONE ? DIG_STONE : landSurface.material;
			}
			// Above the land (or where there's none, as in interiors): inside an object if behind its
			// nearest surface (surfaces face out of their solid side).
			SkyTri nearest = null;
			double best = SEARCH * SEARCH;
			double bx = 0, by = 0, bz = 0;
			for (SkyTri t : nearCell(px, py, pz)) {
				// Its box already farther than the best so far: it can't be nearer.
				double ex = Math.max(0.0, Math.max(t.minX - px, px - t.maxX));
				double ey = Math.max(0.0, Math.max(t.minY - py, py - t.maxY));
				double ez = Math.max(0.0, Math.max(t.minZ - pz, pz - t.maxZ));
				if (ex * ex + ey * ey + ez * ez >= best) {
					continue;
				}
				closestPoint(t, px, py, pz, this.q);
				double dx = px - this.q[0], dy = py - this.q[1], dz = pz - this.q[2];
				double d2 = dx * dx + dy * dy + dz * dz;
				if (d2 < best) {
					best = d2;
					nearest = t;
					bx = this.q[0];
					by = this.q[1];
					bz = this.q[2];
				}
			}
			this.surface = nearest;
			if (nearest == null) {
				this.depth = Double.POSITIVE_INFINITY;
				return farFromSurfaces(px, py, pz, ground);
			}
			if ((px - bx) * nearest.nx + (py - by) * nearest.ny + (pz - bz) * nearest.nz >= 0.0) {
				return AIR;
			}
			if (!nearest.diggable) {
				return KEEP;
			}
			this.depth = Math.sqrt(best);
			return nearest.material == DIG_NONE ? DIG_STONE : nearest.material;
		}

		/**
		 * A point with no surface near (open sky, the middle of a big room, deep in rock): straight
		 * up decides. Under the top of something (a surface facing up, away from it) it's inside
		 * that; under an underside (an overhang, a ceiling) or nothing at all it's in the open, as
		 * long as there's land under it. No land anywhere (interiors): deep in the rock.
		 */
		private int farFromSurfaces(double px, double py, double pz, double ground) {
			boolean landBelow = !Double.isNaN(ground);
			SkyTri above = null;
			double aboveY = Double.POSITIVE_INFINITY;
			for (SkyTri t : columnAt(px, pz)) {
				double h = t.heightAt(px, pz);
				if (Double.isNaN(h)) {
					continue;
				}
				if (h > py) {
					if (h < aboveY) {
						aboveY = h;
						above = t;
					}
				} else if (t.terrain) {
					landBelow = true;
				}
			}
			if (above != null && above.ny > 0.0) {
				this.surface = above;
				this.depth = aboveY - py;
				if (!above.diggable) {
					return KEEP;
				}
				return above.material == DIG_NONE ? DIG_STONE : above.material;
			}
			return above != null || landBelow ? AIR : DIG_STONE;
		}

		private List<SkyTri> nearCell(double px, double py, double pz) {
			int cx = (int) Math.floor(px), cy = (int) Math.floor(py), cz = (int) Math.floor(pz);
			long key = net.minecraft.core.BlockPos.asLong(cx, cy, cz);
			List<SkyTri> list = this.nearCells.get(key);
			if (list == null) {
				list = new ArrayList<>();
				for (SkyTri t : this.tris) {
					if (t.maxX >= cx - SEARCH && t.minX <= cx + 1 + SEARCH && t.maxY >= cy - SEARCH && t.minY <= cy + 1 + SEARCH && t.maxZ >= cz - SEARCH
						&& t.minZ <= cz + 1 + SEARCH) {
						list.add(t);
					}
				}
				this.nearCells.put(key, list);
			}
			return list;
		}

		/** The surfaces over or under the block column holding (x, z). */
		private List<SkyTri> columnAt(double x, double z) {
			if (this.column == null) {
				List<SkyTri> all = new ArrayList<>();
				SkyCollision.originalSurfacesNear(new AABB(Math.min(x, this.boxMinX), this.boxMinY - COLUMN_REACH, Math.min(z, this.boxMinZ),
					Math.max(x, this.boxMaxX), this.boxMaxY + COLUMN_REACH, Math.max(z, this.boxMaxZ)), all);
				this.column = new ArrayList<>();
				for (SkyTri t : all) {
					if (!t.stairHelper && Math.abs(t.ny) >= 0.05) {
						this.column.add(t);
					}
				}
			}
			int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
			long key = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
			List<SkyTri> list = this.columns.get(key);
			if (list == null) {
				list = new ArrayList<>();
				for (SkyTri t : this.column) {
					if (t.maxX >= bx && t.minX <= bx + 1 && t.maxZ >= bz && t.minZ <= bz + 1) {
						list.add(t);
					}
				}
				this.columns.put(key, list);
			}
			return list;
		}
	}

	private static final double COLUMN_REACH = 96.0; // how far up and down a lone point looks

	/**
	 * Is this cell wholly inside Skyrim's geometry, and if so what block is it (a Proto.DIG_*
	 * material)? Cells only partly inside stay Skyrim's (AIR): its surface stays, and the part
	 * under it shows as Minecraft walls wherever it's been dug next to (see the client's DigWalls).
	 * Only for cells next to a dug one: far from every surface means deep inside there.
	 */
	public static int classify(int x, int y, int z) {
		Probe probe = new Probe().around(x, y, z, x + 1, y + 1, z + 1);
		int inside = 0;
		int centreMaterial = DIG_STONE;
		double centreDepth = Double.POSITIVE_INFINITY;
		for (double sy : SAMPLE) {
			for (double sz : SAMPLE) {
				for (double sx : SAMPLE) {
					int r = probe.test(x + sx, y + sy, z + sz);
					if (r == KEEP) {
						return KEEP;
					}
					if (r == AIR) {
						continue;
					}
					inside++;
					if (sx == 0.5 && sy == 0.5 && sz == 0.5) {
						centreMaterial = r;
						centreDepth = probe.depth;
					}
				}
			}
		}
		if (inside < WHOLE) {
			return AIR;
		}
		// Under the land: dirt, stone, then bedrock a few blocks down.
		SkyTri land = landAbove(x + 0.5, y + 0.5, z + 0.5);
		if (land != null) {
			double depth = land.heightAt(x + 0.5, z + 0.5) - (y + 0.5);
			if (depth >= BEDROCK_DEPTH) {
				return DIG_BEDROCK;
			}
			if (Double.isInfinite(centreDepth)) {
				centreMaterial = land.material == DIG_NONE ? DIG_STONE : land.material;
				centreDepth = depth;
			}
		}
		return underground(centreMaterial, centreDepth);
	}

	/** The nearest stretch of land (Skyrim's terrain) above a point, within 32 blocks, or null. */
	public static @Nullable SkyTri landAbove(double x, double y, double z) {
		List<SkyTri> column = new ArrayList<>();
		SkyCollision.originalSurfacesNear(new AABB(x - 0.01, y, z - 0.01, x + 0.01, y + 32, z + 0.01), column);
		SkyTri best = null;
		double bestHeight = Double.POSITIVE_INFINITY;
		for (SkyTri t : column) {
			if (!t.terrain) {
				continue;
			}
			double h = t.heightAt(x, z);
			if (!Double.isNaN(h) && h >= y && h < bestHeight) {
				bestHeight = h;
				best = t;
			}
		}
		return best;
	}

	/** What's this deep behind a surface of this material: grass has dirt under it, then stone. */
	public static int underground(int surface, double depth) {
		return switch (surface) {
			case DIG_GRASS, DIG_DIRT -> depth < 3.5 ? DIG_DIRT : DIG_STONE;
			case DIG_MUD -> depth < 3.5 ? DIG_MUD : DIG_STONE;
			case DIG_SNOW -> depth < 1.5 ? DIG_SNOW : depth < 3.5 ? DIG_DIRT : DIG_STONE;
			case DIG_SAND -> depth < 3.5 ? DIG_SAND : DIG_STONE;
			case DIG_GRAVEL -> depth < 2.5 ? DIG_GRAVEL : DIG_STONE;
			case DIG_ASH -> depth < 3.5 ? DIG_ASH : DIG_STONE;
			case DIG_ICE -> depth < 2.5 ? DIG_ICE : DIG_STONE;
			case DIG_COBBLE -> depth < 1.5 ? DIG_COBBLE : DIG_STONE;
			case DIG_OAK_LOG, DIG_SPRUCE_LOG, DIG_BIRCH_LOG -> surface;
			case DIG_PLANKS -> depth < 2.5 ? DIG_PLANKS : DIG_STONE;
			case DIG_METAL -> depth < 1.5 ? DIG_METAL : DIG_STONE;
			case DIG_GLASS, DIG_ORGANIC, DIG_CLOTH, DIG_BONE, DIG_WEB -> depth < 1.0 ? surface : DIG_STONE;
			default -> DIG_STONE;
		};
	}

	// ---- collision for the walls of dug holes -------------------------------------------------

	private static final SkyLink.SkyState SERVER_SKY = new SkyLink.SkyState();
	private static volatile int serverWorld;
	private static volatile long serverWorldAt;
	// Worked-out walls by section (they depend only on Skyrim's surfaces, not on what's dug), shared
	// by every thread. NO_WALL stands for "none" (a map can't hold null).
	private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.ConcurrentHashMap<Long, VoxelShape>> WALLS =
		new java.util.concurrent.ConcurrentHashMap<>();
	private static final VoxelShape NO_WALL = Shapes.empty();

	/** Skyrim's world changed: every wall is worked out again. */
	public static void wallsChanged() {
		WALLS.clear();
	}

	/** Skyrim's surfaces in this box changed: the walls that could see them are worked out again. */
	public static void wallsChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		int reach = (int) Math.ceil(SEARCH) + 1;
		int down = reach + (int) Math.ceil(LAND_REACH); // a cell looks this far up for the land over it
		for (int sx = (minX - reach) >> 4; sx <= (maxX + reach) >> 4; sx++) {
			for (int sy = (minY - down) >> 4; sy <= (maxY + reach) >> 4; sy++) {
				for (int sz = (minZ - reach) >> 4; sz <= (maxZ + reach) >> 4; sz++) {
					WALLS.remove(net.minecraft.core.SectionPos.asLong(sx, sy, sz));
				}
			}
		}
	}

	private static int worldFor(Level level) {
		if (level.isClientSide()) {
			DugLookup lookup = clientDug;
			return lookup != null ? clientWorld : 0;
		}
		long now = System.currentTimeMillis();
		if (now - serverWorldAt > 250) {
			synchronized (SERVER_SKY) {
				if (SkyLink.readSkyState(SERVER_SKY)) {
					serverWorld = SERVER_SKY.worldId;
				}
			}
			serverWorldAt = now;
		}
		return serverWorld;
	}

	/** Set by the client with clientDug: the Skyrim world its dug cells are for. */
	public static volatile int clientWorld;

	private static @Nullable DugColumn columnFor(net.minecraft.world.level.CollisionGetter level, int x, int z) {
		var chunk = level.getChunkForCollisions(x >> 4, z >> 4);
		return chunk instanceof LevelChunk lc ? lc.getAttached(DUG) : null;
	}

	/**
	 * The solid part of an undug cell next to a dug one: under the land's surface (the wall of a
	 * hole, drawn by the client's DigWalls), which Skyrim's own geometry (just a surface) doesn't
	 * make solid. Null for none. Any thread.
	 */
	public static @Nullable VoxelShape wallShape(net.minecraft.world.level.CollisionGetter getter, BlockPos pos) {
		if (!(getter instanceof Level level) || !SkyCollision.active()) {
			return null;
		}
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		DugColumn here = columnFor(getter, x, z);
		int world = worldFor(level);
		if (here != null && here.isDug(world, x, y, z)) {
			return null;
		}
		boolean nextToDug = false;
		for (Direction d : Direction.values()) {
			DugColumn column = d.getAxis() == Direction.Axis.Y ? here : columnFor(getter, x + d.getStepX(), z + d.getStepZ());
			if (column != null && column.isDug(world, x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
				nextToDug = true;
				break;
			}
		}
		if (!nextToDug) {
			return null;
		}
		if (WALLS.size() > 2048) {
			WALLS.clear();
		}
		var shapes = WALLS.computeIfAbsent(net.minecraft.core.SectionPos.asLong(x >> 4, y >> 4, z >> 4), k -> new java.util.concurrent.ConcurrentHashMap<>());
		long key = pos.asLong();
		VoxelShape cached = shapes.get(key);
		if (cached != null) {
			return cached == NO_WALL ? null : cached;
		}
		VoxelShape shape = computeWall(x, y, z);
		shapes.put(key, shape == null ? NO_WALL : shape);
		return shape;
	}

	private static @Nullable VoxelShape computeWall(int x, int y, int z) {
		Probe probe = new Probe().around(x, y, z, x + 1, y + 1, z + 1);
		if (!probe.land.isEmpty()) {
			// Solid up to the lowest the land gets over the cell (never above the ground).
			double lowest = Double.POSITIVE_INFINITY;
			for (double[] c : new double[][] { { 0.05, 0.05 }, { 0.95, 0.05 }, { 0.05, 0.95 }, { 0.95, 0.95 }, { 0.5, 0.5 } }) {
				double h = probe.landHeight(x + c[0], z + c[1]);
				if (!Double.isNaN(h)) {
					lowest = Math.min(lowest, h);
				}
			}
			if (Double.isFinite(lowest) && lowest - y > 0.05) {
				return lowest - y >= 0.999 ? Shapes.block() : Shapes.box(0, 0, 0, 1, lowest - y, 1);
			}
			if (probe.tris.isEmpty()) {
				return null;
			}
		}
		// Rocks, cave walls: solid if the middle is inside.
		return probe.test(x + 0.5, y + 0.5, z + 0.5) > AIR ? Shapes.block() : null;
	}

	/**
	 * Looks up whether a cell is dug in the client's world (set by the client; null on a server).
	 * Used by the crosshair pick to hit the walls of dug holes.
	 */
	public interface DugLookup {
		boolean isDug(int x, int y, int z);
	}

	public static volatile @Nullable DugLookup clientDug;

	/** Closest point on the triangle to p (Ericson, Real-Time Collision Detection 5.1.5). */
	static void closestPoint(SkyTri t, double px, double py, double pz, double[] out) {
		double abx = t.bx - t.ax, aby = t.by - t.ay, abz = t.bz - t.az;
		double acx = t.cx - t.ax, acy = t.cy - t.ay, acz = t.cz - t.az;
		double apx = px - t.ax, apy = py - t.ay, apz = pz - t.az;
		double d1 = abx * apx + aby * apy + abz * apz, d2 = acx * apx + acy * apy + acz * apz;
		if (d1 <= 0 && d2 <= 0) {
			set(out, t.ax, t.ay, t.az);
			return;
		}
		double bpx = px - t.bx, bpy = py - t.by, bpz = pz - t.bz;
		double d3 = abx * bpx + aby * bpy + abz * bpz, d4 = acx * bpx + acy * bpy + acz * bpz;
		if (d3 >= 0 && d4 <= d3) {
			set(out, t.bx, t.by, t.bz);
			return;
		}
		double vc = d1 * d4 - d3 * d2;
		if (vc <= 0 && d1 >= 0 && d3 <= 0) {
			double v = d1 / (d1 - d3);
			set(out, t.ax + abx * v, t.ay + aby * v, t.az + abz * v);
			return;
		}
		double cpx = px - t.cx, cpy = py - t.cy, cpz = pz - t.cz;
		double d5 = abx * cpx + aby * cpy + abz * cpz, d6 = acx * cpx + acy * cpy + acz * cpz;
		if (d6 >= 0 && d5 <= d6) {
			set(out, t.cx, t.cy, t.cz);
			return;
		}
		double vb = d5 * d2 - d1 * d6;
		if (vb <= 0 && d2 >= 0 && d6 <= 0) {
			double w = d2 / (d2 - d6);
			set(out, t.ax + acx * w, t.ay + acy * w, t.az + acz * w);
			return;
		}
		double va = d3 * d6 - d5 * d4;
		if (va <= 0 && (d4 - d3) >= 0 && (d5 - d6) >= 0) {
			double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
			set(out, t.bx + (t.cx - t.bx) * w, t.by + (t.cy - t.by) * w, t.bz + (t.cz - t.bz) * w);
			return;
		}
		double denom = 1.0 / (va + vb + vc);
		double v = vb * denom, w = vc * denom;
		set(out, t.ax + abx * v + acx * w, t.ay + aby * v + acy * w, t.az + abz * v + acz * w);
	}

	private static void set(double[] out, double x, double y, double z) {
		out[0] = x;
		out[1] = y;
		out[2] = z;
	}
}
