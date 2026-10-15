package dev.skycraft.client.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.skycraft.link.Proto;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CapturedMeshTest {
    @Test void reuseRetainsExpandedStorageAcrossLargeFrames() throws Exception {
        var mesh = new CapturedMesh();
        for (int i = 0; i < 4000; i++) mesh.addVertex(i, 0, 0);
        mesh.finish();
        var field = CapturedMesh.class.getDeclaredField("data"); field.setAccessible(true);
        Object expanded = field.get(mesh);
        mesh.reset();
        for (int i = 0; i < 4000; i++) mesh.addVertex(i, 0, 0);
        assertEquals(6000, mesh.count()); assertSame(expanded, field.get(mesh));
    }
    @Test void reuseDropsIncompleteVerticesAndOldTransforms() {
        var mesh = new CapturedMesh(); mesh.transform = p -> p.add(99, 99, 99);
        mesh.addVertex(100, 100, 100); mesh.reset();
        for (int i = 0; i < 4; i++) mesh.addVertex(i, 2, 3);
        assertEquals(6, mesh.count()); assertEquals(0, mesh.bytes().getFloat(0));
        mesh.reset(); assertEquals(0, mesh.count()); assertEquals(0, mesh.bytes().remaining());
    }
    @Test void trianglesAreNotRegroupedAsQuads() {
        var mesh = new CapturedMesh(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES);
        for (int i = 0; i < 6; i++) mesh.addVertex(i, 0, 0);
        assertEquals(6, mesh.count());
        for (int i = 0; i < 6; i++) assertEquals(i, mesh.bytes().getFloat(i * Proto.REN_VERTEX_BYTES));
    }
    @Test void quadsBecomeTrianglesWithProtocolColorAndLighting() {
        var mesh = new CapturedMesh();
        for (int i = 0; i < 4; i++)
            mesh.addVertex(i, 2, 3).setUv(.25f, .5f).setColor(17, 34, 51, 68).setUv2(80, 160).setNormal(0, 1, 0);
        assertEquals(6, mesh.count());
        var bytes = mesh.bytes();
        assertEquals(6 * Proto.REN_VERTEX_BYTES, bytes.remaining());
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int i = 0; i < 6; i++) {
            int offset = i * Proto.REN_VERTEX_BYTES;
            assertEquals(order[i], bytes.getFloat(offset));
            assertEquals(0x44332211, bytes.getInt(offset + 20));
            assertEquals(5 | 10 << 8, bytes.getInt(offset + 24));
            assertEquals(2, bytes.getInt(offset + 28) >> 4 & 7); // UP ordinal + 1
        }
    }
    @Test void remotePlotTranslationPreservesLocalDetailBeforeFloatPacking() {
        var mesh = new CapturedMesh();
        var plot = new Vec3(30_000_000, 100, -30_000_000);
        var origin = new Vec3(30_000_000.25, 99.5, -30_000_000.125);
        mesh.transform = p -> p.add(plot).subtract(origin);
        for (int i = 0; i < 4; i++) mesh.addVertex(.125f, .25f, .5f);
        var bytes = mesh.bytes();
        assertEquals(-.125f, bytes.getFloat(0));
        assertEquals(.75f, bytes.getFloat(4));
        assertEquals(.625f, bytes.getFloat(8));
    }
    @Test void emptyOrIncompleteQuadDoesNotPublishInvalidTriangles() {
        var mesh = new CapturedMesh();
        assertEquals(0, mesh.count());
        mesh.addVertex(0, 0, 0);
        assertEquals(0, mesh.count());
        assertEquals(0, mesh.bytes().remaining());
    }
}
