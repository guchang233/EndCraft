package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The "Loading terrain" screen waits for the player's chunk section to be compiled for
 * rendering. We never render Minecraft's level while linked, so don't wait for it.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public abstract class WaitingForPlayerChunkMixin {
	@Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
	private void skycraft$ready(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.linked()) {
			cir.setReturnValue(true);
		}
	}
}
