#pragma once
#include "endcraft_protocol.h"
#include <cstddef>

namespace endcraft {
// Diagnostic coordinates are raw host units. They are never fed into SkyState.
constexpr std::size_t kActorProbeOffset=0x900;
constexpr std::uint32_t kActorProbeVersion=1;
enum ActorProbeFlags : std::uint32_t { ActorValid=1, ActorAlive=2, ActorCinematic=4 };
struct ActorProbeState {
    std::uint32_t seq,version,hostPid,flags;
    std::uint64_t sampleMs,samples;
    float position[3],velocity[3],capsuleHeight,capsuleRadius;
    std::uint32_t threadId,reserved[3];
};
static_assert(sizeof(ActorProbeState)==80);
static_assert(offsetof(ActorProbeState,position)==32);
static_assert(skycraft::proto::kOffWaterGrid+sizeof(skycraft::proto::WaterGrid)<=kActorProbeOffset);
static_assert(kActorProbeOffset+sizeof(ActorProbeState)<=skycraft::proto::kOffInputRing);
}
