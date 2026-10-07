#pragma once
#include "unity_bridge.h"
#include "bridge_memory.h"
#include "coordinate_map.h"
#include "nlohmann/json.hpp"
#include <unordered_map>
#include <cmath>

namespace endcraft {
// A Minecraft hit requests the active character's ordinary targeted attack.
// Damage, range, cost, resistance and server synchronization remain game-owned.
class NativeCombat {
    unity::Api api;
    struct Actor {void* entity;void* ability;std::uint32_t entityRoot,abilityRoot;};
    std::unordered_map<std::uint32_t,Actor> targets;
    std::uint64_t lastScan=0,requests=0,accepted=0,rejected=0,confirmedHpDrops=0;
    std::uint32_t watched=0;
    double hpBefore=0,serverHpBefore=0,hpAfter=0,serverHpAfter=0;
    std::uint64_t watchUntil=0;
    std::string error,lastResult;
    static constexpr const char* assembly="Gameplay.Beyond.dll";
    static constexpr const char* space="Beyond.Gameplay.Core";
    BE_ResolvedMethodV1 method(const char* type,const char* name,const char* params="",const char* result="System.Void") {
        return api.method(type,name,params,result,assembly,space);
    }
    void clearTargets() {
        if(api.raw()) for(auto& [id,a]:targets) {
            api.raw()->gchandle_free(api.raw()->context,a.entityRoot);
            api.raw()->gchandle_free(api.raw()->context,a.abilityRoot);
        }
        targets.clear();
    }
    void scan(void* player,unity::V3 position,unity::V3 origin,unity::V3 mcOrigin,BridgeMemory& memory) {
        auto type=api.klass("ObjectMono",assembly,space);
        auto* objects=api.call(api.method("Resources","FindObjectsOfTypeAll","System.Type","UnityEngine.Object[]"),nullptr,{type.type_object});
        unity::Api::TemporaryRoot keep(api.raw(),objects);
        auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_array_length"));
        if(!length||length(objects)>20000) throw std::runtime_error("combat object inventory invalid");
        const auto ownFaction=api.value<int>(method("Entity","get_factionIndex","","Beyond.Gameplay.Core.FactionIndex"),player);
        clearTargets();std::vector<proto::ActorRecord> records;
        for(std::size_t i=0;i<length(objects)&&records.size()<proto::kMaxActors;++i) {
            void* mono=nullptr;std::memcpy(&mono,static_cast<unsigned char*>(objects)+32+i*sizeof(void*),sizeof(void*));
            if(!mono) continue;
            auto* entity=api.call(method("ObjectMono","get_entity","","Beyond.Gameplay.Core.Entity"),mono);
            if(!entity||entity==player) continue;
            auto* enemy=api.call(method("Entity","get_enemyCtrl","","Beyond.Gameplay.Core.EnemyController"),entity);
            if(!enemy||!api.value<bool>(method("Entity","get_alive","","System.Boolean"),entity)) continue;
            if(api.value<int>(method("Entity","get_factionIndex","","Beyond.Gameplay.Core.FactionIndex"),entity)==ownFaction) continue;
            const auto id=api.value<std::uint32_t>(method("Entity","get_instanceUid","","System.UInt32"),entity);
            if(!id||targets.contains(id)) continue;
            const auto at=api.value<unity::V3>(method("Entity","get_position","","UnityEngine.Vector3"),entity);
            const float dx=at.x-position.x,dy=at.y-position.y,dz=at.z-position.z;
            if(!std::isfinite(dx)||!std::isfinite(dy)||!std::isfinite(dz)||dx*dx+dy*dy+dz*dz>64*64) continue;
            auto* ability=api.call(method("Entity","get_abilityCom","","Beyond.Gameplay.Core.AbilitySystem"),entity);
            if(!ability) continue;
            double hp=api.value<double>(method("AbilitySystem","get_hp","","System.Double"),ability);
            double max=api.value<double>(method("AbilitySystem","get_maxHp","","System.Double"),ability);
            if(!std::isfinite(hp)||!std::isfinite(max)||hp<=0||max<=0) continue;
            Actor actor{entity,ability,api.raw()->gchandle_new(api.raw()->context,entity,0),api.raw()->gchandle_new(api.raw()->context,ability,0)};
            if(!actor.entityRoot||!actor.abilityRoot) throw std::runtime_error("combat actor root failed");
            targets.emplace(id,actor);
            auto mc=coordinates::toMc(origin,mcOrigin,at);
            proto::ActorRecord record{};record.formId=id;record.flags=proto::kActorHostile;
            record.x=mc.x;record.y=mc.y;record.z=mc.z;record.width=.7f;record.height=1.8f;
            record.healthFrac=float(hp/max);std::memcpy(record.name,"Endfield enemy",15);
            records.push_back(record);
        }
        memory.actors(records);
    }
public:
    void bind(const BE_HostApiV1* runtime) {api.bind(runtime);}
    void stop(BridgeMemory& memory) {clearTargets();memory.actors({});proto::McEvent event{};for(unsigned i=0;i<proto::kEventRingEntries&&memory.event(event);++i) {} watched=0;}
    void tick(void* player,unity::V3 position,unity::V3 origin,unity::V3 mcOrigin,BridgeMemory& memory) noexcept {
        try {
            const auto now=GetTickCount64();
            if(watched&&now<watchUntil) {
                auto found=targets.find(watched);
                if(found!=targets.end()) {
                    hpAfter=api.value<double>(method("AbilitySystem","get_hp","","System.Double"),found->second.ability);
                    serverHpAfter=api.value<double>(method("AbilitySystem","get_serverHp","","System.Double"),found->second.ability);
                    if(hpAfter<hpBefore&&serverHpAfter<serverHpBefore) {++confirmedHpDrops;lastResult="enemy_hp_and_server_hp_decreased";watched=0;}
                }
            } else if(watched) {watched=0;lastResult="attack_without_confirmed_hp_drop";}
            if(now-lastScan>=250) {lastScan=now;scan(player,position,origin,mcOrigin,memory);}
            proto::McEvent event{};
            for(unsigned i=0;i<32&&memory.event(event);++i) {
                if(event.type!=proto::kEvHitActor) continue;
                ++requests;auto found=targets.find(event.formId);
                if(found==targets.end()||!std::isfinite(event.a)||event.a<=0) {++rejected;lastResult="unknown_or_invalid_target";continue;}
                auto* source=api.call(method("Entity","get_abilityCom","","Beyond.Gameplay.Core.AbilitySystem"),player);
                if(!source) {++rejected;lastResult="player_ability_missing";continue;}
                auto* boxed=api.call(method("AbilitySystem","get_selfTargetHandle","","Beyond.Gameplay.Core.TargetHandleView"),found->second.ability);
                unity::Api::TemporaryRoot root(api.raw(),boxed);
                auto* target=api.raw()->object_unbox(api.raw()->context,boxed);
                if(!target) throw std::runtime_error("combat target handle missing");
                const auto hp=api.value<double>(method("AbilitySystem","get_hp","","System.Double"),found->second.ability);
                const auto serverHp=api.value<double>(method("AbilitySystem","get_serverHp","","System.Double"),found->second.ability);
                bool cast=api.value<bool>(method("AbilitySystem","TryCastNormalAttack","Beyond.Gameplay.Core.TargetHandleView","System.Boolean"),source,{target});
                if(cast) {++accepted;watched=event.formId;hpBefore=hp;serverHpBefore=serverHp;hpAfter=hp;serverHpAfter=serverHp;watchUntil=now+10000;lastResult="ordinary_attack_accepted_pending_damage";}
                else {++rejected;lastResult="ordinary_attack_rejected_by_game";}
            }
            error.clear();
        } catch(const std::exception& e) {error=e.what();}
    }
    nlohmann::json snapshot() const {
        return {{"path","ordinary_targeted_attack"},{"nearby_enemies",targets.size()},{"requests",requests},{"accepted",accepted},{"rejected",rejected},
            {"confirmed_hp_drops",confirmedHpDrops},{"watched_target",watched},{"hp_before",hpBefore},{"hp_after",hpAfter},
            {"server_hp_before",serverHpBefore},{"server_hp_after",serverHpAfter},{"last_result",lastResult},{"error",error}};
    }
};
}
