package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skyrim draws the world. While linked, Minecraft renders nothing of its own level (no sky,
 * clouds, fog or terrain) so the overlay is just hand + HUD on a transparent background.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(
		method = "renderLevel",
		at = @At("HEAD"),
		cancellable = true
	)
	private void skycraft$skipLevel(CallbackInfo ci) {
		if (dev.skycraft.client.RenderBackend.meshOverlay()) {
			var mc = net.minecraft.client.Minecraft.getInstance();
			// Cancelling the world draw must not cancel its light queue or leave the
			// inventory's diffuse lights installed for the next first-person frame.
			mc.level.pollLightUpdates();
			mc.level.getChunkSource().getLightEngine().runLightUpdates();
			if (mc.level.effects().constantAmbientLight())
				com.mojang.blaze3d.platform.Lighting.setupNetherLevel();
			else com.mojang.blaze3d.platform.Lighting.setupLevel();
			com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1, 1, 1, 1);
			var target = mc.getMainRenderTarget();
			target.setClearColor(0, 0, 0, 0);
			target.clear(net.minecraft.client.Minecraft.ON_OSX);
			target.bindWrite(true);
			ci.cancel();
		}
	}

	/**
	 * Every block change (with the neighbouring sections whose faces it culls), chunk load and light
	 * update marks its 16^3 section for re-meshing into the host, as vanilla does for its own meshes.
	 */
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void skycraft$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		if (playerChanged) {
			dev.skycraft.client.render.WorldExporter.markDirtyNow(sectionX, sectionY, sectionZ);
		} else {
			dev.skycraft.client.render.WorldExporter.markDirty(sectionX, sectionY, sectionZ);
		}
	}
}
