#pragma once
#include "../protocol/endcraft_protocol.h"
#include <windows.h>
#include <vector>
#include <cstring>
#include <stdexcept>

namespace endcraft {
namespace proto=skycraft::proto;
inline std::uint64_t acquire64(unsigned char* at) {return static_cast<std::uint64_t>(InterlockedCompareExchange64(reinterpret_cast<volatile LONG64*>(at),0,0));}
inline void release64(unsigned char* at,std::uint64_t value) {InterlockedExchange64(reinterpret_cast<volatile LONG64*>(at),static_cast<LONG64>(value));}
inline bool readRenderMessage(unsigned char* base,std::uint64_t capacity,std::uint32_t& type,std::vector<unsigned char>& payload) {
    const auto head=acquire64(base),tail=acquire64(base+0x40);
    if(head==tail) return false;
    if(head<tail||head-tail>capacity) throw std::runtime_error("render ring invalid counters");
    const auto position=tail%capacity;
    if(capacity-position<8) {release64(base+0x40,tail+capacity-position);return false;}
    std::uint32_t header[2];std::memcpy(header,base+0x80+position,8);
    if(!header[0]) {release64(base+0x40,tail+capacity-position);return false;}
    const auto bytes=(8ull+header[1]+7)&~7ull;
    if(bytes>capacity-position||bytes>head-tail) throw std::runtime_error("render ring invalid payload");
    type=header[0];payload.resize(header[1]);
    if(!payload.empty()) std::memcpy(payload.data(),base+0x88+position,payload.size());
    release64(base+0x40,tail+bytes);return true;
}
class BridgeMemory {
    HANDLE handle=nullptr;
public:
    unsigned char* data=nullptr;
    ~BridgeMemory() {close();}
    bool open() {
        if(data) return true;
        handle=OpenFileMappingW(FILE_MAP_ALL_ACCESS,FALSE,proto::kMappingName);if(!handle) return false;
        data=static_cast<unsigned char*>(MapViewOfFile(handle,FILE_MAP_ALL_ACCESS,0,0,proto::kMappingBytes));
        if(!data) {close();return false;}
        auto* header=reinterpret_cast<proto::Header*>(data);
        if(header->magic!=proto::kMagic||header->version!=proto::kVersion||header->skyrimPid!=GetCurrentProcessId()) {close();return false;}
        return true;
    }
    void close() {if(data) {UnmapViewOfFile(data);data=nullptr;}if(handle) {CloseHandle(handle);handle=nullptr;}}
    bool mc(proto::McState& state) {
        if(!data) return false;
        const auto beat=acquire64(data+24),now=GetTickCount64();
        if(!beat||now<beat||now-beat>2000) return false;
        auto* source=data+proto::kOffMcState;
        for(unsigned tries=0;tries<3;++tries) {
            auto before=InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(source),0,0);
            if(before&1) continue;std::memcpy(&state,source,sizeof(state));MemoryBarrier();
            if(before==InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(source),0,0)) return true;
        }
        return false;
    }
    void sky(proto::SkyState state) {
        if(!data) return;auto* out=data+proto::kOffSkyState;
        InterlockedIncrement(reinterpret_cast<volatile LONG*>(out));MemoryBarrier();
        std::memcpy(out+4,reinterpret_cast<unsigned char*>(&state)+4,sizeof(state)-4);
        MemoryBarrier();InterlockedIncrement(reinterpret_cast<volatile LONG*>(out));
    }
    bool input(proto::InputEvent event) {
        auto* base=data+proto::kOffInputRing;auto head=acquire64(base),tail=acquire64(base+0x40);
        if(head<tail||head-tail>=proto::kInputRingEntries) return false;
        std::memcpy(base+0x80+(head%proto::kInputRingEntries)*sizeof(event),&event,sizeof(event));MemoryBarrier();release64(base,head+1);return true;
    }
    bool collision(std::uint32_t type,const void* payload,std::size_t size) {
        auto* base=data+proto::kOffCollisionRing;auto head=acquire64(base),tail=acquire64(base+0x40);
        auto capacity=proto::kColRingDataBytes;auto bytes=(8+size+7)&~std::uint64_t(7);auto position=head%capacity;
        auto padding=position+bytes>capacity?capacity-position:0;
        if(head<tail||head-tail+bytes+padding>capacity) return false;
        if(padding) {proto::ColMsgHeader pad{0,0};std::memcpy(base+0x80+position,&pad,8);head+=padding;position=0;}
        proto::ColMsgHeader message{type,std::uint32_t(size)};
        std::memcpy(base+0x80+position,&message,8);std::memcpy(base+0x88+position,payload,size);
        MemoryBarrier();release64(base,head+bytes);return true;
    }
    bool render(std::uint32_t& type,std::vector<unsigned char>& payload) {
        return readRenderMessage(data+proto::kOffRenderRing,proto::kRenRingDataBytes,type,payload);
    }
};
}
