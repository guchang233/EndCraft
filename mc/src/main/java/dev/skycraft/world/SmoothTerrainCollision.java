package dev.skycraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One terrain solver for prediction and the integrated server's collision checks. */
public final class SmoothTerrainCollision {
    private SmoothTerrainCollision() {}

    public static Vec3 collide(Entity entity, Vec3 move) {
        AABB box = entity.getBoundingBox();
        double step = entity.maxUpStep();
        List<SkyTri> tris = new ArrayList<>();
        SkyCollision.trianglesNear(box.expandTowards(move).inflate(1.0, 1.0 + step, 1.0), tris);
        if (tris.isEmpty()) return move;
        double[] r = TriCollider.resolve(tris, (box.minX + box.maxX) * .5, box.minY,
            (box.minZ + box.maxZ) * .5, box.getXsize() * .5, box.getYsize(), step,
            entity.onGround(), move.x, move.y, move.z);
        if (r[0] == move.x && r[1] == move.y && r[2] == move.z) return move;
        // Terrain adjustment still respects placed Minecraft blocks.
        return Entity.collideBoundingBox(entity, new Vec3(r[0], r[1], r[2]), box, entity.level(), List.of());
    }
}
