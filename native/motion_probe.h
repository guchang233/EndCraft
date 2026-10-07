#pragma once
#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include "runtime_scope.h"
#include <cmath>
#include <mutex>

namespace motion_probe {
struct Vec {float x,y,z;};
inline float distance(Vec a,Vec b) {return std::sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z));}
class Probe {
    const BE_HostApiV1* api=nullptr;
    BE_ResolvedMethodV1 external{},position{},walkable{},grounded{};
    std::mutex mutex;
    enum Stage {Idle,Queued,Shifted,Finished};
    Stage stage=Idle;
    Vec delta{},origin{},target{};
    std::uintptr_t identity=0;
    unsigned frames=0;
    std::uint64_t queuedAt=0;
    nlohmann::json state={{"state","idle"},{"host_method_invoked",false}};
    template<class T> bool read(const BE_ResolvedMethodV1& method,void* instance,T& output,void** args=nullptr) {
        void* exception=nullptr;
        auto* boxed=api->runtime_invoke(api->context,method.method_info,instance,args,&exception);
        auto* data=!exception&&boxed?api->object_unbox(api->context,boxed):nullptr;
        if(!data) return false;std::memcpy(&output,data,sizeof(T));return true;
    }
    bool write(void* component,Vec desired) {
#ifdef ENDCRAFT_TELEPORT_PROBE
        bool secondary=false;
        void* args[]={&desired,&secondary};void* exception=nullptr;
#else
        void* args[]={&desired};void* exception=nullptr;
#endif
        api->runtime_invoke(api->context,external.method_info,component,args,&exception);
        return exception==nullptr;
    }
    static nlohmann::json vector(Vec value) {return {value.x,value.y,value.z};}
    void finish(const char* reason) {stage=Finished;state["state"]=reason;state["finished_ms"]=GetTickCount64();}
public:
    BE_Result queue(const BE_HostApiV1* runtime,Vec displacement) {
        // A bounded horizontal test only. This API is not continuous player control.
        if(!std::isfinite(displacement.x)||!std::isfinite(displacement.z)||displacement.y!=0||
            distance(displacement,{0,0,0})>0.050001f||distance(displacement,{0,0,0})<0.001f)
            return BE_Result_InvalidArgument;
        std::lock_guard lock(mutex);
        if(stage==Queued||stage==Shifted) return BE_Result_Conflict;
        if(!runtime||!runtime->resolve_method||!runtime->runtime_invoke||!runtime->object_unbox) return BE_Result_NotReady;
        RuntimeThreadScope scope(true);if(!scope.ready()) return BE_Result_NotReady;
        const BE_MethodDescriptorV1 descriptors[]={
#ifdef ENDCRAFT_TELEPORT_PROBE
            {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","TeleportTo","UnityEngine.Vector3|System.Boolean","System.Void",2},
#else
            {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","SetExternalPos","UnityEngine.Vector3","System.Void",1},
#endif
            {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_position",nullptr,"UnityEngine.Vector3",0},
            {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","IsPositionWalkable","UnityEngine.Vector3","System.Boolean",1},
            {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","get_isMovingOnGround",nullptr,"System.Boolean",0}
        };
        BE_ResolvedMethodV1* results[]={&external,&position,&walkable,&grounded};
        for(unsigned i=0;i<4;++i) {
            if(runtime->resolve_method(runtime->context,&descriptors[i],results[i])!=BE_Result_Ok||!results[i]->method_info)
                return BE_Result_NotFound;
        }
        api=runtime;delta=displacement;stage=Queued;frames=0;queuedAt=GetTickCount64();
        state={{"state","queued"},{"host_method_invoked",false},{"requested_delta_raw_units",vector(delta)},
               {"queued_ms",queuedAt},{"samples",nlohmann::json::array()}};
#ifdef ENDCRAFT_TELEPORT_PROBE
        state["method"]="TeleportTo";state["secondary_argument"]=false;
        const auto parameterName=reinterpret_cast<const char*(*)(const void*,std::uint32_t)>(
            GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_method_get_param_name"));
        if(parameterName) {
            const auto* name=parameterName(external.method_info,1);
            state["secondary_parameter_name"]=name?name:"";
        }
#else
        state["method"]="SetExternalPos";
#endif
        return BE_Result_Ok;
    }
    void tick(void* character,void* component,Vec where,Vec speed,bool valid,bool living,bool cinematic) {
        std::lock_guard lock(mutex);
        if(stage!=Queued&&stage!=Shifted) return;
        if(!valid||!living||cinematic||!character||!component) {finish("interrupted_player_not_ready");return;}
        if(stage==Queued) {
            if(GetTickCount64()-queuedAt>3000) {finish("expired_before_execution");return;}
            if(distance(speed,{0,0,0})>.02f) {finish("rejected_player_moving");return;}
            bool onGround=false;
            if(!read(grounded,component,onGround)||!onGround) {finish("rejected_not_grounded");return;}
            origin=where;target={where.x+delta.x,where.y,where.z+delta.z};
            bool canWalk=false;void* args[]={&target};
            if(!read(walkable,component,canWalk,args)||!canWalk) {finish("rejected_position_not_walkable");return;}
            identity=reinterpret_cast<std::uintptr_t>(character);
            state["origin_raw_units"]=vector(origin);state["target_raw_units"]=vector(target);
            state["thread_id"]=GetCurrentThreadId();state["host_method_invoked"]=true;
            if(!write(component,target)) {finish("external_position_exception");return;}
            Vec after{};
            if(read(position,character,after)) {
                state["immediate_position_raw_units"]=vector(after);state["immediate_distance_from_origin"]=distance(after,origin);
                state["observed_position_changed"]=distance(after,origin)>.001f;
            }
            stage=Shifted;state["state"]="sampling_after_shift";return;
        }
        if(identity!=reinterpret_cast<std::uintptr_t>(character)) {finish("interrupted_character_changed");return;}
        state["samples"].push_back({{"frame",++frames},{"position_raw_units",vector(where)},
            {"distance_from_origin",distance(where,origin)},{"distance_from_target",distance(where,target)}});
        if(frames<8) return;
        // Do not pull a player back if they started moving or changed scenes during the test.
        if(distance(where,origin)>.2f||distance(speed,{0,0,0})>.1f) {finish("restore_skipped_player_moved");return;}
        state["restore_invoked"]=true;state["restore_succeeded"]=write(component,origin);
        Vec after{};
        if(read(position,character,after)) {state["restored_position_raw_units"]=vector(after);state["restore_error_raw_units"]=distance(after,origin);}
        finish(state["restore_succeeded"].get<bool>()?"finished":"restore_exception");
    }
    nlohmann::json snapshot() {std::lock_guard lock(mutex);return state;}
};
}
