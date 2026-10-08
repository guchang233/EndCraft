package dev.skycraft.client.mixin;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(BackupConfirmScreen.class)
public interface BackupConfirmAccess {
    @Accessor("onProceed") BackupConfirmScreen.Listener endcraft$onProceed();
}
