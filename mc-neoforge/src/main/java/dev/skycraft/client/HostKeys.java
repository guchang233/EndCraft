package dev.skycraft.client;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import dev.skycraft.SkyCraft;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft keys the host module does not forward (it forwards a fixed set, F5 the only function
 * key): F1 hides the HUD, F3 opens the debug screen and its combinations (F3+B hitboxes, with B
 * forwarded as usual). Read from the keyboard while the host game is in front and playing, which
 * is exactly when the host forwards keys, and delivered like forwarded keys.
 */
final class HostKeys {
	private static final int[][] KEYS = {{0x70, GLFW.GLFW_KEY_F1}, {0x72, GLFW.GLFW_KEY_F3}}; // VK_F1, VK_F3
	private static final MethodHandle GET_ASYNC_KEY_STATE;

	static {
		MethodHandle handle = null;
		try {
			SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
			handle = Linker.nativeLinker().downcallHandle(user32.find("GetAsyncKeyState").orElseThrow(), FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
		} catch (Throwable t) {
			SkyCraft.LOG.warn("SkyCraft: F1/F3 from the host game unavailable", t);
		}
		GET_ASYNC_KEY_STATE = handle;
	}

	private HostKeys() {
	}

	/** Called once a frame while linked, after the host's own input. */
	static void poll(Minecraft minecraft) {
		var sky = SkyClient.sky();
		// Same window as the host's forwarding: in game, focused (no host menu), not loading.
		boolean active = GET_ASYNC_KEY_STATE != null && sky.inGame() && !sky.menuOpen() && !sky.loading();
		for (int[] key : KEYS) {
			boolean down = active && pressed(key[0]);
			InputBridge.injectKey(minecraft, key[1], down);
		}
	}

	private static boolean pressed(int virtualKey) {
		try {
			return ((short) GET_ASYNC_KEY_STATE.invokeExact(virtualKey) & 0x8000) != 0;
		} catch (Throwable t) {
			return false;
		}
	}
}
