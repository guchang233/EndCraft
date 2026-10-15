package dev.skycraft.client;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlayPixelsTest {
    @Test void rasterPreviewPreservesShaderRgbAndMakesBackgroundOpaque() {
        var output = ByteBuffer.allocate(4);
        OverlayPixels.opaque(ByteBuffer.wrap(new byte[]{12, 34, 56, 0}), output);
        assertArrayEquals(new byte[]{12, 34, 56, (byte)255}, output.array());
    }
    @Test void transparentEdgesAreNotMultipliedTwice() {
        var source = ByteBuffer.wrap(new byte[]{64, 32, 16, (byte)128, 12, 34, 56, 0,
            (byte)200, 100, 50, (byte)255});
        var destination = ByteBuffer.allocate(12);
        OverlayPixels.straighten(source, destination);
        assertArrayEquals(new byte[]{(byte)128, 64, 32, (byte)128, 0, 0, 0, 0,
            (byte)200, 100, 50, (byte)255}, destination.array());
        assertEquals(0, source.position());
    }
    @Test void invalidPixelsAreRejectedAndRgbIsClamped() {
        assertThrows(IllegalArgumentException.class, () -> OverlayPixels.straighten(ByteBuffer.allocate(3), ByteBuffer.allocate(4)));
        var result = ByteBuffer.allocate(4);
        OverlayPixels.straighten(ByteBuffer.wrap(new byte[]{100, 0, 0, 1}), result);
        assertEquals(255, result.get(0) & 255);
    }
}
