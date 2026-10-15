package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RenderBackpressureTest {
    @Test void fullQueueReturnsImmediatelyAndRecoversAfterConsumerAdvances() throws Exception {
        var field = SkyLink.class.getDeclaredField("shm"); field.setAccessible(true);
        Object previous = field.get(null);
        try (var arena = Arena.ofShared()) {
            var memory = arena.allocate(OFF_RENDER_RING + RR_DATA + 128L, 8);
            field.set(null, memory);
            memory.set(JAVA_LONG, OFF_RENDER_RING + RR_HEAD, RR_DATA_BYTES);
            memory.set(JAVA_LONG, OFF_RENDER_RING + RR_TAIL, 0);
            assertTrue(SkyLink.renderBackedUp());
            assertTimeout(Duration.ofMillis(500), () -> assertFalse(SkyLink.tryWriteRender(REN_CLEAR_ALL, ByteBuffer.allocate(0), null)));
            memory.set(JAVA_LONG, OFF_RENDER_RING + RR_TAIL, RR_DATA_BYTES);
            assertFalse(SkyLink.renderBackedUp());
            assertTrue(SkyLink.tryWriteRender(REN_CLEAR_ALL, ByteBuffer.allocate(0), null));
        } finally { field.set(null, previous); }
    }
}
