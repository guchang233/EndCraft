package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

/**
 * Mirrors the host's terrain voxels (SkyCollision) into Sable's Rapier static terrain, so ships
 * rest on and collide with Endfield ground instead of falling through it. Sable only uploads the
 * chunk sections its physics tickets hold; this patches those uploads and keeps them in step with
 * the streamed terrain. Placed Minecraft blocks keep their own collider where both exist.
 *
 * <p>Reflection only: Sable is optional and its Rapier backend ships as a nested jar. All calls
 * arrive on the server thread through SableTerrainMixin, except the voxel listener.
 */
public final class SableHostTerrain {
    /** VoxelNeighborhoodState.CORNER.ordinal() in Sable 2.0.6: no neighbour culling, every contact feature tested. */
    private static final int CORNER = 3;
    private static final int MAX_COLLIDERS = 4096, MAX_PENDING = 4096;
    private static final int[] CLEAR = new int[0];
    private static final Queue<int[]> PENDING = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger PENDING_COUNT = new AtomicInteger();
    private static final AtomicBoolean LISTENING = new AtomicBoolean();
    private static volatile boolean overflow;
    // Rapier voxel colliders are global and cannot be freed; one per distinct host shape.
    private static final Map<String, Integer> COLLIDERS = new ConcurrentHashMap<>();
    // Physics-loaded sections of the current overworld pipeline -> positions we patched -> packed value.
    private static final Long2ObjectOpenHashMap<Long2IntOpenHashMap> SECTIONS = new Long2ObjectOpenHashMap<>();
    private static Object pipeline;
    private static ServerLevel level;
    private static boolean disabled;
    private static Field sceneField, acceleratorField;
    private static Object bakery;
    private static Method sceneHandle, changeBlock, createCollider, colliderHandle, addBox, physicsData, neighborhood;
    private static double friction, volume, restitution;

    private SableHostTerrain() {}

