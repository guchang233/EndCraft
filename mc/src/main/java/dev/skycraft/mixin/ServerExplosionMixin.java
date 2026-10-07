package dev.skycraft.mixin;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep vanilla block destruction and proxy damage; never synthesize native crater blocks. */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Inject(method = "explode", at = @At("RETURN"))
    private void skycraft$tellHost(CallbackInfoReturnable<Integer> cir) {
        if (!SkyLink.active()) return;
        ServerExplosion self = (ServerExplosion) (Object) this;
        var center = self.center();
        SkyLink.pushEvent(Proto.EV_EXPLOSION, 0, (float) center.x, (float) center.y, (float) center.z, self.radius(), 0);
    }
}
