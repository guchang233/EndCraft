package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;

/**
 * The host draws textured quads only, so Minecraft's lines (F3+B hitboxes and debug lines) become
 * thin square prisms: four lit sides along each segment, coloured by the line's vertex colour on a
 * near-white block texture. Vertices arrive in pairs (VertexFormat.Mode.LINES).
 */
final class LineCapture implements VertexConsumer {
	private static final float HALF_WIDTH = 0.015F;
	private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("block/white_concrete");
	private final CapturedMesh out;
	private final float u, v;
	private final float[] first = new float[3];
	private final float[] current = new float[3];
	private int firstColor, color = -1, count;
	private boolean open;

	LineCapture(CapturedMesh out) {
		this.out = out;
		var sprite = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(WHITE);
		this.u = sprite.getU(0.5F);
		this.v = sprite.getV(0.5F);
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		commit();
		current[0] = x;
		current[1] = y;
		current[2] = z;
		color = -1;
		open = true;
		return this;
	}

	@Override
	public VertexConsumer setColor(int r, int g, int b, int a) {
		color = 0xFF000000 | r << 16 | g << 8 | b; // lines are drawn opaque
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		return this;
	}

	/** One segment, for callers drawing their own lines (EntityExporter, TerrainDebug). */
	void segment(double ax, double ay, double az, double bx, double by, double bz, int rgb) {
		addVertex((float) ax, (float) ay, (float) az).setColor(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, 255);
		addVertex((float) bx, (float) by, (float) bz).setColor(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, 255);
		commit();
	}

	/** The twelve edges of a box. */
	void box(double x0, double y0, double z0, double x1, double y1, double z1, int rgb) {
		double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
		int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
		for (int[] e : edges) segment(c[e[0]][0], c[e[0]][1], c[e[0]][2], c[e[1]][0], c[e[1]][1], c[e[1]][2], rgb);
	}

	private void commit() {
		if (!open) return;
		open = false;
		if (count++ % 2 == 0) {
			System.arraycopy(current, 0, first, 0, 3);
			firstColor = color;
			return;
		}
		prism(first, current, firstColor);
	}

	private void prism(float[] a, float[] b, int argb) {
		float dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
		float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1e-5F) return;
		dx /= length; dy /= length; dz /= length;
		// Two axes across the segment.
		float rx = Math.abs(dy) < 0.9F ? 0 : 1, ry = Math.abs(dy) < 0.9F ? 1 : 0;
		float px = dy * 0 - dz * ry, py = dz * rx - dx * 0, pz = dx * ry - dy * rx;
		float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
		px = px / pl * HALF_WIDTH; py = py / pl * HALF_WIDTH; pz = pz / pl * HALF_WIDTH;
		float qx = dy * pz - dz * py, qy = dz * px - dx * pz, qz = dx * py - dy * px;
		float[][] side = {{px, py, pz}, {qx, qy, qz}, {-px, -py, -pz}, {-qx, -qy, -qz}};
		int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, bl = argb & 0xFF;
		for (int i = 0; i < 4; i++) {
			float[] s0 = side[i], s1 = side[(i + 1) % 4];
			float nx = s0[0] + s1[0], ny = s0[1] + s1[1], nz = s0[2] + s1[2];
			vertex(a[0] + s0[0], a[1] + s0[1], a[2] + s0[2], r, g, bl, nx, ny, nz);
			vertex(b[0] + s0[0], b[1] + s0[1], b[2] + s0[2], r, g, bl, nx, ny, nz);
			vertex(b[0] + s1[0], b[1] + s1[1], b[2] + s1[2], r, g, bl, nx, ny, nz);
			vertex(a[0] + s1[0], a[1] + s1[1], a[2] + s1[2], r, g, bl, nx, ny, nz);
		}
	}

	private void vertex(float x, float y, float z, int r, int g, int b, float nx, float ny, float nz) {
		out.addVertex(x, y, z).setUv(u, v).setColor(r, g, b, 255).setUv2(0xF0, 0xF0).setNormal(nx, ny, nz);
	}
}
