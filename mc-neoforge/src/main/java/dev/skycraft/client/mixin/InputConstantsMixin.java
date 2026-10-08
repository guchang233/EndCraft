package dev.skycraft.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.skycraft.client.InputBridge;
import dev.skycraft.client.SkyClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keyboard state and mouse capture come from Skyrim while linked, not from SDL. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void skycraft$isKeyDown(long window, int key, CallbackInfoReturnable<Boolean> cir) {
		if (SkyClient.tookOver()) {
			cir.setReturnValue(InputBridge.isKeyDown(key));
		}
	}

    @Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
    private static void endcraft$virtualMouse(long window, int mode, double x, double y, CallbackInfo ci) {
        if (SkyClient.tookOver()) ci.cancel();
    }
}
