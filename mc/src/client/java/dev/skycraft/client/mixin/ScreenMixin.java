package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Skyrim keeps running while a Minecraft screen (pause menu, options, inventory) is open, so the
 * Minecraft world must too: arrows keep flying and enemy hits still land.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "extractTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void endcraft$lightMenuBackdrop(GuiGraphicsExtractor graphics, CallbackInfo ci) {
        if (SkyClient.linked()) {
            graphics.fillGradient(0, 0, width, height, 0x33000000, 0x33000000);
            ci.cancel();
        }
    }

	@Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true)
	private void skycraft$neverPauseWhileLinked(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.linked()) {
			cir.setReturnValue(false);
		}
	}
}
