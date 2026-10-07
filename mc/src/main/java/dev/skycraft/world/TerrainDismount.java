package dev.skycraft.world;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Vanilla only sees coarse collision voxels when choosing a seat exit. Check the native floor too. */
public final class TerrainDismount {
    private TerrainDismount() {}

    public static Vec3 locate(Entity vehicle, LivingEntity passenger, Vec3 vanilla) {
        if (!SkyCollision.active()) return vanilla;
        List<SkyTri> triangles = new ArrayList<>();
        SkyCollision.trianglesNear(vehicle.getBoundingBox().inflate(4, 5, 4), triangles);
        if (triangles.isEmpty()) return vanilla;
        double radius = (vehicle.getBbWidth() + passenger.getBbWidth()) * .5 + .15;
        return choose(triangles, vanilla, vehicle.position(), radius, at -> {
            AABB box = passenger.getDimensions(Pose.STANDING).makeBoundingBox(at);
            if (!passenger.level().noCollision(passenger, box)) return false;
            double[] move = TriCollider.resolve(triangles, at.x, at.y, at.z,
                box.getXsize() * .5, box.getYsize(), 0, false, 0, -.04, 0);
            return Math.abs(move[0]) < .01 && Math.abs(move[2]) < .01 && move[1] < .08;
        });
    }

    static Vec3 choose(List<SkyTri> triangles, Vec3 vanilla, Vec3 vehicle, double radius, Predicate<Vec3> free) {
        List<Vec3> candidates = new ArrayList<>();
        candidates.add(vanilla);
        for (double r : new double[]{radius, radius + .6, radius + 1.2})
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                candidates.add(new Vec3(vehicle.x + Math.cos(angle) * r, vehicle.y, vehicle.z + Math.sin(angle) * r));
            }
        for (Vec3 candidate : candidates) {
            double floor = TriCollider.groundAt(triangles, candidate.x, candidate.y, candidate.z, 3);
            if (!Double.isFinite(floor) || floor < vehicle.y - 4) continue;
            Vec3 safe = new Vec3(candidate.x, Math.max(candidate.y, floor + .04), candidate.z);
            if (free.test(safe)) return safe;
        }
        // A completely blocked landing must not put the player under the floor.
        // Search above the hull; normal gravity takes over once space is available.
        double floor = TriCollider.groundAt(triangles, vehicle.x, vehicle.y, vehicle.z, 3);
        if (Double.isFinite(floor)) for (double lift = .1; lift <= 3; lift += .5) {
            Vec3 safe = new Vec3(vehicle.x, Math.max(vehicle.y, floor) + lift, vehicle.z);
            if (free.test(safe)) return safe;
        }
        return vanilla;
    }
}
