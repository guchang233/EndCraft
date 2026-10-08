package dev.skycraft.client.render;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.skycraft.link.Proto;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
/** Captures Minecraft/NeoForge entity and block-entity renderers, including Create's fallback BERs. */
final class EntityExporter {
    static void frame(Minecraft mc, float partial) {
        var dispatcher = mc.getEntityRenderDispatcher();
        dispatcher.prepare(mc.level, mc.gameRenderer.getMainCamera(), mc.crosshairPickEntity);
        mc.getBlockEntityRenderDispatcher().prepare(mc.level, mc.gameRenderer.getMainCamera(), mc.hitResult);
        var avatar = new CaptureBuffers();
        if (!mc.options.getCameraType().isFirstPerson() && !mc.player.isSpectator())
            dispatcher.render(mc.player, 0, 0, 0, mc.player.getYRot(), partial, new PoseStack(), avatar, dispatcher.getPackedLightCoords(mc.player, partial));
        avatar.send(Proto.REN_AVATAR, null);
        Vec3 origin = mc.player.position();
        var scene = new CaptureBuffers(); int count = 0;
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player || entity instanceof dev.skycraft.combat.SkyrimActorEntity || entity.distanceToSqr(origin) > 64 * 64 || ++count > 96) continue;
            Vec3 position = entity.getPosition(partial).subtract(origin);
            dispatcher.render(entity, position.x, position.y, position.z, entity.getYRot(), partial, new PoseStack(), scene, dispatcher.getPackedLightCoords(entity, partial));
        }
        int cx = mc.player.chunkPosition().x, cz = mc.player.chunkPosition().z, bes = 0;
        for (int z = -3; z <= 3; z++) for (int x = -3; x <= 3; x++) {
            var chunk = mc.level.getChunkSource().getChunk(cx + x, cz + z, false); if (chunk == null) continue;
            for (var be : chunk.getBlockEntities().values()) {
                if (be.getBlockPos().distToCenterSqr(origin) > 48 * 48 || ++bes > 256) continue;
                var pose = new PoseStack(); pose.translate(be.getBlockPos().getX() - origin.x, be.getBlockPos().getY() - origin.y, be.getBlockPos().getZ() - origin.z);
                mc.getBlockEntityRenderDispatcher().render(be, partial, pose, scene);
            }
        }
        SableExporter.capture(mc, partial, origin, scene);
        scene.send(Proto.REN_SCENE, origin);
    }
    static <E extends net.minecraft.world.level.block.entity.BlockEntity> void capturePlotBlockEntity(
            Minecraft mc, E entity, float partial, CaptureBuffers output) {
        var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null || !entity.hasLevel() || !entity.getType().isValid(entity.getBlockState())) return;
        // The regular dispatcher culls using the main-world camera. Plot coordinates are remote,
        // and Sable normally supplies a local camera from LevelRenderer, which we replace.
        // The ship distance/budget was checked before this call; capture the local renderer directly.
        renderer.render(entity, partial, new PoseStack(), output,
            net.minecraft.client.renderer.LevelRenderer.getLightColor(entity.getLevel(), entity.getBlockPos()),
            net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
    }
}
