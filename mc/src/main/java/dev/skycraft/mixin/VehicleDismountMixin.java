package dev.skycraft.mixin;

import dev.skycraft.world.TerrainDismount;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({AbstractBoat.class, AbstractMinecart.class})
public abstract class VehicleDismountMixin {
    @Inject(method = "getDismountLocationForPassenger", at = @At("RETURN"), cancellable = true)
    private void endcraft$nativeLanding(LivingEntity passenger, CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(TerrainDismount.locate((Entity) (Object) this, passenger, cir.getReturnValue()));
    }
}
