package dev.skycraft.link;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/** Host diagnostic slot, deliberately independent of gameplay authority and MC coordinates. */
public record HostActorProbe(int hostPid, int flags, long sampleMs, long samples,
        float x, float y, float z, float vx, float vy, float vz,
        float capsuleHeight, float capsuleRadius, int threadId) {
    public static final long OFFSET=0x900;
    private static final VarHandle INT=ValueLayout.JAVA_INT.varHandle();
    public static HostActorProbe read(MemorySegment memory,long now) {
        if(memory==null) return null;
        for(int attempt=0;attempt<3;attempt++) {
            int before=(int)INT.getAcquire(memory,OFFSET);
            if((before&1)!=0) continue;
            int version=memory.get(ValueLayout.JAVA_INT,OFFSET+4);
            var state=new HostActorProbe(memory.get(ValueLayout.JAVA_INT,OFFSET+8),
                memory.get(ValueLayout.JAVA_INT,OFFSET+12),memory.get(ValueLayout.JAVA_LONG,OFFSET+16),
                memory.get(ValueLayout.JAVA_LONG,OFFSET+24),
                memory.get(ValueLayout.JAVA_FLOAT,OFFSET+32),memory.get(ValueLayout.JAVA_FLOAT,OFFSET+36),
                memory.get(ValueLayout.JAVA_FLOAT,OFFSET+40),memory.get(ValueLayout.JAVA_FLOAT,OFFSET+44),
                memory.get(ValueLayout.JAVA_FLOAT,OFFSET+48),memory.get(ValueLayout.JAVA_FLOAT,OFFSET+52),
                memory.get(ValueLayout.JAVA_FLOAT,OFFSET+56),memory.get(ValueLayout.JAVA_FLOAT,OFFSET+60),
                memory.get(ValueLayout.JAVA_INT,OFFSET+64));
            VarHandle.acquireFence();
            if(before!=(int)INT.getAcquire(memory,OFFSET)) continue;
            if(version!=1||state.hostPid!=memory.get(ValueLayout.JAVA_INT,Proto.H_SKYRIM_PID)||
                (state.flags&1)==0||state.sampleMs<=0||now<state.sampleMs||now-state.sampleMs>2000||
                !Float.isFinite(state.x)||!Float.isFinite(state.y)||!Float.isFinite(state.z)||
                !Float.isFinite(state.vx)||!Float.isFinite(state.vy)||!Float.isFinite(state.vz)||
                !Float.isFinite(state.capsuleHeight)||!Float.isFinite(state.capsuleRadius)||
                state.capsuleHeight<=0||state.capsuleRadius<=0) return null;
            return state;
        }
        return null;
    }
}
