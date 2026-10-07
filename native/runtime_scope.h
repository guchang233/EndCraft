#pragma once
#include <windows.h>

// Metadata discovery runs on an RPC worker. Attach that worker explicitly;
// the game-owned camera tick is already an IL2CPP thread.
class RuntimeThreadScope {
    using Detach=void(*)(void*);
    void* attached_=nullptr;
    Detach detach_=nullptr;
    bool ready_=true;
public:
    explicit RuntimeThreadScope(bool needed) {
        if(!needed) return;
        const auto assembly=GetModuleHandleW(L"GameAssembly.dll");
        if(!assembly) return; // Independent ABI test process.
        const auto current=reinterpret_cast<void*(*)()>(GetProcAddress(assembly,"il2cpp_thread_current"));
        const auto domain=reinterpret_cast<void*(*)()>(GetProcAddress(assembly,"il2cpp_domain_get"));
        const auto attach=reinterpret_cast<void*(*)(void*)>(GetProcAddress(assembly,"il2cpp_thread_attach"));
        detach_=reinterpret_cast<Detach>(GetProcAddress(assembly,"il2cpp_thread_detach"));
        if(!current||!domain||!attach||!detach_) { ready_=false; return; }
        if(current()) return;
        auto* value=domain();
        if(!value) { ready_=false; return; }
        attached_=attach(value); ready_=attached_!=nullptr;
    }
    ~RuntimeThreadScope() { if(attached_) detach_(attached_); }
    RuntimeThreadScope(const RuntimeThreadScope&)=delete;
    RuntimeThreadScope& operator=(const RuntimeThreadScope&)=delete;
    bool ready() const { return ready_; }
};
