#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include "runtime_scope.h"
#include <cstring>
#include <mutex>
#include <string>

using nlohmann::json;
namespace {
#ifndef ENDCRAFT_INSPECTOR_ID
#define ENDCRAFT_INSPECTOR_ID "endcraft.inspect"
#endif
constexpr char kId[]=ENDCRAFT_INSPECTOR_ID;
BE_ThirdPartyHostV1 host{};
std::mutex mutex;
bool initialized=false;
template<class T> T symbol(HMODULE module,const char* name) {
    return reinterpret_cast<T>(GetProcAddress(module,name));
}
const BE_HostApiV1* runtime() { return host.get_runtime?host.get_runtime(host.context):host.runtime; }
BE_Result BE_CALL initialize(const BE_ThirdPartyHostV1* input,const char* configuration) {
    std::lock_guard lock(mutex);
    if(!input||input->version!=1||input->struct_size<offsetof(BE_ThirdPartyHostV1,emit)+sizeof(input->emit)||
       !input->module_id||std::string(input->module_id)!=kId||!input->platform||std::string(input->platform)!="windows-x64"||!input->reply)
        return BE_Result_ContractMismatch;
    if(initialized) return BE_Result_Conflict;
    try {
        if(!json::parse(configuration?configuration:"{}").is_object()) return BE_Result_InvalidArgument;
        host={};std::memcpy(&host,input,(std::min)(std::size_t(input->struct_size),sizeof(host)));
        initialized=true;return BE_Result_Ok;
    } catch(...) { return BE_Result_InvalidArgument; }
}
BE_Result BE_CALL configure(const char* value) {
    std::lock_guard lock(mutex);
    if(!initialized) return BE_Result_NotReady;
    try { return json::parse(value?value:"{}").is_object()?BE_Result_Ok:BE_Result_InvalidArgument; }
    catch(...) { return BE_Result_InvalidArgument; }
}
json inspect(const json& request) {
    auto* api=runtime();
    if(!api||api->abi_version!=1||!api->resolve_class) throw std::runtime_error("runtime_not_ready");
    const auto assembly=GetModuleHandleW(L"GameAssembly.dll");
    if(!assembly) throw std::runtime_error("game_assembly_not_loaded");
    RuntimeThreadScope scope(true);
    if(!scope.ready()) throw std::runtime_error("worker_attachment_not_ready");
    const auto assemblyName=request.at("assembly").get<std::string>();
    const auto namespaceName=request.at("namespace").get<std::string>();
    const auto className=request.at("class").get<std::string>();
    if(assemblyName.size()>256||namespaceName.size()>256||className.size()>256||assemblyName.empty()||className.empty())
        throw std::runtime_error("invalid_class_descriptor");
    BE_ResolvedClassV1 resolved{};
    const auto result=api->resolve_class(api->context,assemblyName.c_str(),namespaceName.c_str(),className.c_str(),&resolved);
    if(result!=BE_Result_Ok||!resolved.class_info) return {{"result",int(result)},{"state","unresolved"}};
    const auto methods=symbol<const void*(*)(const void*,void**)>(assembly,"il2cpp_class_get_methods");
    const auto methodName=symbol<const char*(*)(const void*)>(assembly,"il2cpp_method_get_name");
    const auto count=symbol<unsigned(*)(const void*)>(assembly,"il2cpp_method_get_param_count");
    const auto parameter=symbol<const void*(*)(const void*,unsigned)>(assembly,"il2cpp_method_get_param");
    const auto parameterName=symbol<const char*(*)(const void*,unsigned)>(assembly,"il2cpp_method_get_param_name");
    const auto returnType=symbol<const void*(*)(const void*)>(assembly,"il2cpp_method_get_return_type");
    const auto methodFlags=symbol<unsigned(*)(const void*,unsigned*)>(assembly,"il2cpp_method_get_flags");
    const auto fields=symbol<const void*(*)(const void*,void**)>(assembly,"il2cpp_class_get_fields");
    const auto fieldName=symbol<const char*(*)(const void*)>(assembly,"il2cpp_field_get_name");
    const auto fieldType=symbol<const void*(*)(const void*)>(assembly,"il2cpp_field_get_type");
    const auto fieldOffset=symbol<int(*)(const void*)>(assembly,"il2cpp_field_get_offset");
    const auto fieldFlags=symbol<unsigned(*)(const void*)>(assembly,"il2cpp_field_get_flags");
    const auto typeName=symbol<char*(*)(const void*)>(assembly,"il2cpp_type_get_name");
    const auto freeMemory=symbol<void(*)(void*)>(assembly,"il2cpp_free");
    const auto parent=symbol<const void*(*)(const void*)>(assembly,"il2cpp_class_get_parent");
    const auto name=symbol<const char*(*)(const void*)>(assembly,"il2cpp_class_get_name");
    const auto space=symbol<const char*(*)(const void*)>(assembly,"il2cpp_class_get_namespace");
    if(!methods||!methodName||!count||!parameter||!returnType||!methodFlags||!fields||!fieldName||!fieldType||!fieldOffset||
       !fieldFlags||!typeName||!freeMemory||!parent||!name||!space) throw std::runtime_error("metadata_exports_incomplete");
    auto type=[&](const void* value) { auto* text=typeName(value);std::string output=text?text:"";if(text) freeMemory(text);return output; };
    json hierarchy=json::array();
    unsigned total=0;
    for(auto* klass=resolved.class_info;klass&&hierarchy.size()<6;klass=parent(klass)) {
        json methodList=json::array(),fieldList=json::array();void* iterator=nullptr;
        while(const auto* method=methods(klass,&iterator)) {
            if(++total>1024) break;
            const auto size=count(method);if(size>64) throw std::runtime_error("invalid_parameter_count");
            json parameters=json::array(),names=json::array();for(unsigned i=0;i<size;++i) {parameters.push_back(type(parameter(method,i)));auto* n=parameterName?parameterName(method,i):nullptr;names.push_back(n?n:"");}
            unsigned implementation=0;const auto flags=methodFlags(method,&implementation);
            methodList.push_back({{"name",methodName(method)},{"parameters",parameters},{"parameter_names",names},{"return",type(returnType(method))},
                                  {"static",bool(flags&0x10)},{"flags",flags}});
        }
        iterator=nullptr;
        while(const auto* field=fields(klass,&iterator)) {
            if(++total>1024) break;
            fieldList.push_back({{"name",fieldName(field)},{"type",type(fieldType(field))},
                                 {"offset",fieldOffset(field)},{"flags",fieldFlags(field)}});
        }
        hierarchy.push_back({{"class",name(klass)},{"namespace",space(klass)},{"methods",methodList},{"fields",fieldList}});
        if(total>1024) break;
    }
    return {{"result",0},{"state","metadata_only"},{"host_pid",GetCurrentProcessId()},
            {"hierarchy",hierarchy},{"truncated",total>1024},{"gameplay_modified",false}};
}
json inventory(const json& request) {
    RuntimeThreadScope thread(true);if(!thread.ready()) throw std::runtime_error("runtime_thread_not_ready");
    auto assembly=GetModuleHandleW(L"GameAssembly.dll");
    auto domain=symbol<void*(*)()>(assembly,"il2cpp_domain_get");
    auto assemblies=symbol<const void**(*)(void*,std::size_t*)>(assembly,"il2cpp_domain_get_assemblies");
    auto image=symbol<const void*(*)(const void*)>(assembly,"il2cpp_assembly_get_image");
    auto imageName=symbol<const char*(*)(const void*)>(assembly,"il2cpp_image_get_name");
    auto classCount=symbol<std::size_t(*)(const void*)>(assembly,"il2cpp_image_get_class_count");
    auto getClass=symbol<const void*(*)(const void*,std::size_t)>(assembly,"il2cpp_image_get_class");
    auto name=symbol<const char*(*)(const void*)>(assembly,"il2cpp_class_get_name");
    auto space=symbol<const char*(*)(const void*)>(assembly,"il2cpp_class_get_namespace");
    if(!domain||!assemblies||!image||!imageName||!classCount||!getClass||!name||!space) throw std::runtime_error("inventory_exports_unavailable");
    std::size_t count=0;auto list=assemblies(domain(),&count);if(count>1024) throw std::runtime_error("assembly_count_exceeded");
    auto target=request.value("assembly",std::string{}),filter=request.value("filter",std::string{});
    if(target.size()>256||filter.size()>128) throw std::runtime_error("inventory_filter_exceeded");
    json output=json::array();
    for(std::size_t i=0;i<count;++i) {
        auto img=image(list[i]);const std::string id=imageName(img);
        if(target.empty()) {output.push_back(id);continue;}
        if(id!=target) continue;
        auto total=classCount(img);if(total>200000) throw std::runtime_error("class_count_exceeded");
        for(std::size_t j=0;j<total;++j) {
            auto cls=getClass(img,j);if(!cls) continue;
            std::string n=name(cls),ns=space(cls);
            if((ns+"."+n).find(filter)!=std::string::npos) output.push_back({{"name",n},{"namespace",ns}});
            if(output.size()>=512) break;
        }
    }
    return {{"items",output},{"gameplay_modified",false}};
}
BE_Result BE_CALL message(const char* requestId,const char* body) {
    std::lock_guard lock(mutex);
    if(!initialized) return BE_Result_NotReady;
    if(!requestId||!body) return BE_Result_InvalidArgument;
    try {
        const auto request=json::parse(body);const auto action=request.at("action").get<std::string>();
        json result;
        if(action=="status") result={{"module",kId},{"host_pid",GetCurrentProcessId()},{"gameplay_modified",false}};
        else if(action=="inspect") result=inspect(request);
        else if(action=="inventory") result=inventory(request);
        else throw std::runtime_error("supported_actions_status_inspect");
        const auto encoded=result.dump();return host.reply(host.context,requestId,BE_Result_Ok,encoded.c_str());
    } catch(const std::exception& error) {
        const auto encoded=json{{"error",error.what()},{"gameplay_modified",false}}.dump();
        return host.reply(host.context,requestId,BE_Result_InvalidArgument,encoded.c_str());
    }
}
void BE_CALL shutdown() { std::lock_guard lock(mutex);initialized=false;host={}; }
const BE_ThirdPartyModuleV1 module{sizeof(module),1,kId,initialize,configure,message,shutdown};
}
BE_EXPORT const BE_ThirdPartyModuleV1* BE_CALL BetterEndfield_GetThirdPartyModuleV1() { return &module; }
