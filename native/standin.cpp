// Test harness only. Never injects, opens or controls a game process.
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include "../protocol/probe_state.h"
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>

using nlohmann::json;
namespace {
json lastReply;
BE_Result lastResult=BE_Result_Ok;
void BE_CALL Log(void*,const char* text) { std::cerr<<text<<'\n'; }
BE_Result BE_CALL Reply(void*,const char*,BE_Result result,const char* text) {
    lastReply=json::parse(text);
    lastResult=result;
    return BE_Result_Ok;
}
BE_Result BE_CALL Emit(void*,const char*) { return BE_Result_Ok; }
BE_Result BE_CALL Resolve(void*,const BE_MethodDescriptorV1*,BE_ResolvedMethodV1* out) {
    *out={}; return BE_Result_NotFound;
}
void require(bool condition,const char* message) { if(!condition) throw std::runtime_error(message); }
}
int main(int argc,char** argv) {
    try {
        if(argc<2) { std::cerr<<"Usage: endcraft-standin.exe module.dll [--test | --seconds N]\n"; return 2; }
        bool test=argc>2&&std::string(argv[2])=="--test";
        const std::wstring testMapping=test?L"Local\\EndCraft_test_native_"+std::to_wstring(GetCurrentProcessId())+L"_"+std::to_wstring(GetTickCount64()):L"";
        if(test) SetEnvironmentVariableW(L"ENDCRAFT_STANDIN_MAPPING",testMapping.c_str());
        int seconds=argc>3&&std::string(argv[2])=="--seconds"?std::stoi(argv[3]):5;
        if(seconds<1||seconds>3600) return 2;
        const auto path=std::filesystem::absolute(argv[1]);
        HMODULE library=LoadLibraryW(path.c_str());
        require(library!=nullptr,"DLL load failed");
        auto entry=reinterpret_cast<BE_GetThirdPartyModuleV1Fn>(GetProcAddress(library,BETTER_ENDFIELD_THIRD_PARTY_ENTRY_V1));
        require(entry!=nullptr,"Missing module export");
        const auto* module=entry();
        BE_HostApiV1 runtime{}; runtime.abi_version=1; runtime.resolve_method=Resolve;
        BE_ThirdPartyHostV1 host{sizeof(BE_ThirdPartyHostV1),1,nullptr,"endcraft.probe","windows-x64",".",&runtime,nullptr,Log,Reply,Emit,nullptr};
        if(test) {
            require(module->initialize(nullptr,"{}")==BE_Result_ContractMismatch,"null ABI check");
            auto bad=host; bad.version=99;
            require(module->initialize(&bad,"{}")==BE_Result_ContractMismatch,"version check");
            bad=host; bad.platform="android-arm64";
            require(module->initialize(&bad,"{}")==BE_Result_ContractMismatch,"platform check");
            require(module->initialize(&host,"[]")==BE_Result_InvalidArgument,"configuration check");
        }
        require(module->initialize(&host,"{}")==BE_Result_Ok,"initialization failed (another probe running?)");
        if(test) {
            require(module->initialize(&host,"{}")==BE_Result_Conflict,"duplicate initialization check");
            require(module->on_message("bad","not-json")==BE_Result_Ok,"error reply delivered");
            require(lastReply.contains("error")&&lastResult==BE_Result_InvalidArgument,"malformed input error");
            require(module->configuration_changed("[]")==BE_Result_InvalidArgument,"bad config update");
        }
        require(module->on_message("probe","{\"action\":\"probe\"}")==BE_Result_Ok,"probe reply");
        require(!lastReply["gameplay_enabled"].get<bool>(),"probe cannot enable gameplay");
        require(lastReply["environment"]=="stand_in","must identify simulation");
        for(const auto& p:lastReply["method_probes"]) require(p["state"]=="unresolved"&&!p["verified"].get<bool>(),"mock contracts cannot be verified");
        if(test) {
            HANDLE map=OpenFileMappingW(FILE_MAP_READ,FALSE,testMapping.c_str());
            require(map!=nullptr,"mapping exists");
            auto* data=static_cast<const unsigned char*>(MapViewOfFile(map,FILE_MAP_READ,0,0,skycraft::proto::kMappingBytes));
            require(data!=nullptr,"mapping view");
            const auto* header=reinterpret_cast<const skycraft::proto::Header*>(data);
            require(header->magic==skycraft::proto::kMagic&&header->version==12,"protocol identity");
            const auto* sky=reinterpret_cast<const skycraft::proto::SkyState*>(data+skycraft::proto::kOffSkyState);
            require(!(sky->flags&skycraft::proto::kSkyInGame),"no gameplay authority");
            module->shutdown();
            require(header->skyrimHeartbeatMs==0,"shutdown invalidates heartbeat even with open peer");
            require(module->on_message("after","{\"action\":\"status\"}")==BE_Result_NotReady,"shutdown rejects messages");
            require(module->initialize(&host,"{}")==BE_Result_Conflict,"live peer prevents mapping reset");
            UnmapViewOfFile(data); CloseHandle(map);
            require(module->initialize(&host,"{}")==BE_Result_Ok,"re-enable after peers close");
            module->shutdown(); module->shutdown();
            std::cout<<"native lifecycle / protocol / fail-closed tests: PASS\n";
        } else {
            for(int elapsed=0;elapsed<seconds;++elapsed) {
                module->on_message("status","{\"action\":\"status\"}");
                if(elapsed%5==0) std::cout<<lastReply.dump()<<'\n'<<std::flush;
                std::this_thread::sleep_for(std::chrono::seconds(1));
            }
            module->shutdown();
        }
        // DLL threads are stopped before unloading in this stand-in only.
        FreeLibrary(library);
        return 0;
    } catch(const std::exception& e) { std::cerr<<e.what()<<'\n'; return 1; }
}
