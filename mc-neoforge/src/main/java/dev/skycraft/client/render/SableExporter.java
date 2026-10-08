package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import java.lang.reflect.Method;
import java.util.Collection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** Optional Sable 2.x API adapter; plots remain owned and simulated by Sable. */
final class SableExporter {
    private static Method container;
    private static boolean resolved, warned;
    static void capture(Minecraft mc, float partial, Vec3 origin, CaptureBuffers output) {
        try {
            if (!resolved) {
                resolved = true;
                if (!net.neoforged.fml.ModList.get().isLoaded("sable")) return;
                container = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer").getMethod("getContainer", Level.class);
            }
            if (container == null) return;
            Object c = container.invoke(null, mc.level); if (c == null) return;
            var ships = (java.util.List<?>) c.getClass().getMethod("getAllSubLevels").invoke(c);
            int shipsDrawn = 0, blocks = 0;
            for (Object ship : ships) {
                if ((boolean) ship.getClass().getMethod("isRemoved").invoke(ship)) continue;
                Object pose = ship.getClass().getMethod("renderPose", float.class).invoke(ship, partial);
                Method transform = pose.getClass().getMethod("transformPosition", Vec3.class);
                Object plot = ship.getClass().getMethod("getPlot").invoke(ship);
                Vec3 center = (Vec3) transform.invoke(pose, Vec3.atCenterOf((BlockPos) plot.getClass().getMethod("getCenterBlock").invoke(plot)));
                if (center.distanceToSqr(origin) > 96 * 96 || ++shipsDrawn > 8) continue;
                var holders = (Collection<?>) plot.getClass().getMethod("getLoadedChunks").invoke(plot);
                for (Object holder : holders) {
                    LevelChunk chunk = (LevelChunk) holder.getClass().getMethod("getChunk").invoke(holder);
                    for (int i = 0; i < chunk.getSections().length; i++) {
                        var section = chunk.getSection(i); if (section.hasOnlyAir()) continue;
                        int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
                        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                            if (section.getBlockState(x, y, z).isAir()) continue;
                            if (++blocks > 8192) return;
                            var pos = new BlockPos(chunk.getPos().getMinBlockX()+x, y0+y, chunk.getPos().getMinBlockZ()+z);
                            output.transform = p -> {
                                try { return ((Vec3) transform.invoke(pose, p.add(pos.getX(), pos.getY(), pos.getZ()))).subtract(origin); }
                                catch (ReflectiveOperationException e) { throw new IllegalStateException("Sable pose changed", e); }
                            };
                            WorldExporter.captureBlock(mc, pos, new PoseStack(), output);
                            var be = chunk.getBlockEntity(pos);
                            if (be != null) EntityExporter.capturePlotBlockEntity(mc, be, partial, output);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            if (!warned) { warned = true; dev.skycraft.SkyCraft.LOG.error("Sable mesh adapter unavailable for this build", e); }
        } finally { output.transform = java.util.function.UnaryOperator.identity(); }
    }
}
