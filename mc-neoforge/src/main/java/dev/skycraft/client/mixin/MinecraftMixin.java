package dev.skycraft.client.mixin;

import dev.skycraft.client.SkyClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void endcraft$hostFocus(CallbackInfoReturnable<Boolean> result) {
        // 1.21.1 gates mouse movement and grabbing on Minecraft's focus field.
        // The host has the real window; the hidden guest still consumes forwarded input.
        if (SkyClient.linked()) result.setReturnValue(true);
    }
	@Inject(method = "runTick", at = @At("HEAD"))
	private void skycraft$beginFrame(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.beginFrame();
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V", shift = At.Shift.AFTER)
	)
	private void skycraft$afterRender(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.afterRender();
	}

	@Inject(method = "runTick", at = @At("TAIL"))
	private void skycraft$pace(boolean advanceGameTime, CallbackInfo ci) {
		SkyClient.paceFrame();
	}
}