    /** Sable is uploading a terrain section; fill air with host voxels before it reaches Rapier. */
    public static void sectionAdded(Object owner, int sx, int sy, int sz, int[] packed) {
        if (!bind(owner)) return;
        var patched = new Long2IntOpenHashMap();
        SECTIONS.put(SectionPos.asLong(sx, sy, sz), patched);
        if (SkyCollision.blockCount() == 0) return;
        try {
            int x0 = sx << 4, y0 = sy << 4, z0 = sz << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int i = x | z << 4 | y << 8;
                if (packed[i] >>> 16 != 0) continue; // a Minecraft collider owns this voxel
                VoxelShape host = SkyCollision.shapeAt(x0 + x, y0 + y, z0 + z);
                int collider = host == null ? -1 : collider(host);
                if (collider < 0) continue;
                packed[i] = CORNER | (collider + 1) << 16;
                patched.put(BlockPos.asLong(x0 + x, y0 + y, z0 + z), packed[i]);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    public static void sectionRemoved(Object owner, int sx, int sy, int sz) {
        if (owner == pipeline) SECTIONS.remove(SectionPos.asLong(sx, sy, sz));
    }

    /** Sable rewrote a block and its six neighbours from Minecraft state alone; restore host voxels there. */
    public static void blockChanged(Object owner, SectionPos section, int x, int y, int z) {
        if (owner != pipeline || disabled) return;
        int bx = section.minBlockX() + (x & 15), by = section.minBlockY() + (y & 15), bz = section.minBlockZ() + (z & 15);
        try {
            long scene = scene();
            refresh(scene, bx, by, bz, true);
            for (var d : net.minecraft.core.Direction.values()) refresh(scene, bx + d.getStepX(), by + d.getStepY(), bz + d.getStepZ(), true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    /** Applies streamed terrain changes once per server tick. */
    public static void tick(Object owner) {
        if (owner != pipeline || disabled) return;
        boolean all = overflow;
        if (all) { overflow = false; PENDING.clear(); PENDING_COUNT.set(0); }
        try {
            long scene = scene();
            for (int[] b; (b = PENDING.poll()) != null; ) {
                PENDING_COUNT.decrementAndGet();
                if (b == CLEAR) all = true;
                else if (!all) for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++)
                    refresh(scene, x, y, z, false);
            }
            if (all) for (long key : SECTIONS.keySet().toLongArray()) {
                int x0 = SectionPos.x(key) << 4, y0 = SectionPos.y(key) << 4, z0 = SectionPos.z(key) << 4;
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) refresh(scene, x0 + x, y0 + y, z0 + z, false);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
        }
    }

    private static void refresh(long scene, int x, int y, int z, boolean overwritten) throws ReflectiveOperationException {
        var patched = SECTIONS.get(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        if (patched == null) return; // not loaded in Rapier
        long key = BlockPos.asLong(x, y, z);
        VoxelShape host = SkyCollision.shapeAt(x, y, z);
        if (overwritten) patched.remove(key);
        if (host == null && !overwritten && !patched.containsKey(key)) return; // Minecraft-owned, untouched
        BlockPos pos = new BlockPos(x, y, z);
        int block = blockHandle(level.getBlockState(pos));
        int collider = host == null || block != 0 ? -1 : collider(host);
        int value;
        if (collider >= 0) {
            value = CORNER | (collider + 1) << 16;
            if (patched.get(key) == value) return;
            patched.put(key, value);
        } else {
            // Sable's own value stands; restore it only where we had patched a host voxel.
            if (overwritten || patched.remove(key) == 0) return;
            value = ((Enum<?>) neighborhood.invoke(null, acceleratorField.get(pipeline), pos, null)).ordinal() | block << 16;
        }
        changeBlock.invoke(null, scene, x, y, z, value);
    }

    private static int blockHandle(BlockState state) throws ReflectiveOperationException {
        Object data = physicsData.invoke(bakery, state);
        return data == null ? 0 : (int) colliderHandle.invoke(data) + 1;
    }

    private static int collider(VoxelShape shape) throws ReflectiveOperationException {
        String key = key(shape);
        Integer handle = COLLIDERS.get(key);
        if (handle != null) return handle;
        if (COLLIDERS.size() >= MAX_COLLIDERS) {
            // Unusual terrain detail beyond the budget collides as its bounds.
            AABB bounds = shape.bounds();
            key = "b" + key(net.minecraft.world.phys.shapes.Shapes.create(bounds));
            handle = COLLIDERS.get(key);
            if (handle != null) return handle;
            if (COLLIDERS.size() >= MAX_COLLIDERS * 2) return -1;
            shape = net.minecraft.world.phys.shapes.Shapes.create(bounds);
        }
        Object data = createCollider.invoke(null, friction, volume, restitution, false, null);
        for (AABB box : shape.toAabbs()) addBox.invoke(data, new Vector3d(box.minX, box.minY, box.minZ), new Vector3d(box.maxX, box.maxY, box.maxZ));
        handle = (int) colliderHandle.invoke(data);
        COLLIDERS.put(key, handle);
        return handle;
    }

    /** Host shapes are 8x8x8 sub-voxels, so eighths identify a shape exactly. */
    private static String key(VoxelShape shape) {
        var text = new StringBuilder();
        shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
            for (double v : new double[] {x0, y0, z0, x1, y1, z1}) text.append((char) ('0' + Math.round(v * 8)));
        });
        return text.toString();
    }

    private static long scene() throws ReflectiveOperationException {
        return (long) sceneHandle.invoke(sceneField.get(pipeline));
    }

    /** Tracks the overworld pipeline, the only level that receives host terrain. */
    private static boolean bind(Object owner) {
        if (disabled) return false;
        if (owner == pipeline) return true;
        try {
            Class<?> type = owner.getClass();
            Field levelField = type.getDeclaredField("level");
            levelField.setAccessible(true);
            ServerLevel ownerLevel = (ServerLevel) levelField.get(owner);
            if (ownerLevel.dimension() != Level.OVERWORLD) return false;
            if (changeBlock == null) resolve(type);
            Field bakeryField = type.getDeclaredField("colliderBakery");
            bakeryField.setAccessible(true);
            bakery = bakeryField.get(owner);
            pipeline = owner;
            level = ownerLevel;
            SECTIONS.clear();
            PENDING.clear();
            PENDING_COUNT.set(0);
            overflow = false;
            if (LISTENING.compareAndSet(false, true)) SkyCollision.addVoxelListener(SableHostTerrain::queue);
            SkyCraft.LOG.info("SkyCraft: host terrain mirrored into Sable physics");
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            disable(e);
            return false;
        }
    }

    private static void resolve(Class<?> pipelineType) throws ReflectiveOperationException {
        ClassLoader loader = pipelineType.getClassLoader();
        sceneField = pipelineType.getDeclaredField("scene");
        sceneField.setAccessible(true);
        acceleratorField = pipelineType.getDeclaredField("accelerator");
        acceleratorField.setAccessible(true);
        sceneHandle = sceneField.getType().getDeclaredMethod("handle");
        sceneHandle.setAccessible(true);
        Class<?> rapier = Class.forName("dev.ryanhcode.sable.physics.impl.rapier.Rapier3D", true, loader);
        changeBlock = rapier.getMethod("changeBlock", long.class, int.class, int.class, int.class, int.class);
        Class<?> callback = Class.forName("dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback", true, loader);
        createCollider = rapier.getMethod("createVoxelColliderEntry", double.class, double.class, double.class, boolean.class, callback);
        Class<?> data = createCollider.getReturnType();
        colliderHandle = data.getMethod("handle");
        addBox = data.getMethod("addBox", org.joml.Vector3dc.class, org.joml.Vector3dc.class);
        physicsData = pipelineType.getDeclaredField("colliderBakery").getType().getMethod("getPhysicsDataForBlock", BlockState.class);
        Class<?> state = Class.forName("dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState", true, loader);
        neighborhood = state.getMethod("getState", acceleratorField.getType(), BlockPos.class, net.minecraft.world.level.chunk.LevelChunk.class);
        // Host ground behaves like stone for friction and bounce.
        Class<?> properties = Class.forName("dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper", true, loader);
        BlockState stone = Blocks.STONE.defaultBlockState();
        friction = (double) properties.getMethod("getFriction", BlockState.class).invoke(null, stone);
        volume = (double) properties.getMethod("getVolume", BlockState.class).invoke(null, stone);
        restitution = (double) properties.getMethod("getRestitution", BlockState.class).invoke(null, stone);
    }

    private static void queue(int[] bounds) {
        if (overflow) return;
        if (PENDING_COUNT.incrementAndGet() > MAX_PENDING) { overflow = true; return; }
        PENDING.add(bounds == null ? CLEAR : bounds);
    }

    private static void disable(Throwable e) {
        if (disabled) return;
        disabled = true;
        SkyCraft.LOG.error("SkyCraft: Sable host terrain adapter disabled; ships will not collide with host ground", e);
    }
}
