package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import net.minecraft.client.Minecraft;
import dev.skycraft.link.HostActorProbe;
import dev.skycraft.SkyCraft;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Standby telemetry without input/window takeover; only this writer owns MC state. */
public final class ProbeTelemetry {
    private static final VarHandle INT = ValueLayout.JAVA_INT.varHandle();
    private static long frames;
    private static long lastReport;
    private static boolean actorConnected;
    private ProbeTelemetry() {}
    public static void tick(Minecraft client) {
        SkyLink.poll();
        SkyLink.withSegment(memory -> {
            if(memory!=null && !SkyLink.active()) publish(client,memory);
            long now=SkyLink.tickCount();
            if(now-lastReport>=1000) {
                lastReport=now;
                reportActor(HostActorProbe.read(memory,now),now);
            }
            return null;
        });
    }
    private static void reportActor(HostActorProbe actor,long now) {
        boolean connected=actor!=null;
        if(connected!=actorConnected) {
            actorConnected=connected;
            SkyCraft.LOG.info("EndCraft: real host actor telemetry {}",connected ? "received by Minecraft" : "unavailable or stale");
        }
        String status=actor==null ? "{\"valid_host_actor\":false,\"gameplay_enabled\":false}" :
            String.format(Locale.ROOT,
                "{\"valid_host_actor\":true,\"mc_pid\":%d,\"host_pid\":%d,\"samples\":%d,\"sample_age_ms\":%d,\"position_raw_units\":[%s,%s,%s],\"velocity_raw_units_per_second\":[%s,%s,%s],\"capsule_height_raw_units\":%s,\"capsule_radius_raw_units\":%s,\"alive\":%s,\"in_cinematic\":%s,\"gameplay_enabled\":false}",
                ProcessHandle.current().pid(),actor.hostPid(),actor.samples(),now-actor.sampleMs(),
                actor.x(),actor.y(),actor.z(),actor.vx(),actor.vy(),actor.vz(),actor.capsuleHeight(),actor.capsuleRadius(),
                (actor.flags()&2)!=0,(actor.flags()&4)!=0);
        try {
            Path target=Path.of("endcraft-host-actor.json");
            Path temporary=Path.of("endcraft-host-actor.json.tmp");
            Files.writeString(temporary,status);
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
        } catch(java.io.IOException error) {
            SkyCraft.LOG.debug("EndCraft: actor diagnostic file unavailable",error);
        }
    }
    private static void publish(Minecraft client,java.lang.foreign.MemorySegment memory) {
        long b = Proto.OFF_MC_STATE;
        int old = (int) INT.getAcquire(memory,b);
        INT.setRelease(memory,b,old+1);
        VarHandle.storeStoreFence();
        var player = client.player;
        memory.set(ValueLayout.JAVA_INT,b+4,player != null ? 1 : 0);
        memory.set(ValueLayout.JAVA_DOUBLE,b+8,player != null ? player.getX() : 0);
        memory.set(ValueLayout.JAVA_DOUBLE,b+16,player != null ? player.getY() : 0);
        memory.set(ValueLayout.JAVA_DOUBLE,b+24,player != null ? player.getZ() : 0);
        memory.set(ValueLayout.JAVA_FLOAT,b+32,player != null ? player.getYRot() : 0);
        memory.set(ValueLayout.JAVA_FLOAT,b+36,player != null ? player.getXRot() : 0);
        memory.set(ValueLayout.JAVA_LONG,b+56,++frames);
        VarHandle.storeStoreFence();
        INT.setRelease(memory,b,old+2);
    }
}
