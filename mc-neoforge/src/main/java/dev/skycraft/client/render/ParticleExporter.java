package dev.skycraft.client.render;

import dev.skycraft.client.mixin.ParticleEngineAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.phys.Vec3;

/** Reuses live particle renderers; positions they emit are camera-relative, not player-relative. */
final class ParticleExporter {
    static void capture(Minecraft mc, float partial, Vec3 origin, CaptureBuffers scene) {
        var camera = mc.gameRenderer.getMainCamera();
        var previous = scene.transform;
        Vec3 offset = camera.getPosition().subtract(origin);
        scene.transform = p -> p.add(offset);
        int count = 0;
        try {
            for (var entry : ((ParticleEngineAccess) mc.particleEngine).endcraft$particles().entrySet()) {
                var type = entry.getKey();
                // Custom render types can draw directly to GL with their own textures/programs.
                // Do not reinterpret those vertices as an atlas sprite.
                boolean terrain = type == ParticleRenderType.TERRAIN_SHEET;
                if (!terrain && type != ParticleRenderType.PARTICLE_SHEET_OPAQUE
                        && type != ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT
                        && type != ParticleRenderType.PARTICLE_SHEET_LIT) continue;
                var buffer = scene.textured(terrain ? TextureAtlas.LOCATION_BLOCKS : TextureAtlas.LOCATION_PARTICLES,
                    terrain || type == ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT);
                for (var particle : entry.getValue()) {
                    if (!particle.isAlive() || particle.getPos().distanceToSqr(origin) > 48 * 48) continue;
                    if (++count > 2048) return;
                    particle.render(buffer, camera, partial);
                    if (buffer instanceof CapturedMesh mesh) mesh.finish();
                }
            }
        } finally { scene.transform = previous; }
    }
}
