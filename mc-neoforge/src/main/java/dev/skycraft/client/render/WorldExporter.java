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
    private static int generation = -1, scanTick;
    private static net.minecraft.client.multiplayer.ClientLevel currentLevel;
    private static final RandomSource RANDOM = RandomSource.create(0);
    public static void invalidateResources() { generation = -1; }
    public static void markDirty(int x, int y, int z) { DIRTY.add(SectionPos.asLong(x, y, z)); }
    public static void markDirtyNow(int x, int y, int z) { DIRTY.addAndMoveToFirst(SectionPos.asLong(x, y, z)); }
    public static void frame(Minecraft mc, float partial) {
        if (mc.player == null || mc.level == null) return;
        if (generation != SkyLink.generation() || currentLevel != mc.level) {
            generation = SkyLink.generation(); currentLevel = mc.level;
            DIRTY.clear(); SENT.clear(); TextureExporter.reset();
        }
        if (!TextureExporter.atlas(mc)) return;
        TextureExporter.animate();
        if ((scanTick++ % 20) == 0) {
            int cx = mc.player.chunkPosition().x, cz = mc.player.chunkPosition().z;
            for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx + x, cz + z, false);
                if (chunk == null) continue;
                for (int section = 0; section < chunk.getSections().length; section++) {
                    int sy = chunk.getSectionYFromSectionIndex(section);
                    long key = SectionPos.asLong(cx + x, sy, cz + z);
                    if (!chunk.getSection(section).hasOnlyAir() || SENT.contains(key)) DIRTY.add(key);
                }
            }
        }
        for (int count = 0; count < 2 && !DIRTY.isEmpty(); count++) {
            long section = DIRTY.removeFirstLong();
            if (!exportSection(mc, section)) { DIRTY.add(section); break; }
        }
        EntityExporter.frame(mc, partial);
        SkyLink.writeWorldEntities(java.util.List.of(), selection(mc));
    }
    private static boolean exportSection(Minecraft mc, long key) {
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        var chunk = mc.level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) return true;
        int index = mc.level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= chunk.getSections().length) return true;
        var section = chunk.getSection(index);
        var output = new CapturedMesh();
        long[] solids = new long[64]; int solidCount = 0;
        var pos = new BlockPos.MutableBlockPos();
        var origin = new BlockPos(sx << 4, sy << 4, sz << 4);
        var dispatcher = mc.getBlockRenderer();
        var pose = new PoseStack();
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            var state = section.getBlockState(x, y, z); if (state.isAir()) continue;
            pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
            if (!state.getCollisionShape(mc.level, pos).isEmpty()) { int bit = x + 16 * z + 256 * y; solids[bit >> 6] |= 1L << (bit & 63); solidCount++; }
            if (!state.getFluidState().isEmpty()) {
                var fluid = new CapturedMesh();
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
                var part = new CapturedMesh(); part.flags = 8 | (layer == RenderType.translucent() ? 2 : 1);
                pose.pushPose(); pose.translate(x, y, z);
                dispatcher.getModelRenderer().tesselateBlock(mc.level, model, state, pos, pose, part, true, RANDOM, state.getSeed(pos), 0, resolved, layer);
                pose.popPose(); output.append(part);
            }
        }
        var header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(output.count()).flip();
        if (!SkyLink.tryWriteRender(Proto.REN_SECTION, header, output.bytes())) return false;
        if (output.count() == 0) SENT.remove(key); else SENT.add(key);
        var bits = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN); for (long word : solids) bits.putLong(word);
        var solidHeader = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(sx).putInt(sy).putInt(sz).putInt(solidCount).flip();
        return SkyLink.tryWriteRender(Proto.REN_SOLIDS, solidHeader, bits.flip());
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
