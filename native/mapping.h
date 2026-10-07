#pragma once
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <cstring>
#include <sddl.h>
#include <string>
#include <vector>
#include "../protocol/probe_state.h"

namespace endcraft {
class Mapping {
    HANDLE handle_ = nullptr;
    unsigned char* view_ = nullptr;
    std::string name_;
public:
    Mapping() = default;
    Mapping(const Mapping&) = delete;
    Mapping& operator=(const Mapping&) = delete;
    ~Mapping() { close(); }
    bool open(const wchar_t* name=skycraft::proto::kMappingName) {
        using namespace skycraft::proto;
        if (view_) return false;
        const auto size = kMappingBytes;
        // The launcher may elevate Endfield while Minecraft stays unelevated.
        // Grant only this Windows user (plus SYSTEM/admins), with a medium label.
        HANDLE token=nullptr;
        if(!OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)) return false;
        DWORD bytes=0;
        GetTokenInformation(token,TokenUser,nullptr,0,&bytes);
        std::vector<unsigned char> user(bytes);
        const bool queried=GetTokenInformation(token,TokenUser,user.data(),bytes,&bytes)!=FALSE;
        const auto tokenError=GetLastError(); CloseHandle(token);
        if(!queried) { SetLastError(tokenError); return false; }
        LPWSTR sid=nullptr;
        if(!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(user.data())->User.Sid,&sid)) return false;
        const std::wstring sddl=L"D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;"+std::wstring(sid)+L")S:(ML;;NW;;;ME)";
        LocalFree(sid);
        PSECURITY_DESCRIPTOR descriptor=nullptr;
        if(!ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(),SDDL_REVISION_1,&descriptor,nullptr)) return false;
        SECURITY_ATTRIBUTES security{sizeof(security),descriptor,FALSE};
        handle_ = CreateFileMappingW(INVALID_HANDLE_VALUE, &security, PAGE_READWRITE,
            DWORD(size >> 32), DWORD(size), name);
        const auto error = GetLastError();
        LocalFree(descriptor);
        if (!handle_) return false;
        if (error == ERROR_ALREADY_EXISTS) { close(); SetLastError(ERROR_ALREADY_EXISTS); return false; }
        view_ = static_cast<unsigned char*>(MapViewOfFile(handle_, FILE_MAP_ALL_ACCESS, 0, 0, size));
        if (!view_) { const auto code = GetLastError(); close(); SetLastError(code); return false; }
        auto* h = header();
        h->version = kVersion;
        h->skyrimPid = GetCurrentProcessId(); // Upstream field name; now host PID.
        tick();
        // No SKY_IN_GAME flag: loading a probe never transfers character/input authority.
        auto* sky = reinterpret_cast<SkyState*>(view_ + kOffSkyState);
        sky->flags = kSkyLoading | kSkyMenuOpen;
        sky->viewportW = 640; sky->viewportH = 360;
        sky->gameHour = 12.0f;
        InterlockedExchange(reinterpret_cast<volatile LONG*>(&sky->seq), 2);
        MemoryBarrier();
        InterlockedExchange(reinterpret_cast<volatile LONG*>(&h->magic), LONG(kMagic));
        const std::wstring wideName(name); name_.assign(wideName.begin(),wideName.end());
        return true;
    }
    void tick() {
        if (view_) InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&header()->skyrimHeartbeatMs), GetTickCount64());
    }
    skycraft::proto::Header* header() { return reinterpret_cast<skycraft::proto::Header*>(view_); }
    const unsigned char* data() const { return view_; }
    const std::string& name() const { return name_; }
    void publish(std::uint32_t flags, std::uint32_t resolved) {
        if (!view_) return;
        auto* p = reinterpret_cast<ProbeState*>(view_ + kProbeOffset);
        InterlockedIncrement(reinterpret_cast<volatile LONG*>(&p->seq));
        MemoryBarrier();
        p->version = kProbeVersion; p->flags = flags; p->resolvedMethods = resolved;
        p->sampleMs = GetTickCount64(); p->verifiedCapabilities = 0;
        MemoryBarrier();
        InterlockedIncrement(reinterpret_cast<volatile LONG*>(&p->seq));
    }
    void close() {
        if (view_) {
            InterlockedExchange64(reinterpret_cast<volatile LONG64*>(&header()->skyrimHeartbeatMs), 0);
            UnmapViewOfFile(view_); view_ = nullptr;
        }
        if (handle_) { CloseHandle(handle_); handle_ = nullptr; }
    }
};
}
