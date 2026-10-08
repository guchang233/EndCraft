package dev.skycraft.client.mixin;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(MouseHandler.class)
public interface MouseAccess {
    @Invoker("onPress") void endcraft$onPress(long window, int button, int action, int modifiers);
    @Invoker("onMove") void endcraft$onMove(long window, double x, double y);
    @Invoker("onScroll") void endcraft$onScroll(long window, double x, double y);
}
