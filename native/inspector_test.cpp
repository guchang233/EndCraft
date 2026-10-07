#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include <windows.h>
#include <filesystem>
#include <iostream>
#include <stdexcept>
using nlohmann::json;
namespace {
BE_Result lastResult=BE_Result_Ok;json lastBody;
BE_Result BE_CALL reply(void*,const char*,BE_Result result,const char* body) {lastResult=result;lastBody=json::parse(body);return BE_Result_Ok;}
void check(bool value,const char* error) {if(!value) throw std::runtime_error(error);}
}
int main(int argc,char** argv) {
    try {
        check(argc==2,"DLL required");
        auto library=LoadLibraryW(std::filesystem::absolute(argv[1]).c_str());check(library!=nullptr,"Load failed");
        auto get=reinterpret_cast<BE_GetThirdPartyModuleV1Fn>(GetProcAddress(library,BETTER_ENDFIELD_THIRD_PARTY_ENTRY_V1));check(get!=nullptr,"Export missing");
        const auto* module=get();
        BE_ThirdPartyHostV1 host{};host.struct_size=sizeof(host);host.version=1;host.module_id=module->id;host.platform="windows-x64";host.reply=reply;
        check(module->initialize(nullptr,"{}")==BE_Result_ContractMismatch,"Null host");
        auto wrong=host;wrong.module_id="other";check(module->initialize(&wrong,"{}")==BE_Result_ContractMismatch,"Identity");
        check(module->initialize(&host,"[]")==BE_Result_InvalidArgument,"Config object");
        check(module->initialize(&host,"{}")==BE_Result_Ok,"Initialize");
        check(module->initialize(&host,"{}")==BE_Result_Conflict,"Duplicate");
        check(module->on_message("status","{\"action\":\"status\"}")==BE_Result_Ok,"Status reply");
        check(lastResult==BE_Result_Ok&&!lastBody["gameplay_modified"].get<bool>(),"Read-only status");
        if(std::string(module->id)=="endcraft.inspect") {
            check(module->on_message("inspect","{\"action\":\"inspect\"}")==BE_Result_Ok,"Inspect reply");
            check(lastResult!=BE_Result_Ok&&lastBody["error"]=="runtime_not_ready","No simulated metadata claim");
        } else {
            check(module->on_message("observe","{\"action\":\"observe\"}")==BE_Result_Ok,"Observe reply");
            check(lastResult==BE_Result_NotReady&&!lastBody.empty(),"No simulated actor claim");
        }
        check(module->on_message("invalid","no-json")==BE_Result_Ok&&lastResult!=BE_Result_Ok,"Invalid JSON reply");
        if(std::string(module->id)=="endcraft.motion"||std::string(module->id)=="endcraft.teleport") {
            check(module->on_message("nudge","{\"action\":\"nudge\",\"delta_raw_units\":[0.05,0,0]}")==BE_Result_Ok&&lastResult==BE_Result_NotReady,"Motion requires a real runtime");
            check(module->on_message("nudge","{\"action\":\"nudge\",\"delta_raw_units\":[0,0]}")==BE_Result_Ok&&lastResult==BE_Result_InvalidArgument,"Motion dimensions");
        }
        check(module->configuration_changed("[]")==BE_Result_InvalidArgument,"Config update");
        module->shutdown();module->shutdown();check(module->on_message("closed","{}")==BE_Result_NotReady,"Shutdown");
        FreeLibrary(library);std::cout<<"reader ABI / identity / read-only / lifecycle tests: PASS\n";
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
