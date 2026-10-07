// Keep the Host's settings location consistent with the per-game bootstrap.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <filesystem>
#include <string>
#include <cstring>

namespace {
DWORD WINAPI EndCraftSettingsEnvironment(LPCWSTR name,LPWSTR buffer,DWORD capacity) {
    if (_wcsicmp(name,L"LOCALAPPDATA")==0) {
        wchar_t module[32768]{};
        HMODULE host=GetModuleHandleW(L"BetterEndfield.Host.dll");
        if(host&&GetModuleFileNameW(host,module,32768)) {
            const auto config=std::filesystem::path(module).parent_path().parent_path()/L"endcraft-paths.ini";
            wchar_t root[32768]{};
            GetPrivateProfileStringW(L"Paths",L"local_app_data",L"",root,32768,config.c_str());
            const auto length=DWORD(wcslen(root));
            if(length) {
                if(capacity<=length) return length+1;
                if(buffer) std::memcpy(buffer,root,(length+1)*sizeof(wchar_t));
                return length;
            }
        }
    }
    return GetEnvironmentVariableW(name,buffer,capacity);
}
}
#define GetEnvironmentVariableW EndCraftSettingsEnvironment
#include "../third_party/Better-Endfield/native/shared/host/settings_store.cpp"
#undef GetEnvironmentVariableW
