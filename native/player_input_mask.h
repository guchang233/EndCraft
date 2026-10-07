#pragma once
#include "unity_bridge.h"
#include "nlohmann/json.hpp"
namespace endcraft {
// Own one public controller mask token; never clear masks belonging to the game.
class PlayerInputMask {
    unity::Api api;
    void* controller=nullptr;
    std::uint32_t root=0,token=0;
    bool acquired=false;
    int before=0,after=0;
    std::string error;
    static constexpr const char* assembly="Gameplay.Beyond.dll";
    static constexpr const char* space="Beyond.Gameplay.Core";
public:
    void bind(const BE_HostApiV1* host) {api.bind(host);}
    void release() noexcept {
        if(!acquired) return;
        try {
            bool removed=api.value<bool>(api.method("PlayerController","RemoveActionEnableMask","System.UInt32","System.Boolean",assembly,space),controller,{&token});
            if(!removed) {error="game did not confirm removal of our input mask";return;}
        } catch(const std::exception& e) {error=e.what();return;}
        try {after=api.value<int>(api.method("PlayerController","get_playerActionEnableMask","","Beyond.Gameplay.Core.PlayerController.InputActionType",assembly,space),controller);}
        catch(const std::exception& e) {error=e.what();}
        api.raw()->gchandle_free(api.raw()->context,root);root=0;controller=nullptr;acquired=false;
    }
    void apply(void* character) noexcept {
        if(acquired||!character) return;
        try {
            // PlayerController is a global manager, not Entity.baseController.
            auto* candidate=api.call(api.method("GameInstance","get_playerController","","Beyond.Gameplay.Core.PlayerController",assembly,"Beyond.Gameplay"));
            if(!candidate) return;
            const auto objectClass=reinterpret_cast<const void*(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_object_get_class"));
            const auto parent=reinterpret_cast<const void*(*)(const void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_class_get_parent"));
            if(!objectClass||!parent) throw std::runtime_error("controller type registry absent");
            auto expected=api.klass("PlayerController",assembly,space).class_info;
            bool matches=false;for(auto* k=objectClass(candidate);k;k=parent(k)) if(k==expected) {matches=true;break;}
            if(!matches) throw std::runtime_error("active controller is not a PlayerController");
            auto getter=api.method("PlayerController","get_playerActionEnableMask","","Beyond.Gameplay.Core.PlayerController.InputActionType",assembly,space);
            before=api.value<int>(getter,candidate);int none=0;
            root=api.raw()->gchandle_new(api.raw()->context,candidate,0);
            if(!root) throw std::runtime_error("input controller root failed");
            controller=candidate;
            try {token=api.value<std::uint32_t>(api.method("PlayerController","AddActionEnableMask","Beyond.Gameplay.Core.PlayerController.InputActionType","System.UInt32",assembly,space),candidate,{&none});}
            catch(...) {api.raw()->gchandle_free(api.raw()->context,root);root=0;controller=nullptr;throw;}
            acquired=true;after=api.value<int>(getter,candidate);
            if(after!=0) {release();throw std::runtime_error("controller did not apply the requested empty action mask");}
            error.clear();
        } catch(const std::exception& e) {error=e.what();}
    }
    nlohmann::json snapshot() const {return {{"acquired",acquired},{"mask_before",before},{"mask_after",after},{"error",error}};}
};
}
