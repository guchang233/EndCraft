package dev.skycraft.mixin;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.world.level.Explosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla block destruction and proxy damage; never synthesize native crater blocks. */
@Mixin(Explosion.class)
public abstract class ServerExplosionMixin {
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private float radius;
    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private double x, y, z;
    @Inject(method = "explode", at = @At("RETURN"))
    private void skycraft$tellHost(CallbackInfo ci) {
        if (!SkyLink.active()) return;
        SkyLink.pushEvent(Proto.EV_EXPLOSION, 0, (float) x, (float) y, (float) z, this.radius, 0);
    }
}
