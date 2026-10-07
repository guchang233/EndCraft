#pragma once
#include "unity_bridge.h"
#include "bridge_memory.h"
#include "coordinate_map.h"
#include "nlohmann/json.hpp"
#include <unordered_map>
#include <cmath>

namespace endcraft {
// Minecraft owns hit detection, weapon cooldowns, crits and projectile damage.
// Forward the resulting amount through the host's ordinary damage modifier pipeline.
class NativeCombat {
    unity::Api api;
    struct Actor {void* entity;void* ability;std::uint32_t entityRoot,abilityRoot;unity::V3 position;double hp,maxHp;};
    std::unordered_map<std::uint32_t,Actor> targets;
    std::uint64_t lastScan=0,requests=0,accepted=0,rejected=0,confirmedHpDrops=0;
    std::uint32_t watched=0;
    double hpBefore=0,serverHpBefore=0,hpAfter=0,serverHpAfter=0;
    std::uint64_t watchUntil=0;
    std::string error,lastResult;
    double lastMcDamage=0,lastHostDamage=0;
    std::uint64_t localHpDrops=0;
    void* protectionBox=nullptr;
    std::uint32_t protectionRoot=0,protectedPlayer=0;
    std::string protectionError;
    double playerHp=0,playerMaxHp=0;
    static constexpr double virtualEnemyHealth=20.0;
    static constexpr const char* assembly="Gameplay.Beyond.dll";
    static constexpr const char* space="Beyond.Gameplay.Core";
    BE_ResolvedMethodV1 method(const char* type,const char* name,const char* params="",const char* result="System.Void") {
        return api.method(type,name,params,result,assembly,space);
    }
    template<class T> T enumValue(const char* type,const char* name,const char* asmName=assembly,const char* ns=space) {
        auto runtimeModule=GetModuleHandleW(L"GameAssembly.dll");
        auto field=reinterpret_cast<const void*(*)(const void*,const char*)>(GetProcAddress(runtimeModule,"il2cpp_class_get_field_from_name"));
        auto read=reinterpret_cast<void(*)(const void*,void*)>(GetProcAddress(runtimeModule,"il2cpp_field_static_get_value"));
        auto size=reinterpret_cast<int(*)(const void*,unsigned*)>(GetProcAddress(runtimeModule,"il2cpp_class_value_size"));
        const auto klass=api.klass(type,asmName,ns);unsigned align=0;
        if(!field||!read||!size||size(klass.class_info,&align)!=sizeof(T)) throw std::runtime_error("combat enum ABI mismatch");
        auto f=field(klass.class_info,name);if(!f) throw std::runtime_error("combat enum member missing");
        T output{};read(f,&output);return output;
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
            Actor actor{entity,ability,api.raw()->gchandle_new(api.raw()->context,entity,0),api.raw()->gchandle_new(api.raw()->context,ability,0),at,hp,max};
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
    void releaseProtection() noexcept {
        if(protectionBox) try {
            api.call(method("AbilitySystem/AllowedDamageMaskHandle","Revert"),api.raw()->object_unbox(api.raw()->context,protectionBox));
        } catch(const std::exception& e) {protectionError=e.what();}
        if(protectionRoot) api.raw()->gchandle_free(api.raw()->context,protectionRoot);
        protectionBox=nullptr;protectionRoot=0;protectedPlayer=0;
    }
    void protect(void* player,bool invulnerable) noexcept {
        try {
            auto id=api.value<std::uint32_t>(method("Entity","get_instanceUid","","System.UInt32"),player);
            if(protectionBox&&(!invulnerable||id!=protectedPlayer)) releaseProtection();
            auto* ability=api.call(method("Entity","get_abilityCom","","Beyond.Gameplay.Core.AbilitySystem"),player);
            if(invulnerable&&ability&&!protectionBox) {
                auto mask=enumValue<std::int64_t>("DamageDecorateMask","None",assembly,"Beyond.Gameplay");
                auto* boxed=api.call(method("AbilitySystem","RequestAllowedDamageMask","Beyond.Gameplay.DamageDecorateMask","Beyond.Gameplay.Core.AbilitySystem.AllowedDamageMaskHandle"),ability,{&mask});
                if(!boxed) throw std::runtime_error("creative protection handle missing");
                auto root=api.raw()->gchandle_new(api.raw()->context,boxed,0);
                if(!root) {
                    api.call(method("AbilitySystem/AllowedDamageMaskHandle","Revert"),api.raw()->object_unbox(api.raw()->context,boxed));
                    throw std::runtime_error("creative protection root failed");
                }
                protectionBox=boxed;protectionRoot=root;protectedPlayer=id;
            }
            if(ability) {playerHp=api.value<double>(method("AbilitySystem","get_hp","","System.Double"),ability);playerMaxHp=api.value<double>(method("AbilitySystem","get_maxHp","","System.Double"),ability);}
            protectionError.clear();
        } catch(const std::exception& e) {protectionError=e.what();}
    }
    void stop(BridgeMemory& memory,bool restoreProtection=true) {if(restoreProtection) releaseProtection();clearTargets();memory.actors({});proto::McEvent event{};for(unsigned i=0;i<proto::kEventRingEntries&&memory.event(event);++i) {} watched=0;}
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
                const auto hp=api.value<double>(method("AbilitySystem","get_hp","","System.Double"),found->second.ability);
                const auto serverHp=api.value<double>(method("AbilitySystem","get_serverHp","","System.Double"),found->second.ability);
                if(hp<=0) {++rejected;lastResult="target_already_dead";continue;}
                // Proxies have Minecraft's 20-point maximum health. Map the actual MC
                // hit to the same fraction of the target's native maximum health.
                double amount=double(std::clamp(event.a,0.f,10000.f))*found->second.maxHp/virtualEnemyHealth;
                int damageType=enumValue<int>("DamageType",(event.flags&proto::kHitFire)?"Fire":"Physical","Common.Beyond.dll","Beyond.GEnums");
                std::int64_t mask=enumValue<std::int64_t>("DamageDecorateMask","None",assembly,"Beyond.Gameplay");
                int visual=enumValue<int>("AbilitySystem/Modifier/DamageVisualImportance","Level0");
                bool option=(event.flags&proto::kHitCritical)!=0;
                auto* boxed=api.call(method("AbilitySystem/Modifier","NewDamage",
                    "Beyond.Gameplay.Core.AbilitySystem|Beyond.Gameplay.Core.AbilitySystem|System.Double|Beyond.GEnums.DamageType|Beyond.Gameplay.DamageDecorateMask|Beyond.Gameplay.Core.AbilitySystem.Modifier.DamageVisualImportance|System.Boolean",
                    "Beyond.Gameplay.Core.AbilitySystem.Modifier"),nullptr,{source,found->second.ability,&amount,&damageType,&mask,&visual,&option});
                unity::Api::TemporaryRoot root(api.raw(),boxed);
                auto* modifier=boxed?api.raw()->object_unbox(api.raw()->context,boxed):nullptr;
                if(!modifier) throw std::runtime_error("combat damage modifier missing");
                const int result=api.value<int>(method("AbilitySystem","ApplyModifier","Beyond.Gameplay.Core.AbilitySystem.Modifier&","Beyond.Gameplay.Core.AbilitySystem.Modifier.ApplyResult"),found->second.ability,{modifier});
                lastMcDamage=event.a;lastHostDamage=amount;
                hpAfter=api.value<double>(method("AbilitySystem","get_hp","","System.Double"),found->second.ability);
                serverHpAfter=api.value<double>(method("AbilitySystem","get_serverHp","","System.Double"),found->second.ability);
                if(result==enumValue<int>("AbilitySystem/Modifier/ApplyResult","Succeed")) {
                    ++accepted;watched=event.formId;hpBefore=hp;serverHpBefore=serverHp;watchUntil=now+10000;lastResult="mc_damage_applied_pending_server_confirmation";
                    if(hpAfter<hp) ++localHpDrops;
                } else {++rejected;lastResult="damage_modifier_rejected_by_game";}
            }
            error.clear();
        } catch(const std::exception& e) {error=e.what();}
    }
    nlohmann::json snapshot() const {
        nlohmann::json actors=nlohmann::json::array();
        for(const auto& [id,a]:targets) actors.push_back({{"id",id},{"position",{a.position.x,a.position.y,a.position.z}},{"hp",a.hp},{"max_hp",a.maxHp}});
        return {{"path","minecraft_damage_modifier"},{"creative_protection",protectionBox!=nullptr},{"protection_error",protectionError},{"player_hp",playerHp},{"player_max_hp",playerMaxHp},{"virtual_enemy_health",virtualEnemyHealth},{"last_mc_damage",lastMcDamage},{"last_host_damage",lastHostDamage},{"local_hp_drops",localHpDrops},{"targets",actors},{"nearby_enemies",targets.size()},{"requests",requests},{"accepted",accepted},{"rejected",rejected},
            {"confirmed_hp_drops",confirmedHpDrops},{"watched_target",watched},{"hp_before",hpBefore},{"hp_after",hpAfter},
            {"server_hp_before",serverHpBefore},{"server_hp_after",serverHpAfter},{"last_result",lastResult},{"error",error}};
    }
};
}
