package dev.skycraft.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** TaCZ's refit preview is the live first-person hand, not a model rendered by its screen. */
public final class TaczRefitView {
    private static CameraType previous;
    private TaczRefitView() { }

    public static void opening(ScreenEvent.Opening event) {
        var mc = Minecraft.getInstance();
        boolean refit = RenderBackend.meshOverlay() && isRefit(event.getNewScreen());
        if (refit) {
            if (previous == null) previous = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        } else if (previous != null) {
            mc.options.setCameraType(previous);
            previous = null;
        }
    }

    public static boolean isRefit(Object screen) {
        // Keep TaCZ optional: no references to its classes in our constant pool.
        for (Class<?> type = screen == null ? null : screen.getClass(); type != null; type = type.getSuperclass())
            if (type.getName().equals("com.tacz.guns.client.gui.GunRefitScreen")) return true;
        return false;
    }
}
