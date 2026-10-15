package dev.skycraft.client.render;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class SectionReplayCacheTest {
    private static void put(SectionReplayCache cache, long key, ByteBuffer data) {
        cache.put(key, ByteBuffer.allocate(0), data, ByteBuffer.allocate(0), ByteBuffer.allocate(0));
    }
    @Test void ownsSnapshotWithoutChangingReusableSource() {
        var cache = new SectionReplayCache(16);
        var source = ByteBuffer.allocate(8).putInt(123).putInt(456).flip(); source.position(4);
        put(cache, 1, source); source.putInt(4, 789);
        assertEquals(4, source.position());
        assertEquals(456, cache.get(1).vertices().getInt());
        assertEquals(456, cache.get(1).vertices().duplicate().rewind().getInt());
    }
    @Test void evictsLeastRecentlyUsedAndRejectsOversizedEntries() {
        var cache = new SectionReplayCache(8);
        put(cache, 1, ByteBuffer.allocate(4)); put(cache, 2, ByteBuffer.allocate(4)); cache.get(1);
        put(cache, 3, ByteBuffer.allocate(4));
        assertNull(cache.get(2)); assertNotNull(cache.get(1)); assertEquals(8, cache.bytes());
        put(cache, 1, ByteBuffer.allocate(9)); assertNull(cache.get(1)); assertEquals(4, cache.bytes());
    }
    @Test void invalidationAndWorldResetReleaseSnapshots() {
        var cache = new SectionReplayCache(8); put(cache, 1, ByteBuffer.allocate(4));
        cache.remove(1); assertNull(cache.get(1)); assertEquals(0, cache.bytes());
        put(cache, 2, ByteBuffer.allocate(4)); cache.clear(); assertNull(cache.get(2)); assertEquals(0, cache.bytes());
    }
}
