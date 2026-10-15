package dev.skycraft.client.render;

import java.util.function.UnaryOperator;
import net.minecraft.world.phys.Vec3;

/** Sample Sable's affine pose once around the plot centre; keep remote coordinates in doubles. */
final class PlotTransform {
    private final Vec3 centre, translation, xAxis, yAxis, zAxis;
    PlotTransform(Vec3 centre, Vec3 origin, UnaryOperator<Vec3> pose) {
        this.centre = centre;
        Vec3 world = pose.apply(centre);
        translation = world.subtract(origin);
        xAxis = pose.apply(centre.add(1, 0, 0)).subtract(world);
        yAxis = pose.apply(centre.add(0, 1, 0)).subtract(world);
        zAxis = pose.apply(centre.add(0, 0, 1)).subtract(world);
    }
    UnaryOperator<Vec3> forBlock(int x, int y, int z) {
        double dx = x - centre.x, dy = y - centre.y, dz = z - centre.z;
        return local -> {
            double a = dx + local.x, b = dy + local.y, c = dz + local.z;
            return new Vec3(translation.x + xAxis.x * a + yAxis.x * b + zAxis.x * c,
                translation.y + xAxis.y * a + yAxis.y * b + zAxis.y * c,
                translation.z + xAxis.z * a + yAxis.z * b + zAxis.z * c);
        };
    }
    Vec3 centreRelative() { return translation; }
}
