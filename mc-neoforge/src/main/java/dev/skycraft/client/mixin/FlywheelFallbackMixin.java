package dev.skycraft.client.mixin;
import dev.skycraft.client.SkyClient;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Create's instanced draws bypass VertexConsumer; use its normal animated renderers while linked. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.api.visualization.VisualizationManager", remap = false)
public abstract class FlywheelFallbackMixin {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true, remap = false)
    private static void endcraft$captureFallback(Level level, CallbackInfoReturnable<Boolean> result) {
        if (SkyClient.linked()) result.setReturnValue(false);
    }
}
