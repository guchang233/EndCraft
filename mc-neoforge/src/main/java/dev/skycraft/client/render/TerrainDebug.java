package dev.skycraft.client.render;

import com.mojang.blaze3d.platform.InputConstants;
import dev.skycraft.world.SkyCollision;
import dev.skycraft.world.SkyTri;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * The quote key shows, in the host game, the terrain triangles the host sends Minecraft: what
 * players and entities actually collide with. Drawn as wireframe in the scene mesh.
 */
public final class TerrainDebug {
	public static final KeyMapping KEY = new KeyMapping("key.endcraft.host_terrain", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_APOSTROPHE, "key.categories.endcraft");
	private static final double RADIUS = 24.0, HEIGHT = 12.0;
	/** 72 vertices a triangle; the host takes at most 200000 vertices in one mesh, shared with ships. */
	private static final int MAX_TRIANGLES = 1500;
	private static final int WALKABLE = 0x40FF70, OBJECT_WALKABLE = 0x40C8FF, STEEP = 0xFF8030, STAIR_HELPER = 0xFFE040;
	private static boolean enabled;

	private TerrainDebug() {
	}

	public static void tick(Minecraft minecraft) {
		while (KEY.consumeClick()) {
			enabled = !enabled;
			minecraft.gui.setOverlayMessage(Component.literal(enabled ? "终末地地形三角面：显示" : "终末地地形三角面：隐藏"), false);
		}
	}

	static void capture(Vec3 origin, CaptureBuffers scene) {
		if (!enabled) {
			return;
		}
		List<SkyTri> tris = new ArrayList<>();
		SkyCollision.trianglesNear(new AABB(origin.x - RADIUS, origin.y - HEIGHT, origin.z - RADIUS, origin.x + RADIUS, origin.y + HEIGHT, origin.z + RADIUS), tris);
		LineCapture lines = scene.lines();
		int drawn = 0;
		for (SkyTri t : tris) {
			if (++drawn > MAX_TRIANGLES) break;
			int color = t.stairHelper ? STAIR_HELPER : !t.walkable ? STEEP : t.terrain ? WALKABLE : OBJECT_WALKABLE;
			double ax = t.ax - origin.x, ay = t.ay - origin.y, az = t.az - origin.z;
			double bx = t.bx - origin.x, by = t.by - origin.y, bz = t.bz - origin.z;
			double cx = t.cx - origin.x, cy = t.cy - origin.y, cz = t.cz - origin.z;
			lines.segment(ax, ay, az, bx, by, bz, color);
			lines.segment(bx, by, bz, cx, cy, cz, color);
			lines.segment(cx, cy, cz, ax, ay, az, color);
		}
	}
}
