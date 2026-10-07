#include "mapping.h"
#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include "camera_observer.h"
#include "runtime_scope.h"
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using nlohmann::json;
namespace {
constexpr char kId[] = "endcraft.probe";
endcraft::Mapping mapping;
BE_ThirdPartyHostV1 host{};
std::jthread heartbeat;
std::mutex lifecycle;
bool initialized = false;
bool standin = false;
std::uint32_t resolvedCount = 0;
json lastProbe = json::array();

struct Probe { const char* id; BE_MethodDescriptorV1 descriptor; };
// These are discovery contracts, never a claim that a function is safe to invoke.
const Probe probes[] = {
    {"camera.main", {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_main",nullptr,"UnityEngine.Camera",0}},
    {"camera.view", {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_worldToCameraMatrix",nullptr,"UnityEngine.Matrix4x4",0}},
    {"camera.projection", {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_projectionMatrix",nullptr,"UnityEngine.Matrix4x4",0}},
    {"camera.fov", {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_fieldOfView",nullptr,"System.Single",0}},
    {"player.main", {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","PlayerController","GetMainCharacter",nullptr,nullptr,0}},
    {"player.movement", {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_movementComponent",nullptr,"Beyond.Gameplay.Core.MovementComponent",0}},
    {"player.ability", {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_abilityCom",nullptr,"Beyond.Gameplay.Core.AbilitySystem",0}},
    {"camera.late_tick", {"Gameplay.Beyond.dll","Beyond.Gameplay.View","CameraManager","TailLateTick",nullptr,"System.Void",1}},
    {"physics.raycast", {"UnityEngine.PhysicsModule.dll","UnityEngine","Physics","Raycast","UnityEngine.Vector3|UnityEngine.Vector3|UnityEngine.RaycastHit&|System.Single|System.Int32|UnityEngine.QueryTriggerInteraction","System.Boolean",6}},
    {"physics.collider_size", {"UnityEngine.PhysicsModule.dll","UnityEngine","BoxCollider","set_size","UnityEngine.Vector3","System.Void",1}},
    {"physics.controller_move", {"UnityEngine.PhysicsModule.dll","UnityEngine","CharacterController","Move","UnityEngine.Vector3","UnityEngine.CollisionFlags",1}},
};

const BE_HostApiV1* runtime() {
    return host.get_runtime ? host.get_runtime(host.context) : host.runtime;
}
std::uint32_t flags() {
    return (standin ? endcraft::StandIn : 0u) |
        (runtime() ? endcraft::RuntimeReady : 0u) |
        (GetModuleHandleW(L"GameAssembly.dll") ? endcraft::GameAssemblyLoaded : 0u);
}
void log(const char* message) { if (host.log) host.log(host.context, message); }
json status() {
    const auto now = GetTickCount64();
    auto* h = mapping.header();
    const auto pid = DWORD(InterlockedCompareExchange(reinterpret_cast<volatile LONG*>(&h->mcPid), 0, 0));
    const auto beat = std::uint64_t(InterlockedCompareExchange64(reinterpret_cast<volatile LONG64*>(&h->mcHeartbeatMs), 0, 0));
    bool pidAlive = false;
    if (pid) {
        if (HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid)) {
            pidAlive = WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
            CloseHandle(process);
        }
    }
    json loaded = json::array();
    for (const auto* dll : {"d3d11.dll","d3d12.dll","vulkan-1.dll","dxgi.dll"}) {
        if (GetModuleHandleA(dll)) loaded.push_back(dll);
    }
    const auto camera=camera_probe::snapshot();
    json verified=json::array();
    if(camera["valid_camera"].get<bool>()&&camera["sample_count"].get<std::uint64_t>()>1)
        verified.push_back("main_thread_camera_observation");
    return {
        {"module",kId}, {"mode","probe_only"}, {"environment",standin?"stand_in":"host_process"},
        {"game_assembly_loaded",bool(GetModuleHandleW(L"GameAssembly.dll"))},
        {"host_pid",GetCurrentProcessId()}, {"mapping",mapping.name()}, {"protocol",skycraft::proto::kVersion},
        {"mc_pid",pid}, {"mc_process_alive",pidAlive}, {"mc_heartbeat_age_ms",beat&&now>=beat?json(now-beat):json(nullptr)},
        {"mc_connected",pidAlive&&beat&&now>=beat&&now-beat<8000},
        {"loaded_graphics_libraries",loaded}, {"active_graphics_api","unverified"},
        {"method_probes",lastProbe}, {"camera_observation",camera}, {"verified_capabilities",verified},
        {"gameplay_enabled",false},
        {"blockers",{"Unity main-thread dispatch unverified","host movement authority unverified",
            "host depth capture unverified","NPC physics/navigation unverified","authoritative damage pipeline unverified"}}
    };
}
BE_Result BE_CALL Initialize(const BE_ThirdPartyHostV1* input, const char* config) {
    std::lock_guard lock(lifecycle);
    const auto required = offsetof(BE_ThirdPartyHostV1, emit) + sizeof(input->emit);
    if (!input || input->version!=1 || input->struct_size<required || !input->platform ||
        std::string(input->platform)!="windows-x64" || !input->module_id || std::string(input->module_id)!=kId ||
        !input->reply || !input->emit) return BE_Result_ContractMismatch;
    if (initialized) return BE_Result_Conflict;
    try {
        // Configuration has no switch that can turn gameplay on.
        const auto configuration = json::parse(config ? config : "{}");
        if (!configuration.is_object()) return BE_Result_InvalidArgument;
        host = {};
        std::memcpy(&host, input, (std::min)(std::size_t(input->struct_size), sizeof(host)));
        standin = !GetModuleHandleW(L"GameAssembly.dll");
        wchar_t fixtureName[256]{};
        const auto length=standin?GetEnvironmentVariableW(L"ENDCRAFT_STANDIN_MAPPING",fixtureName,256):0;
        const bool fixture=length>0&&length<256&&std::wstring(fixtureName).starts_with(L"Local\\EndCraft_test_");
        if (!mapping.open(fixture?fixtureName:skycraft::proto::kMappingName)) { log("Cannot own shared memory (another probe may already be running)"); host={}; return BE_Result_Conflict; }
        resolvedCount=0; lastProbe=json::array();
        mapping.publish(flags(),0);
        heartbeat = std::jthread([](std::stop_token stop) {
            while (!stop.stop_requested()) {
                mapping.tick();
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        });
        initialized=true;
        log("EndCraft probe ready. No game methods invoked, no hooks installed, gameplay disabled.");
        host.emit(host.context,"{\"type\":\"ready\",\"mode\":\"probe_only\"}");
        return BE_Result_Ok;
    } catch (...) {
        if(heartbeat.joinable()){heartbeat.request_stop();heartbeat.join();}
        mapping.close(); host={}; initialized=false;
        return BE_Result_InvalidArgument;
    }
}
BE_Result BE_CALL Configure(const char* config) {
    std::lock_guard lock(lifecycle);
    if(!initialized) return BE_Result_NotReady;
    try { return json::parse(config?config:"{}").is_object()?BE_Result_Ok:BE_Result_InvalidArgument; }
    catch (...) { return BE_Result_InvalidArgument; }
}
BE_Result BE_CALL Message(const char* request, const char* body) {
    std::lock_guard lock(lifecycle);
    if(!initialized) return BE_Result_NotReady;
    if(!request||!body) return BE_Result_InvalidArgument;
    try {
        const auto message=json::parse(body);
        const auto action=message.at("action").get<std::string>();
        RuntimeThreadScope scope((action=="probe"||action=="observe")&&runtime()!=nullptr);
        if(!scope.ready()) return host.reply(host.context,request,BE_Result_NotReady,"{\"error\":\"IL2CPP worker attachment unavailable\"}");
        if(action=="probe") {
            lastProbe=json::array(); resolvedCount=0;
            const auto* api=runtime();
            for(const auto& p:probes) {
                BE_ResolvedMethodV1 found{};
                BE_Result result=BE_Result_NotReady;
                if(api&&api->abi_version==1&&api->resolve_method)
                    result=api->resolve_method(api->context,&p.descriptor,&found);
                const bool resolved=result==BE_Result_Ok&&found.method_info&&found.method_pointer;
                if(resolved) ++resolvedCount;
                lastProbe.push_back({{"id",p.id},{"result",int(result)},
                    {"state",resolved?"resolved_not_verified":result==BE_Result_NotReady?"runtime_not_ready":"unresolved"},
                    {"verified",false},{"method",p.descriptor.method_name}});
            }
            mapping.publish(flags(),resolvedCount);
        } else if(action=="observe") {
            const auto result=camera_probe::start(runtime(),host.hooks);
            if(result!=BE_Result_Ok) {
                const auto error=json{{"error","read-only camera observation could not start"},{"result",int(result)}}.dump();
                return host.reply(host.context,request,result,error.c_str());
            }
        } else if(action!="status") {
            return host.reply(host.context,request,BE_Result_InvalidArgument,"{\"error\":\"supported actions: status, probe, observe\"}");
        }
        const auto response=status().dump();
        return host.reply(host.context,request,BE_Result_Ok,response.c_str());
    } catch (...) { return host.reply(host.context,request,BE_Result_InvalidArgument,"{\"error\":\"invalid request\"}"); }
}
void BE_CALL Shutdown() {
    std::lock_guard lock(lifecycle);
    if(!initialized) return;
    heartbeat.request_stop(); heartbeat.join();
    camera_probe::stop(host.hooks);
    mapping.close(); log("EndCraft probe stopped; observer retired, gameplay unchanged.");
    host={}; initialized=false;
}
const BE_ThirdPartyModuleV1 module{sizeof(BE_ThirdPartyModuleV1),1,kId,Initialize,Configure,Message,Shutdown};
}
BE_EXPORT const BE_ThirdPartyModuleV1* BE_CALL BetterEndfield_GetThirdPartyModuleV1() { return &module; }
