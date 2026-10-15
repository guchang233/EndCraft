package dev.skycraft.client.render;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PlotTransformTest {
    private static void same(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-7);
        assertEquals(expected.y, actual.y, 1e-7);
        assertEquals(expected.z, actual.z, 1e-7);
    }
    @Test void rotationTranslationAndScaleMatchDirectPoseAtRemotePlot() {
        Vec3 centre = new Vec3(30_000_000.5, 100.5, -30_000_000.5), origin = new Vec3(9.25, 20.125, -4.5);
        UnaryOperator<Vec3> pose = p -> {
            Vec3 local = p.subtract(centre);
            return new Vec3(10 - local.z * 2, 21 + local.y * 3, -3 + local.x * .5);
        };
        var mapping = new PlotTransform(centre, origin, pose);
        for (int i = -8; i <= 8; i++) {
            int x = 30_000_000 + i, y = 99 + i, z = -30_000_001 - i;
            Vec3 local = new Vec3(.125, .375, .625);
            same(pose.apply(local.add(x, y, z)).subtract(origin), mapping.forBlock(x, y, z).apply(local));
        }
    }
    @Test void thousandsOfVerticesUseFourPoseCallsAndBlockOffsetsAreImmutable() {
        var calls = new AtomicInteger();
        var mapping = new PlotTransform(Vec3.ZERO, Vec3.ZERO, p -> { calls.incrementAndGet(); return p; });
        var first = mapping.forBlock(1, 2, 3); var second = mapping.forBlock(4, 5, 6);
        for (int i = 0; i < 10000; i++) same(new Vec3(1.25, 2.5, 3.75), first.apply(new Vec3(.25, .5, .75)));
        same(new Vec3(4.25, 5.5, 6.75), second.apply(new Vec3(.25, .5, .75)));
        assertEquals(4, calls.get());
    }
    @Test void nextFrameUsesFreshMovingPose() {
        var first = new PlotTransform(Vec3.ZERO, Vec3.ZERO, p -> p.add(1, 0, 0));
        var next = new PlotTransform(Vec3.ZERO, Vec3.ZERO, p -> p.add(2, 0, 0));
        same(new Vec3(1, 0, 0), first.forBlock(0, 0, 0).apply(Vec3.ZERO));
        same(new Vec3(2, 0, 0), next.forBlock(0, 0, 0).apply(Vec3.ZERO));
    }
}
