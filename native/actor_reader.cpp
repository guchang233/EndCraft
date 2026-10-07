#include "BetterEndfield/ThirdPartyModule.h"
#include "nlohmann/json.hpp"
#include "runtime_scope.h"
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#ifdef ENDCRAFT_CANVAS_PROBE
#include "canvas_probe.h"
#endif
#ifdef ENDCRAFT_VISUAL_PROBE
#include "unity_bridge.h"
#include <array>
#endif
#ifdef ENDCRAFT_ACTOR_TELEMETRY
#include "actor_publisher.h"
#endif
#ifdef ENDCRAFT_MOTION_PROBE
#include "motion_probe.h"
#endif
#ifdef ENDCRAFT_GAMEPLAY
#include "gameplay_bridge.h"
#include <thread>
#include <chrono>
#include <filesystem>
#endif

using nlohmann::json;
namespace {
#ifndef ENDCRAFT_ACTOR_MODULE_ID
#define ENDCRAFT_ACTOR_MODULE_ID "endcraft.actor"
#endif
constexpr char kId[]=ENDCRAFT_ACTOR_MODULE_ID;
BE_ThirdPartyHostV1 host{};
#ifdef ENDCRAFT_CANVAS_PROBE
endcraft::CanvasProbe canvasProbe;
#endif
#ifdef ENDCRAFT_ACTOR_TELEMETRY
endcraft::ActorPublisher publisher;
#endif
#ifdef ENDCRAFT_MOTION_PROBE
motion_probe::Probe motion;
#endif
#ifdef ENDCRAFT_GAMEPLAY
endcraft::GameplayBridge gameplay;
using RawData=UINT(WINAPI*)(HRAWINPUT,UINT,LPVOID,PUINT,UINT);
RawData nextRaw=nullptr;
using RawBuffer=UINT(WINAPI*)(PRAWINPUT,PUINT,UINT);
RawBuffer nextRawBuffer=nullptr;
void observeRaw(const RAWINPUT& packet) {
    DWORD foreground=0;GetWindowThreadProcessId(GetForegroundWindow(),&foreground);
    if(foreground==GetCurrentProcessId()&&packet.header.dwType==RIM_TYPEMOUSE&&!(packet.data.mouse.usFlags&MOUSE_MOVE_ABSOLUTE))
        gameplay.rawMouse(packet.data.mouse.lLastX,packet.data.mouse.lLastY);
}
UINT WINAPI onRaw(HRAWINPUT input,UINT command,LPVOID data,PUINT size,UINT headerSize) {
    UINT result=nextRaw?nextRaw(input,command,data,size,headerSize):UINT(-1);
    if(command==RID_INPUT&&data&&result!=UINT(-1)&&headerSize==sizeof(RAWINPUTHEADER)&&result>=offsetof(RAWINPUT,data)+sizeof(RAWMOUSE)) {
        auto& packet=*static_cast<RAWINPUT*>(data);
        if(packet.header.dwSize<=result) observeRaw(packet);
    }
    return result;
}
UINT WINAPI onRawBuffer(PRAWINPUT data,PUINT size,UINT headerSize) {
    const UINT capacity=size?*size:0;
    UINT result=nextRawBuffer?nextRawBuffer(data,size,headerSize):UINT(-1);
    if(data&&result!=UINT(-1)&&headerSize==sizeof(RAWINPUTHEADER)) {
        const auto* cursor=reinterpret_cast<const unsigned char*>(data);
        const auto* end=cursor+capacity;
        for(UINT i=0;i<result;++i) {
            if(std::size_t(end-cursor)<sizeof(RAWINPUTHEADER)) break;
            const auto& packet=*reinterpret_cast<const RAWINPUT*>(cursor);
            if(packet.header.dwSize<sizeof(RAWINPUTHEADER)||packet.header.dwSize>std::size_t(end-cursor)) break;
            if(packet.header.dwType==RIM_TYPEMOUSE&&packet.header.dwSize>=offsetof(RAWINPUT,data)+sizeof(RAWMOUSE)) observeRaw(packet);
            auto stride=(std::size_t(packet.header.dwSize)+sizeof(void*)-1)&~(sizeof(void*)-1);
            if(stride>std::size_t(end-cursor)) break;
            cursor+=stride;
        }
    }
    return result;
}
std::jthread autoWorker;
using PipelineTick=void(__fastcall*)(void*,void*,std::uintptr_t,void*,void*);
PipelineTick nextPipeline=nullptr;
BE_ResolvedMethodV1 pipelineTick{},unityMainCamera{};
BE_ResolvedFieldV1 requestCamera{},hgUnityCamera{};
void __fastcall onPipeline(void* instance,void* request,std::uintptr_t context,void* command,void* method) {
    if(nextPipeline) nextPipeline(instance,request,context,command,method);
    if(!request||(method&&method!=pipelineTick.method_info)) return;
    auto* runtime=host.get_runtime?host.get_runtime(host.context):host.runtime;
    if(!runtime) return;
    // The by-ref value type has no boxed object header. Its field offset is
    // validated before this hook is installed; the actual request stays borrowed.
    void* hgCamera=nullptr;
    std::memcpy(&hgCamera,static_cast<unsigned char*>(request)+requestCamera.offset-16,sizeof(hgCamera));
    if(!hgCamera) return;
    auto* camera=runtime->field_get_value_object(runtime->context,hgUnityCamera.field_info,hgCamera);
    void* exception=nullptr;
    auto* main=runtime->runtime_invoke(runtime->context,unityMainCamera.method_info,nullptr,nullptr,&exception);
    if(!exception&&camera&&camera==main) gameplay.renderFrame(camera);
}
#endif
const BE_HostApiV1* api=nullptr;
const void* cameraClass=nullptr;
using Tick=void(__fastcall*)(void*,float,void*);
Tick next=nullptr;
using ObjectClass=const void*(*)(void*);
using ClassParent=const void*(*)(const void*);
ObjectClass objectClass=nullptr;ClassParent parent=nullptr;
BE_ResolvedMethodV1 tick{},main{},position{},movement{},alive{},cinematic{},velocity{},height{},radius{};
std::atomic<bool> enabled=false;
std::mutex stateMutex,lifecycle;
bool initialized=false;
json latest={{"valid_player",false},{"gameplay_modified",false}};
std::uint64_t samples=0;
const BE_HostApiV1* runtime() {return host.get_runtime?host.get_runtime(host.context):host.runtime;}
void* invoke(const BE_ResolvedMethodV1& method,void* target) {
    void* exception=nullptr;auto* value=api->runtime_invoke(api->context,method.method_info,target,nullptr,&exception);
    return exception?nullptr:value;
}
template<class T> bool value(const BE_ResolvedMethodV1& method,void* target,T& result) {
    auto* boxed=invoke(method,target);auto* raw=boxed?api->object_unbox(api->context,boxed):nullptr;
    if(!raw) return false;std::memcpy(&result,raw,sizeof(T));return true;
}
struct Vec3 {float x,y,z;};
bool finite(Vec3 vector) {return std::isfinite(vector.x)&&std::isfinite(vector.y)&&std::isfinite(vector.z);}
bool cameraInstance(void* instance) {
    __try {
        auto* klass=objectClass(instance);
        for(unsigned depth=0;klass&&depth<16;++depth,klass=parent(klass)) if(klass==cameraClass) return true;
    } __except(EXCEPTION_EXECUTE_HANDLER) {return false;}
    return false;
}
void __fastcall onTick(void* instance,float delta,void* info) {
    if(next) next(instance,delta,info);
    if(!enabled.load(std::memory_order_acquire)||!instance) return;
    // Native code folding can share an entry. Only sample CameraManager instances.
    if(info&&info!=tick.method_info) return;
    if(!cameraInstance(instance)) return;
    auto* character=invoke(main,nullptr);
    json result={{"host_pid",GetCurrentProcessId()},{"thread_id",GetCurrentThreadId()},
                 {"sample_ms",GetTickCount64()},{"valid_player",false},{"gameplay_modified",false}};
    if(character) {
        Vec3 where{},speed{};bool living=false,cutscene=false;float capsuleHeight=0,capsuleRadius=0;
        auto* component=invoke(movement,character);
        const bool valid=value(position,character,where)&&value(alive,character,living)&&value(cinematic,character,cutscene)&&
            component&&value(velocity,component,speed)&&value(height,component,capsuleHeight)&&value(radius,component,capsuleRadius)&&
            finite(where)&&finite(speed)&&std::isfinite(capsuleHeight)&&capsuleHeight>0&&
            std::isfinite(capsuleRadius)&&capsuleRadius>0;
        result.update({{"valid_player",valid},{"alive",living},{"in_cinematic",cutscene},
            {"position_raw_units",{where.x,where.y,where.z}},{"velocity_raw_units_per_second",{speed.x,speed.y,speed.z}},
            {"capsule_height_raw_units",capsuleHeight},{"capsule_radius_raw_units",capsuleRadius}});
#ifdef ENDCRAFT_VISUAL_PROBE
        if(valid&&samples%60==0) {
            try {
                endcraft::unity::Api readApi;readApi.bind(api);
                auto* root=readApi.call(readApi.method("Entity","get_rootCom","","Beyond.Gameplay.Core.RootComponent","Gameplay.Beyond.dll","Beyond.Gameplay.Core"),character);
                auto* object=readApi.call(readApi.method("RootComponent","get_gameObject","","UnityEngine.GameObject","Gameplay.Beyond.dll","Beyond.Gameplay.Core"),root);
                bool include=true;
                auto* list=readApi.call(readApi.method("GameObject","GetComponentsInChildren","System.Type|System.Boolean","UnityEngine.Component[]"),object,{readApi.klass("Renderer").type_object,&include});
                const auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_array_length"));
                const auto stringLength=reinterpret_cast<int(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_string_length"));
                const auto stringChars=reinterpret_cast<const wchar_t*(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_string_chars"));
                auto text=[&](void* value) {if(!value||!stringLength||!stringChars) return std::string{};std::wstring wide(stringChars(value),stringLength(value));int n=WideCharToMultiByte(CP_UTF8,0,wide.data(),int(wide.size()),nullptr,0,nullptr,nullptr);std::string output(n,0);WideCharToMultiByte(CP_UTF8,0,wide.data(),int(wide.size()),output.data(),n,nullptr,nullptr);return output;};
                json materials=json::array();
                auto count=list&&length?length(list):0;
                for(std::uintptr_t i=0;i<count&&i<100;++i) {
                    void* renderer=nullptr;std::memcpy(&renderer,static_cast<unsigned char*>(list)+32+i*8,8);
                    if(!renderer) continue;
                    auto* mat=readApi.call(readApi.method("Renderer","get_sharedMaterial","","UnityEngine.Material"),renderer);if(!mat) continue;
                    auto* shader=readApi.call(readApi.method("Material","get_shader","","UnityEngine.Shader"),mat);
                    auto name=text(readApi.call(readApi.method("Object","get_name","","System.String"),shader));
                    if(materials.size()<12&&std::find(materials.begin(),materials.end(),name)==materials.end()) materials.push_back(name);
                }
                alignas(16) unsigned char hit[512]{};endcraft::unity::V3 from{where.x,where.y+4,where.z},down{0,-1,0};float range=80;bool flag=false;
                auto ray=readApi.method("MovementComponent","CharacterCollisionRaycast","UnityEngine.Vector3|UnityEngine.Vector3|System.Single|UnityEngine.RaycastHit&|System.Boolean","System.Int32","Gameplay.Beyond.dll","Beyond.Gameplay.Core");
                auto code=readApi.value<int>(ray,component,{&from,&down,&range,hit,&flag});
                endcraft::unity::V3 point{},normal{};float hitDistance=0;
                std::memcpy(&point,hit,12);std::memcpy(&normal,hit+12,12);std::memcpy(&hitDistance,hit+28,4);
                result["visual_probe"]={{"host_shaders",materials},{"character_ray_result",code},{"point",{point.x,point.y,point.z}},{"normal",{normal.x,normal.y,normal.z}},{"distance",hitDistance}};
#ifdef ENDCRAFT_TARGETS_PROBE
                auto* cam=readApi.call(readApi.method("Camera","get_main","","UnityEngine.Camera"));int cameraId=0;
                auto* hg=readApi.call(readApi.method("HGCamera","TryGet","UnityEngine.Camera|System.Int32","HG.Rendering.Runtime.HGCamera","HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime"),nullptr,{cam,&cameraId});
                json targets={{"hg_camera_found",hg!=nullptr}};
                if(hg) {
                    auto dimensions=readApi.value<std::array<int,2>>(readApi.method("HGCamera","get_sceneRTSize","","UnityEngine.Vector2Int","HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime"),hg);
                    targets["scene_size"]=dimensions;
                    auto* path=readApi.call(readApi.method("HGCamera","get_renderPathInstance","","HG.Rendering.Runtime.HGRenderPathBase","HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime"),hg);
                    targets["managed_path_found"]=path!=nullptr;
                    if(path) for(const char* getter:{"get_backBufferColor","get_backBufferDepth"}) {
                        try {
                            auto handle=readApi.value<std::array<std::uint64_t,2>>(readApi.method("HGRenderPathBase",getter,"","HG.Rendering.RenderGraphModule.TextureHandle","HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime"),path);
                            auto* texture=readApi.call(readApi.method("TextureHandle","op_Implicit","HG.Rendering.RenderGraphModule.TextureHandle","UnityEngine.RenderTexture","HG.RenderPipelines.Runtime.dll","HG.Rendering.RenderGraphModule"),nullptr,{&handle});
                            targets[getter]={{"handle",handle},{"texture",texture!=nullptr}};
                            if(texture) targets[getter]["name"]=text(readApi.call(readApi.method("Object","get_name","","System.String"),texture));
                        } catch(const std::exception& e) {targets[getter]={{"error",e.what()}};}
                    }
                    for(const char* name:{"_SceneDepthTexture","_SceneColorTexture","_CameraDepthTexture"}) {
                        int id=readApi.value<int>(readApi.method("Shader","PropertyToID","System.String","System.Int32"),nullptr,{readApi.string(name)});
                        auto* handle=readApi.call(readApi.method("HGCamera","GetCurrentFrameRT","System.Int32","UnityEngine.Rendering.RTHandle","HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime"),hg,{&id});
                        targets[name]={{"history_found",handle!=nullptr},{"shader_id",id}};
                    }
                    // The managed getter is stripped in this player. Ask the
                    // public IL2CPP internal-call registry for its Unity binding.
                    auto resolve=reinterpret_cast<void*(*)(const char*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_resolve_icall"));
                    auto getTexture=resolve?reinterpret_cast<void*(*)(int)>(resolve("UnityEngine.Shader::GetGlobalTextureImpl(System.Int32)")):nullptr;
                    targets["global_texture_getter_available"]=getTexture!=nullptr;
                    if(getTexture) for(const char* name:{"_SceneDepthTexture","_SceneColorTexture","_CameraDepthTexture","_SceneDepth"}) {
                        int id=readApi.value<int>(readApi.method("Shader","PropertyToID","System.String","System.Int32"),nullptr,{readApi.string(name)});
                        auto* texture=getTexture(id);
                        targets[name]["global_texture_found"]=texture!=nullptr;
                        if(texture) {
                            targets[name]["name"]=text(readApi.call(readApi.method("Object","get_name","","System.String"),texture));
                            targets[name]["width"]=readApi.value<int>(readApi.method("Texture","get_width","","System.Int32"),texture);
                            targets[name]["height"]=readApi.value<int>(readApi.method("Texture","get_height","","System.Int32"),texture);
                        }
                    }
                }
                result["visual_probe"]["render_targets"]=targets;
#endif
            } catch(const std::exception& e) {result["visual_probe"]={{"error",e.what()}};}
        } else {
            std::lock_guard previous(stateMutex);
            if(latest.contains("visual_probe")) result["visual_probe"]=latest["visual_probe"];
        }
#endif
#ifdef ENDCRAFT_MOTION_PROBE
        motion.tick(character,component,{where.x,where.y,where.z},{speed.x,speed.y,speed.z},valid,living,cutscene);
#endif
#ifdef ENDCRAFT_GAMEPLAY
        gameplay.tick(api,character,component,{where.x,where.y,where.z},valid,living,cutscene);
#endif
    }
#ifdef ENDCRAFT_MOTION_PROBE
    else motion.tick(nullptr,nullptr,{},{},false,false,false);
#endif
#ifdef ENDCRAFT_GAMEPLAY
    else gameplay.tick(api,nullptr,nullptr,{},false,false,false);
#endif
#ifdef ENDCRAFT_CANVAS_PROBE
    canvasProbe.tick(api);
#endif
    std::lock_guard lock(stateMutex);result["samples"]=++samples;
#ifdef ENDCRAFT_ACTOR_TELEMETRY
    if(enabled.load(std::memory_order_acquire)) {
        endcraft::ActorProbeState state{};state.sampleMs=GetTickCount64();state.samples=samples;state.threadId=GetCurrentThreadId();
        if(result["valid_player"].get<bool>()) {
            state.flags=endcraft::ActorValid;
            if(result["alive"].get<bool>()) state.flags|=endcraft::ActorAlive;
            if(result["in_cinematic"].get<bool>()) state.flags|=endcraft::ActorCinematic;
            for(unsigned i=0;i<3;++i) {
                state.position[i]=result["position_raw_units"][i].get<float>();
                state.velocity[i]=result["velocity_raw_units_per_second"][i].get<float>();
            }
            state.capsuleHeight=result["capsule_height_raw_units"].get<float>();
            state.capsuleRadius=result["capsule_radius_raw_units"].get<float>();
        }
        result["shared_memory_published"]=publisher.publish(state);
    }
#endif
    latest=std::move(result);
}
BE_Result start() {
    if(enabled.load()) return BE_Result_Ok;
    api=runtime();
    if(!api||api->abi_version!=1||!api->resolve_method||!api->resolve_class||!api->runtime_invoke||!api->object_unbox||
       !host.hooks||host.hooks->version!=1||!host.hooks->create||!host.hooks->disable_module) return BE_Result_NotReady;
    RuntimeThreadScope scope(true);if(!scope.ready()) return BE_Result_NotReady;
    const BE_MethodDescriptorV1 descriptors[]={
        {"Gameplay.Beyond.dll","Beyond.Gameplay.View","CameraManager","TailLateTick","System.Single","System.Void",1},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","PlayerController","GetMainCharacter",nullptr,"Beyond.Gameplay.Core.Entity",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_position",nullptr,"UnityEngine.Vector3",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_movementComponent",nullptr,"Beyond.Gameplay.Core.MovementComponent",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_alive",nullptr,"System.Boolean",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","Entity","get_inCinematic",nullptr,"System.Boolean",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","get_velocity",nullptr,"UnityEngine.Vector3",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","GetCapsuleHeight",nullptr,"System.Single",0},
        {"Gameplay.Beyond.dll","Beyond.Gameplay.Core","MovementComponent","GetCapsuleRadius",nullptr,"System.Single",0},
    };
    BE_ResolvedMethodV1* results[]={&tick,&main,&position,&movement,&alive,&cinematic,&velocity,&height,&radius};
    for(unsigned i=0;i<9;++i) {
        auto status=api->resolve_method(api->context,&descriptors[i],results[i]);
        if(status!=BE_Result_Ok||!results[i]->method_info||!results[i]->method_pointer) return BE_Result_NotFound;
    }
    BE_ResolvedClassV1 klass{};
    if(api->resolve_class(api->context,"Gameplay.Beyond.dll","Beyond.Gameplay.View","CameraManager",&klass)!=BE_Result_Ok||!klass.class_info)
        return BE_Result_NotFound;
    cameraClass=klass.class_info;
    const auto assembly=GetModuleHandleW(L"GameAssembly.dll");
    objectClass=reinterpret_cast<ObjectClass>(GetProcAddress(assembly,"il2cpp_object_get_class"));
    parent=reinterpret_cast<ClassParent>(GetProcAddress(assembly,"il2cpp_class_get_parent"));
    if(!objectClass||!parent) return BE_Result_NotReady;
    std::uint64_t handle=0;
    const auto result=host.hooks->create(host.hooks->context,kId,tick.method_pointer,reinterpret_cast<void*>(onTick),reinterpret_cast<void**>(&next),&handle);
#ifdef ENDCRAFT_GAMEPLAY
    if(result==BE_Result_Ok) {
        BE_MethodDescriptorV1 pipeline{"HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime","HGRenderPipeline","ExecuteRenderRequestCPP",
            "HG.Rendering.Runtime.HGRenderPipeline.RenderRequest&|UnityEngine.Rendering.ScriptableRenderContext|UnityEngine.Rendering.CommandBuffer","System.Void",3};
        BE_MethodDescriptorV1 camera{"UnityEngine.CoreModule.dll","UnityEngine","Camera","get_main",nullptr,"UnityEngine.Camera",0};
        BE_FieldDescriptorV1 hgField{"HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime","HGRenderPipeline.RenderRequest","hgCamera","HG.Rendering.Runtime.HGCamera"};
        BE_FieldDescriptorV1 unityField{"HG.RenderPipelines.Runtime.dll","HG.Rendering.Runtime","HGCamera","camera","UnityEngine.Camera"};
        BE_ResolvedClassV1 contextClass{};
        auto size=reinterpret_cast<int(*)(const void*,unsigned*)>(GetProcAddress(assembly,"il2cpp_class_value_size"));unsigned alignment=0;
        if(api->resolve_class(api->context,"UnityEngine.CoreModule.dll","UnityEngine.Rendering","ScriptableRenderContext",&contextClass)!=BE_Result_Ok||!size||size(contextClass.class_info,&alignment)!=8||
            api->resolve_method(api->context,&pipeline,&pipelineTick)!=BE_Result_Ok||api->resolve_method(api->context,&camera,&unityMainCamera)!=BE_Result_Ok||
            api->resolve_field(api->context,&hgField,&requestCamera)!=BE_Result_Ok||requestCamera.offset!=16||
            api->resolve_field(api->context,&unityField,&hgUnityCamera)!=BE_Result_Ok) {
            host.hooks->disable_module(host.hooks->context,kId);return BE_Result_ContractMismatch;
        }
        const auto pipelineResult=host.hooks->create(host.hooks->context,kId,pipelineTick.method_pointer,reinterpret_cast<void*>(onPipeline),reinterpret_cast<void**>(&nextPipeline),&handle);
        if(pipelineResult!=BE_Result_Ok) {host.hooks->disable_module(host.hooks->context,kId);return pipelineResult;}
        auto raw=GetProcAddress(GetModuleHandleW(L"user32.dll"),"GetRawInputData");
        auto rawResult=raw?host.hooks->create(host.hooks->context,kId,reinterpret_cast<void*>(raw),reinterpret_cast<void*>(onRaw),reinterpret_cast<void**>(&nextRaw),&handle):BE_Result_NotFound;
        auto rawBuffer=GetProcAddress(GetModuleHandleW(L"user32.dll"),"GetRawInputBuffer");
        auto bufferResult=rawBuffer?host.hooks->create(host.hooks->context,kId,reinterpret_cast<void*>(rawBuffer),reinterpret_cast<void*>(onRawBuffer),reinterpret_cast<void**>(&nextRawBuffer),&handle):BE_Result_NotFound;
        gameplay.rawInputReady(rawResult==BE_Result_Ok||bufferResult==BE_Result_Ok);
    }
#endif
    if(result==BE_Result_Ok) enabled.store(true,std::memory_order_release);
    return result;
}
BE_Result BE_CALL initialize(const BE_ThirdPartyHostV1* input,const char* config) {
    std::lock_guard lock(lifecycle);
    if(!input||input->version!=1||input->struct_size<offsetof(BE_ThirdPartyHostV1,emit)+sizeof(input->emit)||
       !input->module_id||std::string(input->module_id)!=kId||!input->platform||std::string(input->platform)!="windows-x64"||!input->reply)
        return BE_Result_ContractMismatch;
    if(initialized) return BE_Result_Conflict;
    try {
        if(!json::parse(config?config:"{}").is_object()) return BE_Result_InvalidArgument;
        host={};std::memcpy(&host,input,(std::min)(std::size_t(input->struct_size),sizeof(host)));initialized=true;
#ifdef ENDCRAFT_GAMEPLAY
        if(json::parse(config?config:"{}").value("auto_enable",false)) {
            // Enable once; explicit disable and genuine faults must remain disabled.
            // Ordinary scene changes are handled on the game thread by GameplayBridge.
            const auto guestLauncher=json::parse(config?config:"{}").value("guest_launcher",std::string{});
            autoWorker=std::jthread([guestLauncher](std::stop_token stop) {
                if(!guestLauncher.empty()) {
                    auto script=std::filesystem::path(guestLauncher);
                    if(script.is_absolute()&&script.filename()==L"start-guest.ps1"&&std::filesystem::is_regular_file(script)) {
                        wchar_t directory[MAX_PATH]{};GetSystemDirectoryW(directory,MAX_PATH);
                        auto executable=std::filesystem::path(directory)/L"WindowsPowerShell/v1.0/powershell.exe";
                        auto command=L"\""+executable.wstring()+L"\" -NoProfile -File \""+script.wstring()+L"\"";
                        STARTUPINFOW startup{};startup.cb=sizeof(startup);PROCESS_INFORMATION process{};
                        if(CreateProcessW(executable.c_str(),command.data(),nullptr,nullptr,FALSE,CREATE_NO_WINDOW,nullptr,script.parent_path().c_str(),&startup,&process)) {
                            CloseHandle(process.hThread);CloseHandle(process.hProcess);
                        } else if(host.log) host.log(host.context,"EndCraft: guest launcher could not start");
                    }
                }
                while(!stop.stop_requested()) {
                    {
                        std::lock_guard workerLock(lifecycle);
                        if(initialized&&start()==BE_Result_Ok) {
                            gameplay.enable(true);return;
                        }
                    }
                    std::this_thread::sleep_for(std::chrono::seconds(2));
                }
            });
        }
#endif
        return BE_Result_Ok;
    } catch(...) {return BE_Result_InvalidArgument;}
}
BE_Result BE_CALL configure(const char* config) {
    std::lock_guard lock(lifecycle);
    if(!initialized) return BE_Result_NotReady;
    try {return json::parse(config?config:"{}").is_object()?BE_Result_Ok:BE_Result_InvalidArgument;}
    catch(...) {return BE_Result_InvalidArgument;}
}
BE_Result BE_CALL message(const char* request,const char* body) {
    std::lock_guard lock(lifecycle);
    if(!initialized) return BE_Result_NotReady;
    if(!request||!body) return BE_Result_InvalidArgument;
    try {
        const auto action=json::parse(body).at("action").get<std::string>();
#ifdef ENDCRAFT_CANVAS_PROBE
        if(action=="enable"||action=="disable") {
            auto result=action=="enable"?start():BE_Result_Ok;
            if(result==BE_Result_Ok) canvasProbe.enable(action=="enable");
            const auto encoded=json{{"result",int(result)},{"canvas_probe",canvasProbe.snapshot()}}.dump();
            return host.reply(host.context,request,result,encoded.c_str());
        }
#endif
#ifdef ENDCRAFT_GAMEPLAY
        if(action=="recover") {gameplay.recover();return host.reply(host.context,request,BE_Result_Ok,"{\"recovery_queued\":true}");}
        if(action=="renderer_probe") {gameplay.rendererProbe();return host.reply(host.context,request,BE_Result_Ok,"{\"probe_queued\":true}");}
        if(action=="native_rendering") {gameplay.nativeRendering(json::parse(body).at("enabled").get<bool>());return host.reply(host.context,request,BE_Result_Ok,"{\"render_mode_queued\":true}");}
        if(action=="look") {
            const auto input=json::parse(body);gameplay.look(input.at("yaw").get<float>(),input.at("pitch").get<float>());
            return host.reply(host.context,request,BE_Result_Ok,"{\"look_queued\":true}");
        }
        if(action=="shader") {
            auto name=json::parse(body).at("name").get<std::string>();
            if(name.empty()||name.size()>256) return BE_Result_InvalidArgument;
            gameplay.shader(name);
            return host.reply(host.context,request,BE_Result_Ok,"{\"shader_queued\":true}");
        }
        if(action=="capture") {
            gameplay.capture();
            return host.reply(host.context,request,BE_Result_Ok,"{\"capture_queued\":true}");
        }
        if(action=="enable"||action=="disable") {
            auto result=action=="enable"?start():BE_Result_Ok;
            const auto input=json::parse(body);
            if(action=="enable"&&input.contains("initial_host_anchor")) {
                auto anchor=input.at("initial_host_anchor").get<std::vector<float>>();
                auto pose=input.at("initial_guest_position").get<std::vector<float>>();
                if(anchor.size()!=3||pose.size()!=3) return BE_Result_InvalidArgument;
                gameplay.initialAnchor({anchor[0],anchor[1],anchor[2]},{pose[0],pose[1],pose[2]});
            }
            if(result==BE_Result_Ok) gameplay.enable(action=="enable");
            const auto encoded=json{{"result",int(result)},{"gameplay",gameplay.snapshot()}}.dump();
            return host.reply(host.context,request,result,encoded.c_str());
        }
#endif
#ifdef ENDCRAFT_MOTION_PROBE
        if(action=="nudge") {
            const auto input=json::parse(body);const auto delta=input.at("delta_raw_units").get<std::vector<float>>();
            if(delta.size()!=3) return host.reply(host.context,request,BE_Result_InvalidArgument,"{\"error\":\"three_component_delta_required\"}");
            auto result=start();if(result==BE_Result_Ok) result=motion.queue(api,{delta[0],delta[1],delta[2]});
            const auto encoded=json{{"result",int(result)},{"motion_test",motion.snapshot()}}.dump();
            return host.reply(host.context,request,result,encoded.c_str());
        }
#endif
        if(action=="observe") {
            const auto result=start();if(result!=BE_Result_Ok) {
                const auto encoded=json{{"error","actor_observation_not_started"},{"result",int(result)}}.dump();
                return host.reply(host.context,request,result,encoded.c_str());
            }
        } else if(action!="status") return host.reply(host.context,request,BE_Result_InvalidArgument,"{\"error\":\"status_or_observe_required\"}");
        std::lock_guard stateLock(stateMutex);auto state=latest;state["enabled"]=enabled.load();
#ifdef ENDCRAFT_CANVAS_PROBE
        state["canvas_probe"]=canvasProbe.snapshot();
        state["gameplay_modified"]=state["canvas_probe"]["visible"];
#endif
#ifdef ENDCRAFT_MOTION_PROBE
        state["motion_test"]=motion.snapshot();
        state["gameplay_write_invoked"]=state["motion_test"].value("host_method_invoked",false);
        state["gameplay_modified"]=state["motion_test"].value("observed_position_changed",false);
#endif
#ifdef ENDCRAFT_GAMEPLAY
        state["gameplay"]=gameplay.snapshot();
        state["gameplay_modified"]=state["gameplay"]["host_moves"].get<std::uint64_t>()>0;
#endif
        const auto now=GetTickCount64();state["sample_age_ms"]=state.contains("sample_ms")?json(now-state["sample_ms"].get<std::uint64_t>()):json(nullptr);
        state["module"]=kId;const auto encoded=state.dump();return host.reply(host.context,request,BE_Result_Ok,encoded.c_str());
    } catch(...) {return host.reply(host.context,request,BE_Result_InvalidArgument,"{\"error\":\"invalid_request\"}");}
}
void BE_CALL shutdown() {
#ifdef ENDCRAFT_CANVAS_PROBE
    canvasProbe.enable(false);
#endif
#ifdef ENDCRAFT_GAMEPLAY
    autoWorker.request_stop();if(autoWorker.joinable()) autoWorker.join();gameplay.enable(false);
#endif
    std::lock_guard lock(lifecycle);if(!initialized) return;
    enabled.store(false,std::memory_order_release);
    if(host.hooks&&host.hooks->disable_module) host.hooks->disable_module(host.hooks->context,kId);
    std::lock_guard stateLock(stateMutex);latest["valid_player"]=false;
#ifdef ENDCRAFT_ACTOR_TELEMETRY
    endcraft::ActorProbeState invalid{};invalid.sampleMs=GetTickCount64();publisher.publish(invalid);publisher.close();
#endif
    host={};initialized=false;
}
const BE_ThirdPartyModuleV1 module{sizeof(module),1,kId,initialize,configure,message,shutdown};
}
BE_EXPORT const BE_ThirdPartyModuleV1* BE_CALL BetterEndfield_GetThirdPartyModuleV1() {return &module;}
