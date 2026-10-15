package dev.skycraft.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.skycraft.link.Proto;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.UnaryOperator;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Capture old-style VertexConsumer quads into the unchanged protocol-12 vertex layout. */
final class CapturedMesh implements VertexConsumer {
    private ByteBuffer data = ByteBuffer.allocateDirect(1024).order(ByteOrder.LITTLE_ENDIAN);
    static final UnaryOperator<Vec3> IDENTITY = UnaryOperator.identity();
    private static final int[] TRIANGLE_ORDER = {0, 1, 2};
    private static final int[] QUAD_ORDER = {0, 1, 2, 0, 2, 3};
    private final float[][] quad = new float[4][8];
    private int pending, vertices;
    private final boolean triangles;
    CapturedMesh() { this(VertexFormat.Mode.QUADS); }
    CapturedMesh(VertexFormat.Mode mode) {
        if (mode != VertexFormat.Mode.QUADS && mode != VertexFormat.Mode.TRIANGLES)
            throw new IllegalArgumentException("Unsupported primitive: " + mode);
        triangles = mode == VertexFormat.Mode.TRIANGLES;
    }
    private boolean open;
    int flags = 1 | 8;
    UnaryOperator<Vec3> transform = IDENTITY;
    private float x, y, z, u, v, nx, ny, nz;
    private int color = -1, light = 0x00f000f0;
    int count() { finish(); return vertices; }
    void reset() {
        data.clear(); pending = vertices = 0; open = false;
        transform = IDENTITY; flags = 1 | 8;
        x = y = z = u = v = nx = ny = nz = 0;
        color = -1; light = 0x00f000f0;
    }
    ByteBuffer bytes() { finish(); return data.duplicate().flip().order(ByteOrder.LITTLE_ENDIAN); }
    void append(CapturedMesh other) {
        finish(); ByteBuffer source = other.bytes(); ensure(source.remaining()); data.put(source); vertices += other.count();
    }
    private void ensure(int count) {
        if (data.remaining() >= count) return;
        ByteBuffer larger = ByteBuffer.allocateDirect(Math.max(data.capacity() * 2, data.position() + count)).order(ByteOrder.LITTLE_ENDIAN);
        data.flip(); larger.put(data); data = larger;
    }
    private void commit() {
        if (!open) return;
        float[] q = quad[pending++];
        if (transform == IDENTITY) { q[0] = x; q[1] = y; q[2] = z; }
        else {
            Vec3 position = transform.apply(new Vec3(x, y, z));
            q[0] = (float) position.x; q[1] = (float) position.y; q[2] = (float) position.z;
        }
        q[3] = u; q[4] = v; q[5] = Float.intBitsToFloat(color); q[6] = Float.intBitsToFloat(light);
        int face = nx == 0 && ny == 0 && nz == 0 ? 0 : Direction.getNearest(nx, ny, nz).ordinal() + 1;
        q[7] = Float.intBitsToFloat(flags | face << 4);
        open = false;
        if (pending == (triangles ? 3 : 4)) {
            int count = triangles ? 3 : 6;
            ensure(count * Proto.REN_VERTEX_BYTES);
            for (int index : triangles ? TRIANGLE_ORDER : QUAD_ORDER) {
                float[] a = quad[index]; int argb = Float.floatToRawIntBits(a[5]), packed = Float.floatToRawIntBits(a[6]);
                data.putFloat(a[0]).putFloat(a[1]).putFloat(a[2]).putFloat(a[3]).putFloat(a[4]);
                data.put((byte) (argb >>> 16)).put((byte) (argb >>> 8)).put((byte) argb).put((byte) (argb >>> 24));
                data.putInt((packed >>> 4 & 15) | (packed >>> 20 & 15) << 8).putInt(Float.floatToRawIntBits(a[7]));
            }
            vertices += count; pending = 0;
        }
    }
    void finish() { commit(); }
    @Override public VertexConsumer addVertex(float x, float y, float z) {
        commit(); this.x = x; this.y = y; this.z = z; color = -1; light = 0x00f000f0; nx = ny = nz = 0; open = true; return this;
    }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { color = a << 24 | r << 16 | g << 8 | b; return this; }
    @Override public VertexConsumer setUv(float u, float v) { this.u = u; this.v = v; return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { light = u | v << 16; return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { nx = x; ny = y; nz = z; return this; }
}
