package dev.skycraft.client.mixin;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(KeyboardHandler.class)
public interface KeyboardAccess {
    @Invoker("keyPress") void endcraft$keyPress(long window, int key, int scan, int action, int modifiers);
    @Invoker("charTyped") void endcraft$charTyped(long window, int codepoint, int modifiers);
}
