package dev.skycraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.skycraft.world.SableHostTerrain;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Sable's Rapier terrain is built from Minecraft blocks only; add the host's ground to it. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
public abstract class SableTerrainMixin {
    @WrapOperation(method = "handleChunkSectionAddition", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;addChunk(JIII[IZI)V"))
    private void skycraft$addHostTerrain(long scene, int x, int y, int z, int[] blocks, boolean terrain, int body, Operation<Void> original) {
        if (terrain) SableHostTerrain.sectionAdded(this, x, y, z, blocks);
        original.call(scene, x, y, z, blocks, terrain, body);
    }

    @Inject(method = "handleChunkSectionRemoval", at = @At("HEAD"), remap = false)
    private void skycraft$forgetHostTerrain(int x, int y, int z, CallbackInfo ci) {
        SableHostTerrain.sectionRemoved(this, x, y, z);
    }

    @Inject(method = "handleBlockChange", at = @At("TAIL"), remap = false)
    private void skycraft$restoreHostTerrain(SectionPos section, LevelChunkSection blocks, int x, int y, int z, BlockState previous, BlockState state, CallbackInfo ci) {
        SableHostTerrain.blockChanged(this, section, x, y, z);
    }

    @Inject(method = "tick", at = @At("TAIL"), remap = false)
    private void skycraft$syncHostTerrain(CallbackInfo ci) {
        SableHostTerrain.tick(this);
    }
}
