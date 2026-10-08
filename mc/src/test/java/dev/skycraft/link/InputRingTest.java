package dev.skycraft.link;

import static dev.skycraft.link.Proto.*;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.foreign.Arena;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class InputRingTest {
    @Test void saveQuitCallbackCanWaitForServerUsingSkyLink() throws Exception {
        var field = SkyLink.class.getDeclaredField("shm");field.setAccessible(true);
        Object previous = field.get(null);
        try (var arena = Arena.ofShared()) {
            var memory = arena.allocate(OFF_INPUT_RING + IR_DATA + 16, 8);
            memory.set(JAVA_LONG, OFF_INPUT_RING + IR_HEAD, 1);
            memory.set(JAVA_SHORT, OFF_INPUT_RING + IR_DATA, (short) IN_KEY);
            memory.set(JAVA_SHORT, OFF_INPUT_RING + IR_DATA + 2, (short) 41);
            memory.set(JAVA_INT, OFF_INPUT_RING + IR_DATA + 4, 1);
            field.set(null, memory);
            int[] calls = {0};
            SkyLink.drainInput((type, code, a, b, c) -> {
                assertFalse(Thread.holdsLock(SkyLink.class));
                assertEquals(1, memory.get(JAVA_LONG, OFF_INPUT_RING + IR_TAIL));
                try {CompletableFuture.supplyAsync(SkyLink::generation).get(2, TimeUnit.SECONDS);}
                catch (Exception e) {throw new AssertionError("server access deadlocked during input callback", e);}
                assertEquals(IN_KEY, type);assertEquals(41, code);assertEquals(1, a);calls[0]++;
            });
            assertEquals(1, calls[0]);
            SkyLink.drainInput((type, code, a, b, c) -> fail("input replayed after callback"));
        } finally {field.set(null, previous);}
    }
}
