package dev.skycraft.client.mixin;

import dev.skycraft.client.RenderBackend;
import dev.skycraft.client.TaczRefitView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @ModifyArg(method = "renderItemInHand", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"), index = 4)
    private int endcraft$refitPreviewLight(int packedLight) {
        // This is a model inspection screen; native terrain has no Minecraft block lighting.
        return RenderBackend.meshOverlay() && TaczRefitView.isRefit(Minecraft.getInstance().screen)
            ? LightTexture.FULL_BRIGHT : packedLight;
    }
}
