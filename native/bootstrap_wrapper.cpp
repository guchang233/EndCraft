// Uses the ordinary Windows XInput side-loading route; no manual mapper.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <filesystem>
#include <fstream>
#include <string>
#include <array>
#include <algorithm>

namespace {
std::filesystem::path EndCraftSidecar() {
    wchar_t executable[32768]{};
    GetModuleFileNameW(nullptr,executable,32768);
    return std::filesystem::path(executable).parent_path()/L"EndCraft-bootstrap.ini";
}
DWORD WINAPI EndCraftReadProfile(LPCWSTR section,LPCWSTR key,LPCWSTR fallback,
        LPWSTR buffer,DWORD capacity,LPCWSTR originalFile) {
    const auto sidecar=EndCraftSidecar();
    // A per-game sidecar avoids assuming the launcher's LOCALAPPDATA equals the shell's.
    const auto file=std::filesystem::is_regular_file(sidecar)?sidecar:std::filesystem::path(originalFile);
    const auto count=GetPrivateProfileStringW(section,key,fallback,buffer,capacity,file.c_str());
    std::wofstream log(sidecar.parent_path()/L"EndCraft-loader.log",std::ios::app);
    log<<L"profile="<<file.wstring()<<L" section="<<section<<L" key="<<key
       <<L" value="<<(buffer?buffer:L"")<<L"\n";
    return count;
}
DWORD WINAPI EndCraftEnvironment(LPCWSTR name,LPWSTR buffer,DWORD capacity) {
    if(_wcsicmp(name,L"LOCALAPPDATA")==0) {
        wchar_t root[32768]{};
        GetPrivateProfileStringW(L"Paths",L"local_app_data",L"",root,32768,EndCraftSidecar().c_str());
        const auto length=DWORD(wcslen(root));
        if(length) {
            if(capacity<=length) return length+1;
            if(buffer) std::copy_n(root,length+1,buffer);
            return length;
        }
    }
    return GetEnvironmentVariableW(name,buffer,capacity);
}
HMODULE WINAPI EndCraftLoadLibrary(LPCWSTR path,HANDLE file,DWORD flags) {
    auto result=LoadLibraryExW(path,file,flags);
    const auto error=GetLastError();
    std::wofstream log(EndCraftSidecar().parent_path()/L"EndCraft-loader.log",std::ios::app);
    log<<L"host="<<path<<L" loaded="<<(result!=nullptr)<<L" error="<<(result?0:error)<<L"\n";
    SetLastError(error);
    return result;
}
}
#define GetPrivateProfileStringW EndCraftReadProfile
#define GetEnvironmentVariableW EndCraftEnvironment
#define LoadLibraryExW EndCraftLoadLibrary
#define BETTER_ENDFIELD_INPUT_PROXY_XINPUT14
#define BETTER_ENDFIELD_INPUT_PROXY_LOAD_HOST
#define BETTER_ENDFIELD_INPUT_PROXY_MARKER L"BetterEndfield-xinput1_4-proxy.loaded"
#define BETTER_ENDFIELD_INPUT_PROXY_STATUS L"BetterEndfield-xinput1_4-host.status"
#include "../third_party/Better-Endfield/native/loaders/xinput/input_proxy.cpp"
#undef GetPrivateProfileStringW
#undef GetEnvironmentVariableW
#undef LoadLibraryExW
