package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Version-specific 1.21.1 mesh adapter; shares protocol 12 with the released host. */
public final class WorldExporter {
    private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
    private static final LongOpenHashSet SENT = new LongOpenHashSet();
    // Sections exported at least once; later changes arrive through LevelRendererMixin.setSectionDirty.
    private static final LongOpenHashSet EXPORTED = new LongOpenHashSet();
    private static int generation = -1, scanTick, sentWorld, refreshTick;
    private static boolean hostWasLoading;
    private static boolean lastPreview;
    // The host drops render messages while its renderer is not ready and on managed exceptions;
    // re-send exported sections round-robin so a lost one comes back on its own.
    private static final it.unimi.dsi.fastutil.longs.LongArrayList REFRESH = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static final int REFRESH_EVERY_FRAMES = 4;
    private static net.minecraft.client.multiplayer.ClientLevel currentLevel;
    private static final RandomSource RANDOM = RandomSource.create(0);
    private static final CapturedMesh SECTION_MESH = new CapturedMesh();
    private static final CapturedMesh PART_MESH = new CapturedMesh();
    private static final SectionReplayCache REPLAY = new SectionReplayCache(64L * 1024 * 1024);
    public static void invalidateResources() { generation = -1; }
    public static void markDirty(int x, int y, int z) { long key = SectionPos.asLong(x, y, z); REPLAY.remove(key); DIRTY.add(key); }
    public static void markDirtyNow(int x, int y, int z) { long key = SectionPos.asLong(x, y, z); REPLAY.remove(key); DIRTY.addAndMoveToFirst(key); }
    public static void frame(Minecraft mc, float partial) {
        if (mc.player == null || mc.level == null) return;
        var sky = dev.skycraft.client.SkyClient.sky();
        // Meshes live in the host's scene and go with it; send nothing until the new scene is up.
        if (sky.loading()) { hostWasLoading = true; return; }
        if (hostWasLoading) {
            // Back from a load or the host menu: anything sent meanwhile may have been dropped.
            hostWasLoading = false;
            requeueExported();
        }
        boolean preview = dev.skycraft.client.RenderBackend.preview();
        if (generation != SkyLink.generation() || currentLevel != mc.level || sentWorld != sky.worldId || lastPreview != preview) {
            // A new host world (scene reload) or a new link: drop whatever the host still holds, then
            // send the atlas and every loaded section again, as the Fabric exporter does.
            if (!SkyLink.tryWriteRender(Proto.REN_CLEAR_ALL, ByteBuffer.allocate(0), null)) return;
            generation = SkyLink.generation(); currentLevel = mc.level; sentWorld = sky.worldId; lastPreview = preview;
            DIRTY.clear(); SENT.clear(); EXPORTED.clear(); REFRESH.clear(); REPLAY.clear(); TextureExporter.reset();
            dev.skycraft.SkyCraft.LOG.info("SkyCraft: host world {} — resending all Minecraft block meshes", sentWorld);
        }
        if (SkyLink.renderBackedUp()) {
            SkyLink.writeWorldEntities(java.util.List.of(), selection(mc));
            return;
        }
        if (!preview) {
            if (!TextureExporter.atlas(mc)) return;
            TextureExporter.animate();
        }
        if ((scanTick++ % 20) == 0) {
            int cx = mc.player.chunkPosition().x, cz = mc.player.chunkPosition().z;
            for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx + x, cz + z, false);
                if (chunk == null) continue;
                for (int section = 0; section < chunk.getSections().length; section++) {
                    int sy = chunk.getSectionYFromSectionIndex(section);
                    long key = SectionPos.asLong(cx + x, sy, cz + z);
                    if (!chunk.getSection(section).hasOnlyAir() && !EXPORTED.contains(key)) DIRTY.add(key);
                }
            }
        }
        if (DIRTY.isEmpty() && ++refreshTick % REFRESH_EVERY_FRAMES == 0) {
            if (REFRESH.isEmpty()) REFRESH.addAll(SENT);
            if (!REFRESH.isEmpty()) {
                long key = REFRESH.removeLong(REFRESH.size() - 1);
                var cached = REPLAY.get(key);
                if (cached == null) DIRTY.add(key);
                else if (!SkyLink.tryWriteRender(Proto.REN_SECTION, cached.header().duplicate(), cached.vertices().duplicate())
                    || !SkyLink.tryWriteRender(Proto.REN_SOLIDS, cached.solidHeader().duplicate(), cached.solids().duplicate())) REFRESH.add(key);
            }
        }
        long sectionDeadline = System.nanoTime() + 3_000_000;
        for (int count = 0; count < 2 && !DIRTY.isEmpty(); count++) {
            long section = DIRTY.removeFirstLong();
            if (!exportSection(mc, section)) { DIRTY.add(section); break; }
            if (System.nanoTime() >= sectionDeadline || SkyLink.renderBackedUp()) break;
        }
        // Raster preview already renders entities/particles through vanilla and shader-pack passes.
        if (!dev.skycraft.client.RenderBackend.preview()) EntityExporter.frame(mc, partial);
        SkyLink.writeWorldEntities(java.util.List.of(), selection(mc));
    }
    private static void requeueExported() {
        for (long key : SENT) DIRTY.add(key);
        REFRESH.clear();
    }
    private static boolean exportSection(Minecraft mc, long key) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        var chunk = mc.level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) return true;
        int index = mc.level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSections().length) return true;
        var section = chunk.getSection(index);
        var output = SECTION_MESH; output.reset();
        long[] solids = new long[64]; int solidCount = 0;
        var pos = new BlockPos.MutableBlockPos();
        var origin = new BlockPos(sx << 4, sy << 4, sz << 4);
        var dispatcher = mc.getBlockRenderer();
        var pose = new PoseStack();
        boolean visual = !dev.skycraft.client.RenderBackend.preview();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var state = section.getBlockState(x, y, z); if (state.isAir()) continue;
            pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!state.getCollisionShape(mc.level, pos).isEmpty()) { int bit = x + 16 * z + 256 * y; solids[bit >> 6] |= 1L << (bit & 63); solidCount++; }
            if (!visual) continue;
            if (!state.getFluidState().isEmpty()) {
                var fluid = PART_MESH; fluid.reset();
                fluid.flags = 128 | 8 | (ItemBlockRenderTypes.getRenderLayer(state.getFluidState()) == RenderType.translucent() ? 2 : 1);
                dispatcher.renderLiquid(pos, mc.level, fluid, state, state.getFluidState());
                output.append(fluid);
            }
            if (state.getRenderShape() != RenderShape.MODEL) continue;
            var model = dispatcher.getBlockModel(state);
            var data = mc.level.getModelDataManager().getAt(pos);
            if (data == null) data = net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
            var resolved = model.getModelData(mc.level, pos, state, data);
            RANDOM.setSeed(state.getSeed(pos));
            for (RenderType layer : model.getRenderTypes(state, RANDOM, resolved)) {
                var part = PART_MESH; part.reset(); part.flags = 8 | (layer == RenderType.translucent() ? 2 : 1);
                pose.pushPose(); pose.translate(x, y, z);
                dispatcher.getModelRenderer().tesselateBlock(mc.level, model, state, pos, pose, part, true, RANDOM, state.getSeed(pos), 0, resolved, layer);
                pose.popPose(); output.append(part);
            }
        }
        var header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(output.count()).flip();
        if (!SkyLink.tryWriteRender(Proto.REN_SECTION, header, output.bytes())) return false;
        if (output.count() == 0 && solidCount == 0) SENT.remove(key); else SENT.add(key);
        EXPORTED.add(key);
        var bits = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN); for (long word : solids) bits.putLong(word);
        var solidHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(solidCount).flip();
        bits.flip();
        if (!SkyLink.tryWriteRender(Proto.REN_SOLIDS, solidHeader, bits)) return false;
        if (output.count() > 0 || solidCount > 0) REPLAY.put(key, header, output.bytes(), solidHeader, bits);
        else REPLAY.remove(key);
        return true;
    }
    static void captureBlock(Minecraft mc, BlockPos pos, PoseStack pose, CaptureBuffers target) {
        var state = mc.level.getBlockState(pos);
        if (state.getRenderShape() != RenderShape.MODEL) return;
        var dispatcher = mc.getBlockRenderer(); var model = dispatcher.getBlockModel(state);
        var data = model.getModelData(mc.level, pos, state, net.neoforged.neoforge.client.model.data.ModelData.EMPTY);
        RANDOM.setSeed(state.getSeed(pos));
        for (var layer : model.getRenderTypes(state, RANDOM, data))
            dispatcher.getModelRenderer().tesselateBlock(mc.level, model, state, pos, pose, target.getBuffer(layer), true, RANDOM, state.getSeed(pos), 0, data, layer);
    }
    private static float[] selection(Minecraft mc) {
        if (mc.screen != null || !(mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit) || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return null;
        var pos = hit.getBlockPos();
        var shape = mc.level.getBlockState(pos).getShape(mc.level, pos);
        if (shape.isEmpty()) return new float[]{pos.getX(), pos.getY(), pos.getZ(), pos.getX()+1, pos.getY()+1, pos.getZ()+1};
        var b = shape.bounds().move(pos);
        return new float[]{(float)b.minX,(float)b.minY,(float)b.minZ,(float)b.maxX,(float)b.maxY,(float)b.maxZ};
    }
}
