package dev.skycraft.client;

import java.nio.ByteBuffer;

/** GL blends onto transparent black; Unity UI expects straight-alpha texture pixels. */
public final class OverlayPixels {
    private OverlayPixels() { }
    public static void opaque(ByteBuffer source, ByteBuffer destination) {
        if (source.remaining() % 4 != 0 || destination.remaining() < source.remaining())
            throw new IllegalArgumentException("RGBA buffer size");
        for (int i = source.position(); i < source.limit(); i += 4)
            destination.put(source.get(i)).put(source.get(i + 1)).put(source.get(i + 2)).put((byte)255);
    }
    public static void straighten(ByteBuffer source, ByteBuffer destination) {
        if (source.remaining() % 4 != 0 || destination.remaining() < source.remaining())
            throw new IllegalArgumentException("RGBA buffer size");
        for (int i = source.position(); i < source.limit(); i += 4) {
            int alpha = source.get(i + 3) & 255;
            if (alpha == 0) { destination.putInt(0); continue; }
            for (int c = 0; c < 3; c++) {
                int value = source.get(i + c) & 255;
                destination.put((byte) (alpha == 255 ? value : Math.min(255, (value * 255 + alpha / 2) / alpha)));
            }
            destination.put((byte) alpha);
        }
    }
}
