#pragma once
#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>

// Read-only observation on the game's actual camera tick. No transform writes.
namespace camera_probe {
inline std::atomic<bool> enabled{false};
inline std::mutex mutex;
inline const BE_HostApiV1* api=nullptr;
inline BE_ResolvedMethodV1 mainCamera{}, view{}, projection{}, fov{}, tick{};
using TickFn=void(__fastcall*)(void*,float,void*);
inline TickFn nextTick=nullptr;
inline std::uint64_t samples=0;
inline DWORD threadId=0;
inline bool valid=false;
inline float viewMatrix[16]{}, projectionMatrix[16]{}, fieldOfView=0;
inline void* invoke(const BE_ResolvedMethodV1& method,void* target) {
    void* exception=nullptr;
    void* value=api->runtime_invoke(api->context,method.method_info,target,nullptr,&exception);
    return exception?nullptr:value;
}
inline void __fastcall ObserveTick(void* instance,float delta,void* method) {
    if(nextTick) nextTick(instance,delta,method);
    if(!enabled.load(std::memory_order_acquire)) return;
    std::lock_guard lock(mutex);
    if(!enabled.load(std::memory_order_acquire)) return;
    threadId=GetCurrentThreadId(); ++samples; valid=false;
    void* camera=invoke(mainCamera,nullptr);
    if(!camera) return;
    void* boxedView=invoke(view,camera);
    void* rawView=boxedView?api->object_unbox(api->context,boxedView):nullptr;
    if(!rawView) return;
    std::memcpy(viewMatrix,rawView,sizeof(viewMatrix));
    void* boxedProjection=invoke(projection,camera);
    void* rawProjection=boxedProjection?api->object_unbox(api->context,boxedProjection):nullptr;
    if(!rawProjection) return;
    std::memcpy(projectionMatrix,rawProjection,sizeof(projectionMatrix));
    void* boxedFov=invoke(fov,camera);
    void* rawFov=boxedFov?api->object_unbox(api->context,boxedFov):nullptr;
    if(!rawFov) return;
    fieldOfView=*static_cast<float*>(rawFov);
    valid=std::isfinite(fieldOfView)&&fieldOfView>0&&fieldOfView<180;
    for(float element:viewMatrix) valid=valid&&std::isfinite(element);
    for(float element:projectionMatrix) valid=valid&&std::isfinite(element);
}
inline BE_Result start(const BE_HostApiV1* runtime,const BE_HookChainApiV1* hooks) {
    if(enabled.load()) return BE_Result_Ok;
    if(!runtime||runtime->abi_version!=1||!runtime->resolve_method||!runtime->runtime_invoke||!runtime->object_unbox||
        !hooks||hooks->version!=1||hooks->struct_size<sizeof(*hooks)||!hooks->create||!hooks->disable_module) return BE_Result_NotReady;
    const BE_MethodDescriptorV1 descriptors[]={
        {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_main",nullptr,"UnityEngine.Camera",0},
        {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_worldToCameraMatrix",nullptr,"UnityEngine.Matrix4x4",0},
        {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_projectionMatrix",nullptr,"UnityEngine.Matrix4x4",0},
        {"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_fieldOfView",nullptr,"System.Single",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.View","CameraManager","TailLateTick","System.Single","System.Void",1}
    };
    BE_ResolvedMethodV1* results[]={&mainCamera,&view,&projection,&fov,&tick};
    for(int i=0;i<5;++i) {
        auto result=runtime->resolve_method(runtime->context,&descriptors[i],results[i]);
        if(result!=BE_Result_Ok||!results[i]->method_info||!results[i]->method_pointer) return BE_Result_NotFound;
    }
    api=runtime;
    std::uint64_t handle{};
    const auto result=hooks->create(hooks->context,"endcraft.probe",tick.method_pointer,
        reinterpret_cast<void*>(&ObserveTick),reinterpret_cast<void**>(&nextTick),&handle);
    if(result!=BE_Result_Ok) return result;
    { std::lock_guard lock(mutex); samples=0; valid=false; threadId=0; }
    enabled.store(true,std::memory_order_release);
    return BE_Result_Ok;
}
inline void stop(const BE_HookChainApiV1* hooks) {
    enabled.store(false,std::memory_order_release);
    if(hooks&&hooks->disable_module) hooks->disable_module(hooks->context,"endcraft.probe");
    std::lock_guard lock(mutex); valid=false;
    // Keep the forwarder: Better-Endfield retains retired hook nodes in-process.
}
inline nlohmann::json snapshot() {
    std::lock_guard lock(mutex);
    return {{"enabled",enabled.load()},{"sample_count",samples},{"thread_id",threadId},
        {"valid_camera",valid},{"fov",fieldOfView},{"view_matrix",viewMatrix},{"projection_matrix",projectionMatrix},
        {"gameplay_modified",false}};
}
}
