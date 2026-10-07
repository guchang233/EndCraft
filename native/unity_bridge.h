#pragma once
#include "BetterEndfield/ThirdPartyModule.h"
#include <windows.h>
#include <unordered_map>
#include <vector>
#include <string>
#include <stdexcept>
#include <cstring>

namespace endcraft::unity {
struct V2 {float x,y;};
struct V3 {float x,y,z;};
struct Quaternion {float x,y,z,w;};
struct Color {float r,g,b,a;};
struct Matrix {float values[16];};
struct Rect {float x,y,width,height;};
struct RenderTarget {int type,name,instance,pad;std::uintptr_t buffer;int mip,face,slice,pad2;};
class Api {
    const BE_HostApiV1* api=nullptr;
    std::unordered_map<std::string,BE_ResolvedMethodV1> methods;
    std::unordered_map<std::string,BE_ResolvedClassV1> classes;
public:
    std::vector<std::uint32_t> roots;
    struct TemporaryRoot {
        const BE_HostApiV1* api;
        std::uint32_t handle;
        TemporaryRoot(const BE_HostApiV1* a,void* object):api(a),handle(a->gchandle_new(a->context,object,0)) {
            if(!handle) throw std::runtime_error("temporary managed root allocation failed");
        }
        ~TemporaryRoot() {api->gchandle_free(api->context,handle);}
        TemporaryRoot(const TemporaryRoot&)=delete;
        TemporaryRoot& operator=(const TemporaryRoot&)=delete;
    };
    void bind(const BE_HostApiV1* value) {api=value;}
    const BE_HostApiV1* raw() const {return api;}
    BE_ResolvedClassV1 klass(const char* type,const char* assembly="UnityEngine.CoreModule.dll",const char* space="UnityEngine") {
        std::string key=std::string(assembly)+space+type;
        if(auto found=classes.find(key);found!=classes.end()) return found->second;
        BE_ResolvedClassV1 result{};
        if(api->resolve_class(api->context,assembly,space,type,&result)!=BE_Result_Ok||!result.class_info)
            throw std::runtime_error("class unavailable: "+key);
        classes.emplace(key,result);return result;
    }
    BE_ResolvedMethodV1 method(const char* type,const char* name,const char* params="",const char* returns="System.Void",
        const char* assembly="UnityEngine.CoreModule.dll",const char* space="UnityEngine") {
        std::string key=std::string(assembly)+space+type+name+params+returns;
        if(auto found=methods.find(key);found!=methods.end()) return found->second;
        unsigned count=params[0]?1:0;for(auto* p=params;*p;++p) if(*p=='|') ++count;
        BE_MethodDescriptorV1 descriptor{assembly,space,type,name,params[0]?params:nullptr,returns,count};
        BE_ResolvedMethodV1 result{};
        if(api->resolve_method(api->context,&descriptor,&result)!=BE_Result_Ok||!result.method_info)
            throw std::runtime_error("method unavailable: "+key);
        methods.emplace(key,result);return result;
    }
    void* call(BE_ResolvedMethodV1 method,void* object=nullptr,std::initializer_list<void*> args={}) {
        void* exception=nullptr;
        std::vector<void*> parameters(args);
        auto* result=api->runtime_invoke(api->context,method.method_info,object,parameters.empty()?nullptr:parameters.data(),&exception);
        if(exception) throw std::runtime_error("managed exception during bridge call");
        return result;
    }
    template<class T> T value(BE_ResolvedMethodV1 method,void* object=nullptr,std::initializer_list<void*> args={}) {
        auto* result=call(method,object,args);auto* raw=result?api->object_unbox(api->context,result):nullptr;
        if(!raw) throw std::runtime_error("missing boxed bridge result");T output;std::memcpy(&output,raw,sizeof(T));return output;
    }
    void* keep(void* object) {
        if(!object) throw std::runtime_error("missing Unity object");
        const auto handle=api->gchandle_new(api->context,object,0);
        if(!handle) throw std::runtime_error("managed root allocation failed");roots.push_back(handle);return object;
    }
    void* object(const char* type) {return keep(api->object_new(api->context,klass(type).class_info));}
    void* string(const std::string& value) {return api->string_new(api->context,value.c_str());}
    void* gameObject(const std::string& name) {
        auto* output=object("GameObject");call(method("GameObject",".ctor","System.String"),output,{string(name)});return output;
    }
    void* transform(void* object,bool component=false) {
        return call(method(component?"Component":"GameObject","get_transform","","UnityEngine.Transform"),object);
    }
    void* add(void* object,const char* type,const char* assembly="UnityEngine.CoreModule.dll",const char* space="UnityEngine") {
        auto klassValue=klass(type,assembly,space);
        return keep(call(method("GameObject","AddComponent","System.Type","UnityEngine.Component"),object,{klassValue.type_object}));
    }
    void setPosition(void* transform,V3 value) {call(method("Transform","set_position","UnityEngine.Vector3"),transform,{&value});}
    void setActive(void* object,bool value) {call(method("GameObject","SetActive","System.Boolean"),object,{&value});}
    void destroy(void* object) {if(object) call(method("Object","Destroy","UnityEngine.Object"),nullptr,{object});}
    void* array(const char* type,const void* bytes,std::size_t count,std::size_t stride,const char* assembly="UnityEngine.CoreModule.dll",const char* space="UnityEngine") {
        // Public IL2CPP allocation exports; standard Windows x64 IL2CPP array ABI.
        // Validate element size and allocated length before accessing the vector.
        const auto module=GetModuleHandleW(L"GameAssembly.dll");
        const auto allocate=reinterpret_cast<void*(*)(const void*,std::uintptr_t)>(GetProcAddress(module,"il2cpp_array_new"));
        const auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(module,"il2cpp_array_length"));
        const auto size=reinterpret_cast<int(*)(const void*,unsigned*)>(GetProcAddress(module,"il2cpp_class_value_size"));
        if(!allocate||!length||!size) throw std::runtime_error("IL2CPP array exports unavailable");
        auto k=klass(type,assembly,space);unsigned align=0;
        if(size(k.class_info,&align)!=stride) throw std::runtime_error("IL2CPP element ABI mismatch");
        auto* result=allocate(k.class_info,count);
        if(!result||length(result)!=count) throw std::runtime_error("IL2CPP array allocation failed");
        struct ArrayPrefix {void* klass;void* monitor;void* bounds;std::uintptr_t length;};
        static_assert(sizeof(ArrayPrefix)==32);
        if(count) {
            if(!bytes) throw std::runtime_error("array source missing");
            std::memcpy(static_cast<unsigned char*>(result)+sizeof(ArrayPrefix),bytes,count*stride);
        }
        return result;
    }
    void* texture(unsigned width,unsigned height,const void* bytes) {
        if(!width||!height||width>8192||height>8192) throw std::runtime_error("texture dimensions invalid");
        auto* result=object("Texture2D");int w=int(width),h=int(height),format=4;bool mip=false;
        call(method("Texture2D",".ctor","System.Int32|System.Int32|UnityEngine.TextureFormat|System.Boolean"),result,{&w,&h,&format,&mip});
        int point=0,clamp=1;
        call(method("Texture","set_filterMode","UnityEngine.FilterMode"),result,{&point});
        call(method("Texture","set_wrapMode","UnityEngine.TextureWrapMode"),result,{&clamp});
        upload(result,width,height,bytes);return result;
    }
    void upload(void* texture,unsigned width,unsigned height,const void* bytes) {
        auto pointer=reinterpret_cast<std::uintptr_t>(bytes);int length=int(std::uint64_t(width)*height*4);bool update=false,discard=false;
        call(method("Texture2D","LoadRawTextureData","System.IntPtr|System.Int32"),texture,{&pointer,&length});
        call(method("Texture2D","Apply","System.Boolean|System.Boolean"),texture,{&update,&discard});
    }
};
}
