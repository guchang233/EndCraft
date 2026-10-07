#pragma once
#include "endcraft_protocol.h"
#include <cstddef>

namespace endcraft {
// Diagnostic extension in the upstream header's unused region; never enables gameplay.
constexpr std::size_t kProbeOffset = 0x40;
constexpr std::uint32_t kProbeVersion = 1;
enum ProbeFlags : std::uint32_t { StandIn = 1, RuntimeReady = 2, GameAssemblyLoaded = 4 };
struct ProbeState {
    std::uint32_t seq, version, flags, resolvedMethods;
    std::uint64_t sampleMs;
    std::uint32_t verifiedCapabilities, reserved;
};
static_assert(sizeof(ProbeState) == 32);
static_assert(kProbeOffset + sizeof(ProbeState) < skycraft::proto::kOffSkyState);
}
