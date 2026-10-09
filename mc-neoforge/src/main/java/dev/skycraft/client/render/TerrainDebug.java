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
	/**
	 * 54 vertices a triangle. The scene is re-sent every frame through the shared render ring, so
	 * this stays well below the host's 200000-vertex mesh limit.
	 */
	private static final int MAX_TRIANGLES = 800;
	/** The edges lie on the host's own ground: lift them toward the player and draw them thick. */
	private static final double LIFT = 0.05;
	private static final float HALF_WIDTH = 0.035F;
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
		LineCapture lines = scene.lines(HALF_WIDTH);
		int drawn = 0;
		for (SkyTri t : tris) {
			if (++drawn > MAX_TRIANGLES) break;
			int color = t.stairHelper ? STAIR_HELPER : !t.walkable ? STEEP : t.terrain ? WALKABLE : OBJECT_WALKABLE;
			// Lift along the normal, on the player's side of the surface (the winding isn't trusted).
			double side = t.nx * (origin.x - t.ax) + t.ny * (origin.y + 1.0 - t.ay) + t.nz * (origin.z - t.az) >= 0 ? LIFT : -LIFT;
			double ox = t.nx * side - origin.x, oy = t.ny * side - origin.y, oz = t.nz * side - origin.z;
			double ax = t.ax + ox, ay = t.ay + oy, az = t.az + oz;
			double bx = t.bx + ox, by = t.by + oy, bz = t.bz + oz;
			double cx = t.cx + ox, cy = t.cy + oy, cz = t.cz + oz;
			lines.segment(ax, ay, az, bx, by, bz, color);
			lines.segment(bx, by, bz, cx, cy, cz, color);
			lines.segment(cx, cy, cz, ax, ay, az, color);
		}
	}
}
