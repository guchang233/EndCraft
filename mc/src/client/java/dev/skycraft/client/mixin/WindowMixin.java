package dev.skycraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The MC window is hidden while linked; Skyrim has the real focus, so pretend we do too. */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void skycraft$focused(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.tookOver()) {
			// Focused while Skyrim is connected; if Skyrim goes away, act unfocused so MC
			// never tries to grab the (hidden) mouse.
			cir.setReturnValue(SkyClient.linked());
		}
	}

	@Inject(method = "isIconified", at = @At("HEAD"), cancellable = true)
	private void skycraft$notIconified(CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.linked()) {
			cir.setReturnValue(false);
		}
	}
}
