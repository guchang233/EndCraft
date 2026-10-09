package dev.skycraft.link;

import static org.junit.jupiter.api.Assertions.*;
import static java.lang.foreign.ValueLayout.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Real Windows mapping + JNI-free Java FFM across two test processes. No game process. */
@EnabledOnOs(OS.WINDOWS)
class NativeLinkTest {
    private static void await(BooleanSupplier condition, java.util.function.Supplier<String> state) throws Exception {
        long deadline=System.nanoTime()+20_000_000_000L;
        while(!condition.getAsBoolean()) {
            if(System.nanoTime()>deadline) fail("Native mapping condition timed out; "+state.get());
            Thread.sleep(25);
        }
    }
    @Test void nativeHeartbeatDisconnectAndReconnect() throws Exception {
        Path directory=Path.of("..").toAbsolutePath().normalize().resolve("build/native");
        assertTrue(Files.isRegularFile(directory.resolve("endcraft-standin.exe")),"Build native first");
        String name="Local\\EndCraft_test_"+UUID.randomUUID().toString().replace("-","");
        System.setProperty("skycraft.link",name);
        int previousGeneration=SkyLink.generation();
        for(int round=0;round<2;round++) {
            ProcessBuilder builder=new ProcessBuilder(directory.resolve("endcraft-standin.exe").toString(),
                    directory.resolve("endcraft.probe.dll").toString(),"--seconds","15");
            builder.environment().put("ENDCRAFT_STANDIN_MAPPING",name);
            builder.redirectErrorStream(true);
            // A file, not a pipe: the host prints status JSON while running and would block on a
            // full pipe that is only read after it exits.
            Path output=Files.createTempFile("endcraft-standin",".log");
            builder.redirectOutput(output.toFile());
            Process nativeHost=builder.start();
            java.util.function.Supplier<String> state=()->{
                String text;
                try { text=Files.readString(output); } catch(java.io.IOException e) { text=e.toString(); }
                return SkyLink.openDiagnostics()+" host alive="+nativeHost.isAlive()+(nativeHost.isAlive()?"":" exit="+nativeHost.exitValue())
                    +" output="+text.substring(0,Math.min(text.length(),1500));
            };
            try {
                await(() -> { SkyLink.poll(); return SkyLink.withSegment(s->s!=null); }, state);
                assertFalse(SkyLink.active(),"A test host must never take over Minecraft");
                assertEquals((int)nativeHost.pid(),SkyLink.skyrimPid());
                assertTrue(SkyLink.generation()>previousGeneration);
                previousGeneration=SkyLink.generation();
                long first=SkyLink.withSegment(s->s.get(JAVA_LONG,Proto.H_SKYRIM_HEARTBEAT));
                await(() -> SkyLink.withSegment(s->s.get(JAVA_LONG,Proto.H_SKYRIM_HEARTBEAT)>first), state);
                SkyLink.poll();
                SkyLink.withSegment(s->{
                    assertEquals(Proto.MAGIC,s.get(JAVA_INT,Proto.H_MAGIC));
                    assertEquals(Proto.VERSION,s.get(JAVA_INT,Proto.H_VERSION));
                    assertEquals((int)ProcessHandle.current().pid(),s.get(JAVA_INT,Proto.H_MC_PID));
                    assertTrue(s.get(JAVA_LONG,Proto.H_MC_HEARTBEAT)>0);
                    return null;
                });
                assertEquals(0,nativeHost.waitFor(),Files.readString(output));
                await(() -> { SkyLink.poll(); return SkyLink.withSegment(s->s==null); }, state);
                assertEquals(0,SkyLink.skyrimPid());
            } finally {
                if(nativeHost.isAlive()) nativeHost.destroyForcibly();
                Files.deleteIfExists(output);
            }
        }
    }
}
