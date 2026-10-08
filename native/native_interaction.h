#pragma once
#include "unity_bridge.h"
#include "coordinate_map.h"
#include "bridge_memory.h"
#include "nlohmann/json.hpp"
#include <mutex>
#include <set>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <atomic>

namespace endcraft {
// Dispatch through the game's normal bomb/hittable path, including ECS scenery.
class NativeInteraction {
    unity::Api api;
    std::mutex sampleMutex;
    std::string bombMethod;
    float bombValue=0;
    std::int64_t bombMask=0;
    std::uint64_t explosions=0,hits=0,skipped=0,nativeBombSamples=0;
    unsigned lastColliders=0,lastTargets=0;
    std::string error,state="waiting_for_native_bomb_sample";
    bool calibrationLoaded=false;
    std::atomic<bool> dispatching=false;
    static constexpr const char* assembly="Gameplay.Beyond.dll";
    static constexpr const char* space="Beyond.Gameplay.Core";
    BE_ResolvedFieldV1 field(const char* type,const char* name,const char* expected,const char* ns=space) {
        BE_FieldDescriptorV1 descriptor{assembly,ns,type,name,expected};BE_ResolvedFieldV1 out{};
        if(api.raw()->resolve_field(api.raw()->context,&descriptor,&out)!=BE_Result_Ok||!out.field_info) throw std::runtime_error("native interaction field contract missing");
        return out;
    }
    std::size_t valueSize(const void* cls) {
        auto fn=reinterpret_cast<int(*)(const void*,unsigned*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_class_value_size"));unsigned alignment=0;
        if(!fn) throw std::runtime_error("native value type registry missing");
        return fn(cls,&alignment);
    }
    std::filesystem::path calibrationPath() {
        wchar_t directory[32768]{};
        if(!GetEnvironmentVariableW(L"LOCALAPPDATA",directory,32768)) throw std::runtime_error("local calibration directory missing");
        return std::filesystem::path(directory)/L"BetterEndfield/EndCraft/native-bomb-calibration.json";
    }
    std::string gameFingerprint() {
        wchar_t path[32768]{};WIN32_FILE_ATTRIBUTE_DATA data{};
        if(!GetModuleFileNameW(GetModuleHandleW(L"GameAssembly.dll"),path,32768)||!GetFileAttributesExW(path,GetFileExInfoStandard,&data)) throw std::runtime_error("game version fingerprint unavailable");
        return std::to_string(data.nFileSizeHigh)+":"+std::to_string(data.nFileSizeLow)+":"+std::to_string(data.ftLastWriteTime.dwHighDateTime)+":"+std::to_string(data.ftLastWriteTime.dwLowDateTime);
    }
public:
    void bind(const BE_HostApiV1* host) {
        api.bind(host);
        if(calibrationLoaded) return;
        calibrationLoaded=true;
        try {
            std::ifstream stream(calibrationPath());if(!stream) return;
            const auto data=nlohmann::json::parse(stream);
            const auto id=data.at("method").get<std::string>();const auto value=data.at("strength").get<float>();
            if(data.at("game")!=gameFingerprint()||id.empty()||id.size()>128||!std::isfinite(value)||value<=0) return;
            std::lock_guard lock(sampleMutex);bombMethod=id;bombValue=value;bombMask=data.at("bomb_mask").get<std::int64_t>();state="native_bomb_calibration_restored";
        } catch(...) {} // stale or damaged calibration requires a fresh native sample
    }
    // Called on the native Hit hook before forwarding; never holds scene objects.
    void observeBomb(void* method,float value,std::int64_t mask) noexcept {
        try {
            if(dispatching.load()||!api.raw()||!method||!std::isfinite(value)||value<=0) return;
            const auto f=field("DamageDecorateMask","Bomb","Beyond.Gameplay.DamageDecorateMask","Beyond.Gameplay");
            auto* box=api.raw()->field_get_value_object(api.raw()->context,f.field_info,nullptr);
            if(!box) return;
            const auto bit=*static_cast<const std::int64_t*>(api.raw()->object_unbox(api.raw()->context,box));
            if(!(mask&bit)) return;
            auto dll=GetModuleHandleW(L"GameAssembly.dll");
            auto length=reinterpret_cast<int(*)(void*)>(GetProcAddress(dll,"il2cpp_string_length"));
            auto chars=reinterpret_cast<const wchar_t*(*)(void*)>(GetProcAddress(dll,"il2cpp_string_chars"));
            if(!length||!chars||length(method)<=0||length(method)>128) return;
            const auto size=WideCharToMultiByte(CP_UTF8,0,chars(method),length(method),nullptr,0,nullptr,nullptr);
            std::string text(size,'\0');WideCharToMultiByte(CP_UTF8,0,chars(method),length(method),text.data(),size,nullptr,nullptr);
            {std::lock_guard lock(sampleMutex);bombMethod=text;bombValue=value;bombMask=bit;++nativeBombSamples;state="native_bomb_calibrated";}
            const auto path=calibrationPath();std::filesystem::create_directories(path.parent_path());
            std::ofstream stream(path,std::ios::trunc);
            stream<<nlohmann::json{{"game",gameFingerprint()},{"method",text},{"strength",value},{"bomb_mask",bit}}.dump(2);
        } catch(...) {} // observation cannot interrupt the game's original hit
    }
    bool explode(void* player,const proto::McEvent& event,unity::V3 origin,unity::V3 mcOrigin) noexcept {
        ++explosions;lastTargets=lastColliders=0;
        try {
            if(!std::isfinite(event.a)||!std::isfinite(event.b)||!std::isfinite(event.c)||!std::isfinite(event.d)||event.d<=0||event.d>32) throw std::runtime_error("invalid explosion pose or radius");
            std::string methodId;float strength=0;std::int64_t mask=0;
            {std::lock_guard lock(sampleMutex);methodId=bombMethod;strength=bombValue;mask=bombMask;}
            if(methodId.empty()) {++skipped;state="waiting_for_native_bomb_sample";return false;}
            auto managerField=field("GameWorld","battleHitReactionManager","Beyond.Gameplay.Core.BattleHitReactionManager");
            auto* manager=api.raw()->field_get_value_object(api.raw()->context,managerField.field_info,nullptr);
            if(!manager) throw std::runtime_error("native hittable manager unavailable");
            unity::Api::TemporaryRoot managerRoot(api.raw(),manager);
            const auto center=coordinates::toHost(origin,mcOrigin,{event.a,event.b,event.c});float radius=event.d*2;
            auto* colliders=api.call(api.method("Physics","OverlapSphereV2","UnityEngine.Vector3|System.Single","UnityEngine.ECSColliderResultProxy[]","UnityEngine.PhysicsModule.dll"),nullptr,{const_cast<unity::V3*>(&center),&radius});
            unity::Api::TemporaryRoot colliderRoot(api.raw(),colliders);
            auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_array_length"));
            if(!length||length(colliders)>8192) throw std::runtime_error("native bomb overlap exceeded bound");
            const auto stride=valueSize(api.klass("ECSColliderResultProxy","UnityEngine.PhysicsModule.dll").class_info);
            if(stride!=24) throw std::runtime_error("ECS collider ABI changed");
            const auto infoClass=api.klass("BattleHitReactionManager/HitInfo",assembly,space);
            const auto infoSize=valueSize(infoClass.class_info);
            if(infoSize<64||infoSize>256) throw std::runtime_error("native hit info ABI changed");
            const auto targetField=field("BattleHitReactionManager/HitInfo","hitTarget","Beyond.ObjectPtr<Beyond.Gameplay.Core.IHittableObject>");
            if(targetField.offset<16||std::size_t(targetField.offset-16)+16>infoSize) throw std::runtime_error("native hit target layout invalid");
            auto dll=GetModuleHandleW(L"GameAssembly.dll");
            auto fieldType=reinterpret_cast<const void*(*)(const void*)>(GetProcAddress(dll,"il2cpp_field_get_type"));
            auto classFromType=reinterpret_cast<const void*(*)(const void*)>(GetProcAddress(dll,"il2cpp_class_from_type"));
            auto classField=reinterpret_cast<const void*(*)(const void*,const char*)>(GetProcAddress(dll,"il2cpp_class_get_field_from_name"));
            auto offset=reinterpret_cast<int(*)(const void*)>(GetProcAddress(dll,"il2cpp_field_get_offset"));
            if(!fieldType||!classFromType||!classField||!offset) throw std::runtime_error("native ObjectPtr registry missing");
            auto ptrClass=classFromType(fieldType(targetField.field_info));
            auto objField=classField(ptrClass,"obj"),uidField=classField(ptrClass,"cachedUid");
            if(!objField||!uidField||valueSize(ptrClass)!=16||offset(objField)!=16||offset(uidField)!=24) throw std::runtime_error("native ObjectPtr ABI changed");
            auto* methodString=api.string(methodId);unity::Api::TemporaryRoot methodRoot(api.raw(),methodString);
            std::set<std::pair<std::uintptr_t,std::uint32_t>> seen;
            bool important=false;
            struct DispatchGuard {std::atomic<bool>& flag;~DispatchGuard(){flag.store(false);}} guard{dispatching};dispatching.store(true);
            lastColliders=unsigned(length(colliders));
            for(unsigned i=0;i<lastColliders;++i) {
                auto* proxy=static_cast<unsigned char*>(colliders)+32+i*stride;
                auto* boxed=api.raw()->object_new(api.raw()->context,infoClass.class_info);
                unity::Api::TemporaryRoot infoRoot(api.raw(),boxed);
                auto* info=api.raw()->object_unbox(api.raw()->context,boxed);
                if(!info) throw std::runtime_error("native hit info allocation failed");
                auto point=center;
                api.call(api.method("ECSColliderResultProxy","TryGetClosestPoint","UnityEngine.Vector3|UnityEngine.Vector3&","System.Boolean","UnityEngine.PhysicsModule.dll"),proxy,{const_cast<unity::V3*>(&center),&point});
                if(!api.value<bool>(api.method("BattleHitReactionManager","TryGetHitInfo","Beyond.Gameplay.Core.Entity|UnityEngine.ECSColliderResultProxy|UnityEngine.Vector3|Beyond.Gameplay.Core.BattleHitReactionManager.HitInfo&","System.Boolean",assembly,space),manager,{player,proxy,&point,info})) continue;
                // ObjectPtr is read only after the game itself constructed it.
                std::uintptr_t target=0;std::uint32_t uid=0;
                auto* pointer=static_cast<unsigned char*>(info)+targetField.offset-16;
                std::memcpy(&target,pointer,sizeof(target));std::memcpy(&uid,pointer+8,sizeof(uid));
                if(!target||!uid||!seen.emplace(target,uid).second) continue;
                api.call(api.method("BattleHitReactionManager","Hit","Beyond.Gameplay.Core.Entity|System.String|System.Single|Beyond.Gameplay.DamageDecorateMask|System.Boolean|Beyond.Gameplay.Core.BattleHitReactionManager.HitInfo","System.Void",assembly,space),manager,{player,methodString,&strength,&mask,&important,info});
                ++hits;++lastTargets;
            }
            error.clear();state=lastTargets?"native_bomb_hits_dispatched":"no_native_hittable_in_range";
            return lastTargets>0;
        } catch(const std::exception& e) {error=e.what();state="native_bomb_dispatch_failed";return false;}
    }
    nlohmann::json snapshot() {
        std::lock_guard lock(sampleMutex);
        return {{"state",state},{"native_bomb_samples",nativeBombSamples},{"method_learned",!bombMethod.empty()},
            {"explosions",explosions},{"native_hittable_hits",hits},{"skipped",skipped},{"last_colliders",lastColliders},{"last_targets",lastTargets},{"error",error}};
    }
};
}
