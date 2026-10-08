package dev.skycraft.link;

import static org.junit.jupiter.api.Assertions.*;
import static java.lang.foreign.ValueLayout.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import org.junit.jupiter.api.Test;

class HostActorProbeTest {
    private MemorySegment fixture(Arena arena) {
        var memory=arena.allocate(0x1000,8);
        memory.set(JAVA_INT,Proto.H_SKYRIM_PID,123);
        memory.set(JAVA_INT,0x900,2);memory.set(JAVA_INT,0x904,1);
        memory.set(JAVA_INT,0x908,123);memory.set(JAVA_INT,0x90c,3);
        memory.set(JAVA_LONG,0x910,10000);memory.set(JAVA_LONG,0x918,25);
        memory.set(JAVA_FLOAT,0x920,-334.5f);memory.set(JAVA_FLOAT,0x924,222.2f);
        memory.set(JAVA_FLOAT,0x928,1305.3f);memory.set(JAVA_FLOAT,0x938,1.8f);
        memory.set(JAVA_FLOAT,0x93c,.35f);
        return memory;
    }
    @Test void readsRawHostUnitsAndRejectsStaleOrFutureSample() {
        try(var arena=Arena.ofConfined()) {
            var memory=fixture(arena);var actor=HostActorProbe.read(memory,10062);
            assertNotNull(actor);assertEquals(-334.5f,actor.x());assertEquals(1.8f,actor.capsuleHeight());
            assertNull(HostActorProbe.read(memory,12001));assertNull(HostActorProbe.read(memory,9999));
        }
    }
    @Test void rejectsUncommittedWrongOwnerAndInvalidGeometry() {
        try(var arena=Arena.ofConfined()) {
            var memory=fixture(arena);memory.set(JAVA_INT,0x900,3);assertNull(HostActorProbe.read(memory,10001));
            memory.set(JAVA_INT,0x900,4);memory.set(JAVA_INT,0x908,124);assertNull(HostActorProbe.read(memory,10001));
            memory.set(JAVA_INT,0x908,123);memory.set(JAVA_FLOAT,0x920,Float.NaN);assertNull(HostActorProbe.read(memory,10001));
            memory.set(JAVA_FLOAT,0x920,1);memory.set(JAVA_FLOAT,0x93c,-1);assertNull(HostActorProbe.read(memory,10001));
            memory.set(JAVA_FLOAT,0x93c,.35f);memory.set(JAVA_INT,0x90c,0);assertNull(HostActorProbe.read(memory,10001));
        }
    }
}
