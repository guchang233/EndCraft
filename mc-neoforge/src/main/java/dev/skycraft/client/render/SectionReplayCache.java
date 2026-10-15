package dev.skycraft.client.render;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;

/** Bounded copies for recovery replays; normal dirty updates invalidate them before re-meshing. */
final class SectionReplayCache {
    record Entry(ByteBuffer header, ByteBuffer vertices, ByteBuffer solidHeader, ByteBuffer solids) {
        long size() { return (long) header.remaining() + vertices.remaining() + solidHeader.remaining() + solids.remaining(); }
    }
    private final long budget;
    private long bytes;
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    SectionReplayCache(long budget) {
        if (budget < 0) throw new IllegalArgumentException("Negative cache budget");
        this.budget = budget;
    }
    Entry get(long key) { return entries.get(key); }
    long bytes() { return bytes; }
    void clear() { entries.clear(); bytes = 0; }
    void remove(long key) { var old = entries.remove(key); if (old != null) bytes -= old.size(); }
    void put(long key, ByteBuffer header, ByteBuffer vertices, ByteBuffer solidHeader, ByteBuffer solids) {
        remove(key);
        long size = (long) header.remaining() + vertices.remaining() + solidHeader.remaining() + solids.remaining();
        if (size > budget) return;
        while (bytes + size > budget) {
            var iterator = entries.entrySet().iterator();
            var old = iterator.next(); bytes -= old.getValue().size(); iterator.remove();
        }
        entries.put(key, new Entry(copy(header), copy(vertices), copy(solidHeader), copy(solids))); bytes += size;
    }
    private static ByteBuffer copy(ByteBuffer source) {
        return ByteBuffer.allocateDirect(source.remaining()).put(source.duplicate()).flip().asReadOnlyBuffer();
    }
}
