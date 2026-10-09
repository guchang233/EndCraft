package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.platform.SdlKeyMap;
import dev.skycraft.client.mixin.KeyboardAccess;
import dev.skycraft.client.mixin.MouseAccess;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** The native protocol uses SDL scancodes; Minecraft 1.21.1 uses GLFW keycodes. */
public final class InputBridge {
    private static final boolean[] KEYS = new boolean[512];
    private static final boolean[] BUTTONS = new boolean[8];
    public static boolean isKeyDown(int key) { return key >= 0 && key < KEYS.length && KEYS[key]; }
    private static int modifiers() {
        int m = 0;
        if (isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT)) m |= GLFW.GLFW_MOD_SHIFT;
        if (isKeyDown(GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyDown(GLFW.GLFW_KEY_RIGHT_CONTROL)) m |= GLFW.GLFW_MOD_CONTROL;
        if (isKeyDown(GLFW.GLFW_KEY_LEFT_ALT) || isKeyDown(GLFW.GLFW_KEY_RIGHT_ALT)) m |= GLFW.GLFW_MOD_ALT;
        return m;
    }
    public static void drain(Minecraft mc) {
        SkyLink.drainInput((type, code, a, b, c) -> {
            long window = mc.getWindow().getWindow();
            var keyboard = (KeyboardAccess) mc.keyboardHandler;
            var mouse = (MouseAccess) mc.mouseHandler;
            switch (type) {
                case Proto.IN_KEY -> {
                    int key = SdlKeyMap.toGlfw(code);
                    if (key < 0 || key >= KEYS.length) break;
                    boolean previous = KEYS[key];
                    KEYS[key] = a != 0;
                    keyboard.endcraft$keyPress(window, key, GLFW.glfwGetKeyScancode(key), a == 0 ? GLFW.GLFW_RELEASE : previous ? GLFW.GLFW_REPEAT : GLFW.GLFW_PRESS, modifiers());
                }
                case Proto.IN_MOUSE_BUTTON -> {
                    int button = SdlKeyMap.mouseButton(code);
                    if (button < 0 || button >= BUTTONS.length) break;
                    BUTTONS[button] = a != 0;
                    mouse.endcraft$onPress(window, button, a == 0 ? GLFW.GLFW_RELEASE : GLFW.GLFW_PRESS, modifiers());
                }
                case Proto.IN_CURSOR -> mouse.endcraft$onMove(window, a, b);
                case Proto.IN_SCROLL -> mouse.endcraft$onScroll(window, 0, a / 120.0);
                case Proto.IN_TEXT -> { if (mc.screen != null) keyboard.endcraft$charTyped(window, a, modifiers()); }
                case Proto.IN_RELEASE_ALL -> releaseAll();
                case Proto.IN_HURT -> {
                    var server = mc.getSingleplayerServer();
                    if (server != null && mc.player != null) {
                        var id = mc.player.getUUID();
                        server.execute(() -> { var p = server.getPlayerList().getPlayer(id); if (p != null) dev.skycraft.combat.SkyCombat.hurtPlayer(p, code, a / 100f, b, c); });
                    }
                }
                case Proto.IN_OPEN_MENU -> { if (mc.player != null && mc.screen == null) {
                    releaseAll(); mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                } }
                case Proto.IN_INPUT_MODE -> mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.literal(a != 0 ? "MC input exclusive: on" : "MC input exclusive: off"), false);
                default -> { }
            }
        });
    }
    /** A key delivered exactly like a forwarded one (diagnostics). */
    static void injectKey(Minecraft mc, int key, boolean down) {
        if (key < 0 || key >= KEYS.length || KEYS[key] == down) return;
        KEYS[key] = down;
        ((KeyboardAccess) mc.keyboardHandler).endcraft$keyPress(mc.getWindow().getWindow(), key, GLFW.glfwGetKeyScancode(key),
            down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, modifiers());
    }
    public static void releaseAll() {
        var mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        for (int key = 0; key < KEYS.length; key++) if (KEYS[key]) {
            KEYS[key] = false;
            ((KeyboardAccess) mc.keyboardHandler).endcraft$keyPress(window, key, GLFW.glfwGetKeyScancode(key), GLFW.GLFW_RELEASE, modifiers());
        }
        for (int button = 0; button < BUTTONS.length; button++) if (BUTTONS[button]) {
            BUTTONS[button] = false;
            ((MouseAccess) mc.mouseHandler).endcraft$onPress(window, button, GLFW.GLFW_RELEASE, 0);
        }
    }
}
