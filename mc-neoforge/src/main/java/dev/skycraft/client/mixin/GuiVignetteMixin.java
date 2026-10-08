package dev.skycraft.client.mixin;
import dev.skycraft.client.SkyClient;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * The 1.21.1 vignette blends with (ZERO, ONE_MINUS_SRC_COLOR, ONE, ZERO): it writes alpha 1 over
 * the whole screen. While linked the host draws the world, so the overlay would turn opaque black.
 */
@Mixin(Gui.class)
public abstract class GuiVignetteMixin {
    @Inject(method = "renderVignette", at = @At("HEAD"), cancellable = true)
    private void endcraft$skipVignette(GuiGraphics graphics, Entity camera, CallbackInfo ci) {
        if (SkyClient.linked()) ci.cancel();
    }
}
