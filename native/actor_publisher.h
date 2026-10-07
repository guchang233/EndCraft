#pragma once
#include "../protocol/actor_state.h"
#include <windows.h>
#include <cstring>

namespace endcraft {
// A secondary writer opens the probe-owned mapping and writes only its diagnostic slot.
// The actor callback's state mutex serializes publish/close against shutdown.
class ActorPublisher {
    HANDLE handle=nullptr;
    unsigned char* view=nullptr;
public:
    ~ActorPublisher() {close();}
    bool publish(ActorProbeState state) {
        if(!view) {
            handle=OpenFileMappingW(FILE_MAP_ALL_ACCESS,FALSE,skycraft::proto::kMappingName);
            if(!handle) return false;
            view=static_cast<unsigned char*>(MapViewOfFile(handle,FILE_MAP_ALL_ACCESS,0,0,0x1000));
            if(!view) {close();return false;}
        }
        auto* header=reinterpret_cast<skycraft::proto::Header*>(view);
        const auto now=GetTickCount64();
        const auto heartbeat=static_cast<std::uint64_t>(InterlockedCompareExchange64(
            reinterpret_cast<volatile LONG64*>(&header->skyrimHeartbeatMs),0,0));
        if(header->magic!=skycraft::proto::kMagic||header->version!=skycraft::proto::kVersion||
           header->skyrimPid!=GetCurrentProcessId()||!heartbeat||now<heartbeat||now-heartbeat>=8000) {
            close();return false;
        }
        auto* target=reinterpret_cast<ActorProbeState*>(view+kActorProbeOffset);
        auto* seq=reinterpret_cast<volatile LONG*>(&target->seq);
        InterlockedIncrement(seq);MemoryBarrier();
        state.version=kActorProbeVersion;state.hostPid=GetCurrentProcessId();
        std::memcpy(reinterpret_cast<unsigned char*>(target)+4,reinterpret_cast<unsigned char*>(&state)+4,sizeof(state)-4);
        MemoryBarrier();InterlockedIncrement(seq);return true;
    }
    void close() {
        if(view) {UnmapViewOfFile(view);view=nullptr;}
        if(handle) {CloseHandle(handle);handle=nullptr;}
    }
};
}
