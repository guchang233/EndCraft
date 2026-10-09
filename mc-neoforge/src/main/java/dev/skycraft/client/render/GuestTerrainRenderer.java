package dev.skycraft.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.world.SkyCollision;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * A LAN guest has no host game drawing the world, so it draws the host's terrain itself: the
 * collision triangles the server sent (SkyTerrainSync), shaded by material and facing. One GPU
 * buffer per region, rebuilt when that region changes.
 */
public final class GuestTerrainRenderer {
	private static final double RANGE = 160.0;
	private static final float[] LIGHT = normalize(0.35F, 1.0F, 0.55F);
	/** Colour per Proto.DIG_* material. */
	private static final int[] MATERIAL_COLOR = {
		0x8C8A7E, 0x6A8F3C, 0x7A5C3A, 0x8A8A88, 0x7D7D7D, 0xEEEEEE, 0xA8C8E8, 0xD8C88A,
		0x8C8682, 0x5A4A3A, 0x6B5133, 0x5A4630, 0xC8BFA0, 0xA08050, 0x9AA0A8, 0xB8D8E0,
		0x6A8040, 0xB0A090, 0xE0D8C0, 0xDDDDDD, 0x606060, 0x404040};
	private static final int TERRAIN_COLOR = 0x7D8A6A;

	private record Mesh(long revision, VertexBuffer buffer, int originX, int originY, int originZ) {
	}

	private static final Map<Long, Mesh> MESHES = new HashMap<>();
	private static int meshEpoch = Integer.MIN_VALUE;

	private GuestTerrainRenderer() {
	}

	public static void render(RenderLevelStageEvent event) {
		if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
			return;
		}
		if (SkyLink.active() || !SkyCollision.active()) {
			clear(); // the host game draws this terrain, or there is none
			return;
		}
		if (meshEpoch != SkyCollision.epoch()) {
			clear();
			meshEpoch = SkyCollision.epoch();
		}
		Vec3 camera = event.getCamera().getPosition();
		var shader = GameRenderer.getPositionColorShader();
		if (shader == null) {
			return;
		}
		RenderSystem.enableDepthTest();
		RenderSystem.disableCull(); // the triangles' winding is not trusted
		for (var entry : SkyCollision.rawTris().entrySet()) {
			SkyCollision.RawTris tris = entry.getValue();
			double dx = tris.minX() + 4 - camera.x, dz = tris.minZ() + 4 - camera.z;
			if (dx * dx + dz * dz > RANGE * RANGE) {
				continue;
			}
			Mesh mesh = MESHES.get(entry.getKey());
			if (mesh == null || mesh.revision() != tris.revision()) {
				if (mesh != null && mesh.buffer() != null) mesh.buffer().close();
				mesh = build(tris);
				MESHES.put(entry.getKey(), mesh);
			}
			if (mesh.buffer() == null) {
				continue;
			}
			Matrix4f modelView = new Matrix4f(event.getModelViewMatrix())
				.translate((float) (mesh.originX() - camera.x), (float) (mesh.originY() - camera.y), (float) (mesh.originZ() - camera.z));
			mesh.buffer().bind();
			mesh.buffer().drawWithShader(modelView, event.getProjectionMatrix(), shader);
		}
		VertexBuffer.unbind();
		RenderSystem.enableCull();
	}

	private static Mesh build(SkyCollision.RawTris tris) {
		int ox = tris.minX(), oy = tris.minY(), oz = tris.minZ();
		BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		float[] v = tris.vertices();
		for (int i = 0; i < tris.flags().length; i++) {
			int flags = tris.flags()[i];
			if ((flags & (Proto.TRI_GHOST | Proto.TRI_STAIR_HELPER)) != 0) {
				continue; // dug-away surfaces and invisible stair ramps
			}
			int o = i * 9;
			float ux = v[o + 3] - v[o], uy = v[o + 4] - v[o + 1], uz = v[o + 5] - v[o + 2];
			float wx = v[o + 6] - v[o], wy = v[o + 7] - v[o + 1], wz = v[o + 8] - v[o + 2];
			float[] n = normalize(uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx);
			// Two-sided: light whichever side faces up-ish.
			float facing = Math.abs(n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]);
			float shade = 0.55F + 0.45F * facing;
			int material = (flags >>> Proto.TRI_MATERIAL_SHIFT) & 0xFF;
			int color = material > 0 && material < MATERIAL_COLOR.length ? MATERIAL_COLOR[material]
				: (flags & Proto.TRI_TERRAIN) != 0 ? TERRAIN_COLOR : MATERIAL_COLOR[0];
			int r = (int) (((color >> 16) & 0xFF) * shade), g = (int) (((color >> 8) & 0xFF) * shade), b = (int) ((color & 0xFF) * shade);
			for (int k = 0; k < 3; k++) {
				builder.addVertex(v[o + k * 3] - ox, v[o + k * 3 + 1] - oy, v[o + k * 3 + 2] - oz).setColor(r, g, b, 255);
			}
		}
		var data = builder.build();
		if (data == null) {
			return new Mesh(tris.revision(), null, ox, oy, oz);
		}
		VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
		buffer.bind();
		buffer.upload(data);
		VertexBuffer.unbind();
		return new Mesh(tris.revision(), buffer, ox, oy, oz);
	}

	private static void clear() {
		if (MESHES.isEmpty()) {
			return;
		}
		for (Mesh mesh : MESHES.values()) {
			if (mesh.buffer() != null) mesh.buffer().close();
		}
		MESHES.clear();
	}

	private static float[] normalize(float x, float y, float z) {
		float length = (float) Math.sqrt(x * x + y * y + z * z);
		return length < 1e-6F ? new float[] {0, 1, 0} : new float[] {x / length, y / length, z / length};
	}
}
