package dev.skycraft.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TerrainDismountTest {
    private static List<SkyTri> floor(float height) {
        return List.of(new SkyTri(new float[]{-5,height,-5, 5,height,-5, 5,height,5}, 0, false),
            new SkyTri(new float[]{-5,height,-5, 5,height,5, -5,height,5}, 0, false));
    }
    @Test void raisesExitAboveFractionalNativeFloor() {
        Vec3 exit = TerrainDismount.choose(floor(54.82093f), new Vec3(1.2,54,0), new Vec3(0,54,0), 1.2, p -> true);
        assertEquals(54.86093, exit.y, .00001);
        assertEquals(1.2, exit.x);
    }
    @Test void choosesAnotherSideWhenPlacedBlocksObstructTheVanillaExit() {
        Vec3 exit = TerrainDismount.choose(floor(2.25f), new Vec3(1.2,2,0), new Vec3(0,2,0), 1.2, p -> p.x < -.5);
        assertTrue(exit.x < -.5);
        assertTrue(exit.y > 2.25);
    }
    @Test void preservesVanillaHeightOnMinecraftPlatformAboveNativeTerrain() {
        Vec3 exit = TerrainDismount.choose(floor(1), new Vec3(1.2,3,0), new Vec3(0,2,0), 1.2, p -> true);
        assertEquals(3, exit.y);
    }
}
