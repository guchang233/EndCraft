package dev.skycraft.platform;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SdlKeyMapTest {
    @Test void physicalMovementKeysKeepTheirDirections() {
        assertEquals(65, SdlKeyMap.toGlfw(4)); // A
        assertEquals(68, SdlKeyMap.toGlfw(7)); // D
        assertEquals(87, SdlKeyMap.toGlfw(26)); // W
        assertEquals(83, SdlKeyMap.toGlfw(22)); // S
        assertEquals(263, SdlKeyMap.toGlfw(80)); // left
        assertEquals(262, SdlKeyMap.toGlfw(79)); // right
    }
    @Test void inventoryInteractionUsesCorrectMouseButtons() {
        assertEquals(0, SdlKeyMap.mouseButton(1));
        assertEquals(1, SdlKeyMap.mouseButton(3));
        assertEquals(2, SdlKeyMap.mouseButton(2));
        assertEquals(-1, SdlKeyMap.toGlfw(512));
        assertEquals(59, SdlKeyMap.toGlfw(51));
    }
}
