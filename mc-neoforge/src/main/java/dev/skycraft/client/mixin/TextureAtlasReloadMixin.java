package dev.skycraft.client.mixin;
import dev.skycraft.client.render.WorldExporter;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Atlas UVs and texture contents can change while OpenGL reuses the same texture id. */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasReloadMixin {
    @Inject(method = "upload", at = @At("RETURN"))
    private void endcraft$invalidate(CallbackInfo ci) { WorldExporter.invalidateResources(); }
}
