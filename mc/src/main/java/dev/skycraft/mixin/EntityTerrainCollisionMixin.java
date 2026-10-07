package dev.skycraft.mixin;

import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SmoothTerrainCollision;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityTerrainCollisionMixin {
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void endcraft$sharedTerrain(Vec3 requested, CallbackInfoReturnable<Vec3> cir) {
        Entity entity = (Entity) (Object) this;
        if (!entity.noPhysics && SkyCollision.usesSmoothCollider(entity))
            cir.setReturnValue(SmoothTerrainCollision.collide(entity, cir.getReturnValue()));
    }
}
