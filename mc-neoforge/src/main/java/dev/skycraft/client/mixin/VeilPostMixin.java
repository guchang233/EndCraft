package dev.skycraft.client.mixin;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Veil (bundled with Sable) composites the main target through an RGB16F buffer after the level,
 * writing alpha 1 everywhere. While linked the level is skipped and the overlay must stay
 * transparent, otherwise the host sees a black screen behind the HUD.
 */
@Pseudo
@Mixin(targets = "foundry.veil.api.client.render.VeilRenderSystem", remap = false)
public abstract class VeilPostMixin {
    @Inject(method = "renderPost", at = @At("HEAD"), cancellable = true, remap = false)
    private static void endcraft$keepOverlayTransparent(CallbackInfo ci) {
        if (SkyClient.linked()) ci.cancel();
    }
}
