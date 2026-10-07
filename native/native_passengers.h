#pragma once
#include "unity_bridge.h"
#include "coordinate_map.h"
#include "../protocol/endcraft_protocol.h"
#include "nlohmann/json.hpp"
#include <unordered_map>
#include <cmath>

namespace endcraft {
// MC owns seat capacity and the passenger pose. Hold only the original actor's
// movement/AI components; its model, health and ordinary damage pipeline remain live.
class NativePassengers {
    unity::Api api;
    struct Session {
        void* entity=nullptr;
        void* movement=nullptr;
        void* root=nullptr;
        std::vector<std::uint32_t> roots;
        std::vector<void*> paused;
        unity::V3 desired{};
        float yaw=0;
        std::uint32_t vehicle=0;
        std::uint64_t heartbeat=0;
    };
    std::unordered_map<std::uint32_t,Session> sessions;
    std::uint64_t boards=0,releases=0,poses=0,rejected=0;
    std::string error,lastRelease;
    std::uint64_t lastReleaseBeatAge=0;
    static constexpr const char* assembly="Gameplay.Beyond.dll";
    static constexpr const char* space="Beyond.Gameplay.Core";
    BE_ResolvedMethodV1 method(const char* type,const char* name,const char* args="",const char* result="System.Void") {
        return api.method(type,name,args,result,assembly,space);
    }
    void root(Session& s,void* object) {
        auto handle=api.raw()->gchandle_new(api.raw()->context,object,0);
        if(!handle) throw std::runtime_error("passenger managed root failed");
        s.roots.push_back(handle);
    }
    void pause(Session& s,void* component) {
        if(!component || api.value<bool>(method("BaseComponent","get_isPaused","","System.Boolean"),component)) return;
        root(s,component);
        // Record before invoking so a partial acquisition can restore it too.
        s.paused.push_back(component);
        api.call(method("BaseComponent","Pause"),component);
    }
    void release(Session& s,const char* reason="bridge_stop") noexcept {
        lastRelease=reason;lastReleaseBeatAge=GetTickCount64()-s.heartbeat;
        bool restore=true;
        try {restore=!api.value<bool>(method("Entity","get_markReleased","","System.Boolean"),s.entity)
            && !api.value<bool>(method("Entity","get_isPaused","","System.Boolean"),s.entity);}
        catch(const std::exception& e) {error=e.what();restore=false;}
        if(restore) for(auto it=s.paused.rbegin();it!=s.paused.rend();++it) try {
            api.call(method("BaseComponent","Resume"),*it);
        } catch(const std::exception& e) {error=e.what();}
        for(auto handle:s.roots) api.raw()->gchandle_free(api.raw()->context,handle);
        s.paused.clear();s.roots.clear();++releases;
    }
    static float distanceSquared(unity::V3 a,unity::V3 b) {
        return (a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z);
    }
public:
    void bind(const BE_HostApiV1* host) {api.bind(host);}
    bool mounted(std::uint32_t id) const {return sessions.contains(id);}
    void stop() noexcept {for(auto& [id,s]:sessions) release(s);sessions.clear();}
    void accept(const proto::McEvent& event,void* entity,bool rideable,unity::V3 origin,unity::V3 mcOrigin) noexcept {
        try {
            auto found=sessions.find(event.formId);
            if(event.flags==0) {
                if(found!=sessions.end()) {
                    auto& s=found->second;
                    if(std::isfinite(event.a)&&std::isfinite(event.b)&&std::isfinite(event.c)) {
                        auto landing=coordinates::toHost(origin,mcOrigin,unity::V3{event.a,event.b,event.c});
                        if(distanceSquared(s.desired,landing)<64.f) {
                            bool warning=false;
                            if(s.movement) api.call(method("MovementComponent","TeleportTo","UnityEngine.Vector3|System.Boolean"),s.movement,{&landing,&warning});
                            else api.call(method("RootComponent","set_position","UnityEngine.Vector3"),s.root,{&landing});
                        }
                    }
                    release(s,"guest_dismount");sessions.erase(found);
                }
                return;
            }
            if(event.flags!=1||!entity||!rideable||!event.weapon||!std::isfinite(event.a)||!std::isfinite(event.b)||!std::isfinite(event.c)||!std::isfinite(event.d)) {++rejected;return;}
            auto desired=coordinates::toHost(origin,mcOrigin,{event.a,event.b,event.c});
            if(found==sessions.end()) {
                auto at=api.value<unity::V3>(method("Entity","get_position","","UnityEngine.Vector3"),entity);
                if(distanceSquared(at,desired)>9.f || api.value<bool>(method("Entity","get_isPaused","","System.Boolean"),entity)) {++rejected;return;}
                auto [it,inserted]=sessions.try_emplace(event.formId);found=it;
                auto& s=it->second;s.entity=entity;s.desired=desired;
                try {
                    root(s,entity);
                    s.movement=api.call(method("Entity","get_movementComponent","","Beyond.Gameplay.Core.MovementComponent"),entity);
                    s.root=api.call(method("Entity","get_rootCom","","Beyond.Gameplay.Core.RootComponent"),entity);
                    if(!s.root) throw std::runtime_error("passenger movement/root missing");
                    root(s,s.root);
                    pause(s,api.call(method("Entity","get_enemyAICom","","Beyond.Gameplay.AI.EnemyAIComponent"),entity));
                    pause(s,api.call(method("Entity","get_npcAICom","","Beyond.Gameplay.AI.NpcAIComponent"),entity));
                    pause(s,api.call(method("Entity","get_actorCtrl","","Beyond.Gameplay.Core.ActorController"),entity));
                    pause(s,api.call(method("Entity","get_navCom","","Beyond.Gameplay.Core.NavigationComponent"),entity));
                    pause(s,api.call(method("Entity","get_rotateCom","","Beyond.Gameplay.Core.RotatorComponent"),entity));
                    pause(s,s.movement);
                    ++boards;
                } catch(...) {release(s,"acquisition_failed");sessions.erase(it);throw;}
            }
            auto& s=found->second;
            if(s.entity!=entity || distanceSquared(s.desired,desired)>64.f) {release(s,"invalid_pose_or_identity");sessions.erase(found);++rejected;return;}
            s.desired=desired;s.yaw=event.d;s.vehicle=event.weapon;s.heartbeat=GetTickCount64();
        } catch(const std::exception& e) {error=e.what();++rejected;}
    }
    void tick() noexcept {
        for(auto it=sessions.begin();it!=sessions.end();) {
            auto& s=it->second;
            try {
                if(GetTickCount64()-s.heartbeat>1500 || !api.value<bool>(method("Entity","get_alive","","System.Boolean"),s.entity)
                    || api.value<bool>(method("Entity","get_markReleased","","System.Boolean"),s.entity)
                    || api.value<bool>(method("Entity","get_inCinematic","","System.Boolean"),s.entity)) {
                    release(s,GetTickCount64()-s.heartbeat>1500?"heartbeat_timeout":"actor_unavailable");it=sessions.erase(it);continue;
                }
                bool warning=false;
                if(s.movement) api.call(method("MovementComponent","TeleportTo","UnityEngine.Vector3|System.Boolean"),s.movement,{&s.desired,&warning});
                else api.call(method("RootComponent","set_position","UnityEngine.Vector3"),s.root,{&s.desired});
                // EndCraft reflects MC Z. MC yaw zero and native yaw 180 both
                // face native -Z; native yaw is 180 + MC yaw.
                float angle=(180.f+s.yaw)*3.14159265358979323846f/360.f;
                unity::Quaternion q{0,std::sin(angle),0,std::cos(angle)};
                api.call(method("RootComponent","set_rotation","UnityEngine.Quaternion"),s.root,{&q});
                ++poses;
            } catch(const std::exception& e) {error=e.what();release(s,"pose_error");it=sessions.erase(it);continue;}
            ++it;
        }
    }
    nlohmann::json snapshot() const {
        nlohmann::json riding=nlohmann::json::array();
        for(const auto& [id,s]:sessions) riding.push_back({{"actor",id},{"vehicle",s.vehicle},{"position",{s.desired.x,s.desired.y,s.desired.z}},{"paused_components",s.paused.size()}});
        return {{"riding",riding},{"boards",boards},{"releases",releases},{"poses",poses},{"rejected",rejected},{"last_release",lastRelease},{"last_release_beat_age_ms",lastReleaseBeatAge},{"error",error}};
    }
};
}
