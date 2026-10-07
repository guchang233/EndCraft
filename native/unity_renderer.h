#pragma once
#include "unity_bridge.h"
#include "bridge_memory.h"
#include "coordinate_map.h"
#include "alpha_mesh.h"
#include "nlohmann/json.hpp"
#include <map>
#include <array>
#include <cmath>

namespace endcraft {
class UnityRenderer {
    struct MeshObject {void* object=nullptr;void* mesh=nullptr;void* filter=nullptr;void* renderer=nullptr;void* collider=nullptr;void* material=nullptr;bool drawn=false;};
    unity::Api api;
    void* canvas=nullptr;void* rawImage=nullptr;void* hudTexture=nullptr;void* shader=nullptr;
    void* hudMaterial=nullptr;
    void* avatarShader=nullptr;
    void* worldTarget=nullptr;
    int targetWidth=0,targetHeight=0;
    std::map<unsigned,alpha::Texture> alphaTextures;
    unsigned hudWidth=0,hudHeight=0,front=0;
    std::map<unsigned,void*> textures,materials;
    std::map<std::array<int,3>,MeshObject> sections;
    std::vector<MeshObject> avatar;
    std::vector<unsigned char> pixels;
    unity::V3 hostOrigin{},mcOrigin{.5f,64,.5f};
    int worldLayer=0,cameraMask=0;
    bool nativeAttempted=false;
    template<class T> static T read(const std::vector<unsigned char>& payload,std::size_t offset=0) {
        if(offset>payload.size()||sizeof(T)>payload.size()-offset) throw std::runtime_error("truncated mesh payload");
        T value;std::memcpy(&value,payload.data()+offset,sizeof(value));return value;
    }
    void* material(unsigned texture) {
        auto found=textures.find(texture);if(found==textures.end()) return nullptr;
        if(auto cached=materials.find(texture);cached!=materials.end()) return cached->second;
        if(!shader) {
            for(const char* candidate:{"Unlit/Texture","Sprites/Default","UI/Default"}) {
                shader=api.call(api.method("Shader","Find","System.String","UnityEngine.Shader"),nullptr,{api.string(candidate)});
                if(shader) {shaderName=candidate;shaderSupported=api.value<bool>(api.method("Shader","get_isSupported","","System.Boolean"),shader);break;}
            }
            if(!shader) throw std::runtime_error("no usable built-in world shader");
        }
        auto* chosen=shader;
        if(nativeRendering) chosen=api.call(api.method("Shader","Find","System.String","UnityEngine.Shader"),nullptr,{api.string("Unlit/Texture")});
        auto* result=api.object("Material");api.call(api.method("Material",".ctor","UnityEngine.Shader"),result,{chosen});
        bindTexture(result,found->second);
        if(shaderName=="Unlit/Transparent Cutout") {
            float cutoff=.1f;api.call(api.method("Material","SetFloat","System.String|System.Single"),result,{api.string("_Cutoff"),&cutoff});
        }
        materials[texture]=result;return result;
    }
    void bindTexture(void* mat,void* texture) {
        api.call(api.method("Material","set_mainTexture","UnityEngine.Texture"),mat,{texture});
        for(const char* property:{"_BaseColorMap","_BaseMap"}) if(api.value<bool>(api.method("Material","HasProperty","System.String","System.Boolean"),mat,{api.string(property)}))
            api.call(api.method("Material","SetTexture","System.String|UnityEngine.Texture"),mat,{api.string(property),texture});
        unity::Color white{1,1,1,1};for(const char* property:{"_BaseColor","_Color"}) if(api.value<bool>(api.method("Material","HasProperty","System.String","System.Boolean"),mat,{api.string(property)}))
            api.call(api.method("Material","SetColor","System.String|UnityEngine.Color"),mat,{api.string(property),&white});
    }
    MeshObject createMesh(const char* name,bool collide) {
        MeshObject result;result.object=api.gameObject(name);result.mesh=api.object("Mesh");
        api.call(api.method("GameObject","set_layer","System.Int32"),result.object,{&worldLayer});
        api.call(api.method("Mesh",".ctor"),result.mesh);
        api.call(api.method("Mesh","MarkDynamic"),result.mesh);
        int indexFormat=1;api.call(api.method("Mesh","set_indexFormat","UnityEngine.Rendering.IndexFormat"),result.mesh,{&indexFormat});
        result.filter=api.add(result.object,"MeshFilter");
        api.call(api.method("MeshFilter","set_sharedMesh","UnityEngine.Mesh"),result.filter,{result.mesh});
        result.renderer=api.add(result.object,"MeshRenderer");
        if(collide) result.collider=api.add(result.object,"MeshCollider","UnityEngine.PhysicsModule.dll");
        return result;
    }
    void updateMesh(MeshObject& object,const proto::RenVertex* vertices,std::size_t count,unsigned texture,unity::V3 origin,bool clipAlpha=false) {
        if(count>200000||count%3) throw std::runtime_error("mesh vertex count invalid");
        auto* mat=material(texture);if(!mat) {api.setActive(object.object,false);return;}
        api.call(api.method("MeshFilter","set_sharedMesh","UnityEngine.Mesh"),object.filter,{nullptr});
        std::vector<proto::RenVertex> clipped;
        if(auto tex=alphaTextures.find(texture);tex!=alphaTextures.end()) {
            for(std::size_t i=0;i<count;i+=3) {
                if(clipAlpha||(vertices[i].flags&1)) alpha::triangle(tex->second,vertices+i,clipped);
                else clipped.insert(clipped.end(),vertices+i,vertices+i+3);
            }
            if(clipped.size()>200000) throw std::runtime_error("alpha-clipped mesh exceeds vertex budget");
            vertices=clipped.data();count=clipped.size();
        }
        std::vector<unity::V3> positions(count);std::vector<unity::V2> uv(count);std::vector<unsigned> colors(count);std::vector<int> indices(count);
        for(std::size_t i=0;i<count;++i) {
            const auto& v=vertices[i];
            if(!std::isfinite(v.x)||!std::isfinite(v.y)||!std::isfinite(v.z)) throw std::runtime_error("nonfinite mesh vertex");
            positions[i]=coordinates::reflect(unity::V3{v.x,v.y,v.z});uv[i]={v.u,v.v};colors[i]=v.color;
            // MC and Unity use opposite triangle winding conventions.
            indices[i]=int(i/3*3+(i%3==1?2:i%3==2?1:0));
        }
        auto* p=api.array("Vector3",positions.data(),count,sizeof(unity::V3));
        unity::Api::TemporaryRoot pr(api.raw(),p);
        auto* u=api.array("Vector2",uv.data(),count,sizeof(unity::V2));
        unity::Api::TemporaryRoot ur(api.raw(),u);
        auto* c=api.array("Color32",colors.data(),count,4);
        unity::Api::TemporaryRoot cr(api.raw(),c);
        auto* t=api.array("Int32",indices.data(),count,4,"mscorlib.dll","System");
        unity::Api::TemporaryRoot tr(api.raw(),t);
        api.call(api.method("Mesh","set_triangles","System.Int32[]"),object.mesh,{api.array("Int32",nullptr,0,4,"mscorlib.dll","System")});
        api.call(api.method("Mesh","set_vertices","UnityEngine.Vector3[]"),object.mesh,{p});
        api.call(api.method("Mesh","set_uv","UnityEngine.Vector2[]"),object.mesh,{u});
        api.call(api.method("Mesh","set_colors32","UnityEngine.Color32[]"),object.mesh,{c});
        api.call(api.method("Mesh","set_triangles","System.Int32[]"),object.mesh,{t});
        api.call(api.method("Mesh","RecalculateBounds"),object.mesh);
        bool discard=false;api.call(api.method("Mesh","UploadMeshData","System.Boolean"),object.mesh,{&discard});
        api.call(api.method("MeshFilter","set_sharedMesh","UnityEngine.Mesh"),object.filter,{object.mesh});
        api.call(api.method("Renderer","set_sharedMaterial","UnityEngine.Material"),object.renderer,{mat});
        if(nativeRendering) {api.call(api.method("Material","set_shader","UnityEngine.Shader"),mat,{shader});if(auto tex=textures.find(texture);tex!=textures.end()) bindTexture(mat,tex->second);}
        object.material=mat;object.drawn=count>0;
        api.setPosition(api.transform(object.object),coordinates::toHost(hostOrigin,mcOrigin,origin));
        if(object.collider) {
            api.call(api.method("MeshCollider","set_sharedMesh","UnityEngine.Mesh","System.Void","UnityEngine.PhysicsModule.dll"),object.collider,{nullptr});
            api.call(api.method("MeshCollider","set_sharedMesh","UnityEngine.Mesh","System.Void","UnityEngine.PhysicsModule.dll"),object.collider,{object.mesh});
        }
        api.setActive(object.object,count>0);
    }
    void newTexture(unsigned id,unsigned w,unsigned h,const void* bytes) {
        alpha::Texture alphaTexture{w,h,{}};
        alphaTexture.rgba.assign(static_cast<const unsigned char*>(bytes),static_cast<const unsigned char*>(bytes)+std::size_t(w)*h*4);
        alphaTextures[id]=std::move(alphaTexture);
        auto* texture=api.texture(w,h,bytes);
        if(auto old=textures.find(id);old!=textures.end()) api.destroy(old->second);
        textures[id]=texture;
        if(auto mat=materials.find(id);mat!=materials.end()) bindTexture(mat->second,texture);
    }
public:
    void prepareNative() {
        if(nativeAttempted||hudFrames<8||(!avatarFrames&&sections.empty())) return;
        nativeAttempted=true;
        try {configureShader("HGRP/Unlit");nativeRendering=shaderSupported;}
        catch(const std::exception& e) {probeResult["native_initialization_error"]=e.what();nativeRendering=false;}
    }
    nlohmann::json probeResult;
    bool nativeRendering=false;
    void diagnostics() {
        auto assembly=GetModuleHandleW(L"GameAssembly.dll");
        auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(assembly,"il2cpp_array_length"));
        auto chars=reinterpret_cast<const wchar_t*(*)(void*)>(GetProcAddress(assembly,"il2cpp_string_chars"));
        auto strLength=reinterpret_cast<int(*)(void*)>(GetProcAddress(assembly,"il2cpp_string_length"));
        if(!length||!chars||!strLength) throw std::runtime_error("diagnostic exports unavailable");
        auto name=[&](void* object) {
            auto* s=api.call(api.method("Object","get_name","","System.String"),object);
            if(!s) return std::string{};int n=strLength(s);if(n<0||n>1024) return std::string{};
            int bytes=WideCharToMultiByte(CP_UTF8,0,chars(s),n,nullptr,0,nullptr,nullptr);std::string r(bytes,'\0');
            WideCharToMultiByte(CP_UTF8,0,chars(s),n,r.data(),bytes,nullptr,nullptr);return r;
        };
        probeResult=nlohmann::json::object();probeResult["shaders"]=nlohmann::json::array();
        auto* all=api.call(api.method("Resources","FindObjectsOfTypeAll","System.Type","UnityEngine.Object[]"),nullptr,{api.klass("Shader").type_object});
        unity::Api::TemporaryRoot root(api.raw(),all);auto count=length(all);
        if(count>4096) throw std::runtime_error("shader inventory limit");
        auto** objects=reinterpret_cast<void**>(static_cast<unsigned char*>(all)+32);
        for(std::uintptr_t i=0;i<count;++i) probeResult["shaders"].push_back(name(objects[i]));
        all=api.call(api.method("Camera","get_allCameras","","UnityEngine.Camera[]"));
        unity::Api::TemporaryRoot camerasRoot(api.raw(),all);count=length(all);
        if(count>64) throw std::runtime_error("camera inventory limit");
        objects=reinterpret_cast<void**>(static_cast<unsigned char*>(all)+32);probeResult["cameras"]=nlohmann::json::array();
        for(std::uintptr_t i=0;i<count;++i) probeResult["cameras"].push_back({{"name",name(objects[i])},
            {"id",api.value<int>(api.method("Object","GetInstanceID","","System.Int32"),objects[i])},
            {"mask",api.value<int>(api.method("Camera","get_cullingMask","","System.Int32"),objects[i])}});
    }
    bool independentDepth() const {return worldTarget!=nullptr;}
    void configureShader(const std::string& name) {
        auto* selected=api.call(api.method("Shader","Find","System.String","UnityEngine.Shader"),nullptr,{api.string(name)});
        // Some streamed HGRP assets are loaded but absent from Shader.Find's table.
        if(!selected) {
            auto module=GetModuleHandleW(L"GameAssembly.dll");
            auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(module,"il2cpp_array_length"));
            auto chars=reinterpret_cast<const wchar_t*(*)(void*)>(GetProcAddress(module,"il2cpp_string_chars"));
            auto strLength=reinterpret_cast<int(*)(void*)>(GetProcAddress(module,"il2cpp_string_length"));
            auto* all=api.call(api.method("Resources","FindObjectsOfTypeAll","System.Type","UnityEngine.Object[]"),nullptr,{api.klass("Shader").type_object});
            unity::Api::TemporaryRoot root(api.raw(),all);
            if(!length||!chars||!strLength||length(all)>4096) throw std::runtime_error("shader lookup exports unavailable");
            auto** objects=reinterpret_cast<void**>(static_cast<unsigned char*>(all)+32);
            for(std::uintptr_t i=0;i<length(all);++i) {
                auto* text=api.call(api.method("Object","get_name","","System.String"),objects[i]);int n=text?strLength(text):0;
                if(n<=0||n>1024) continue;
                int bytes=WideCharToMultiByte(CP_UTF8,0,chars(text),n,nullptr,0,nullptr,nullptr);std::string candidate(bytes,'\0');
                WideCharToMultiByte(CP_UTF8,0,chars(text),n,candidate.data(),bytes,nullptr,nullptr);
                if(candidate==name) {selected=api.keep(objects[i]);break;}
            }
        }
        if(!selected) throw std::runtime_error("requested shader unavailable");
        shader=selected;shaderName=name;
        shaderSupported=api.value<bool>(api.method("Shader","get_isSupported","","System.Boolean"),shader);
        for(auto& [id,mat]:materials) {
            api.call(api.method("Material","set_shader","UnityEngine.Shader"),mat,{shader});
            if(auto tex=textures.find(id);tex!=textures.end()) bindTexture(mat,tex->second);
        }
    }
    bool shaderSupported=false;
    std::uint64_t hudFrames=0,meshMessages=0,avatarFrames=0;
    std::uint64_t avatarVertices=0;
    std::size_t sectionCount() const {return sections.size();}
    std::size_t textureCount() const {return textures.size();}
    int layer() const {return worldLayer;}
    int mask() const {return cameraMask;}
    std::size_t visibleMeshes() {
        std::size_t count=0;
        for(const auto& [key,section]:sections)
            if(api.value<bool>(api.method("Renderer","get_isVisible","","System.Boolean"),section.renderer)) ++count;
        for(const auto& mesh:avatar)
            if(api.value<bool>(api.method("Renderer","get_isVisible","","System.Boolean"),mesh.renderer)) ++count;
        return count;
    }
    void reanchor(unity::V3 origin) {hostOrigin=origin;}
    std::string shaderName;
    bool ready=false;
    void init(const BE_HostApiV1* host,unity::V3 origin) {
        api.bind(host);hostOrigin=origin;
        if(auto* camera=api.call(api.method("Camera","get_main","","UnityEngine.Camera"))) {
            cameraMask=api.value<int>(api.method("Camera","get_cullingMask","","System.Int32"),camera);
            // New GameObjects start on Default, which the host camera may exclude.
            if(cameraMask) for(worldLayer=0;worldLayer<31;++worldLayer) if(std::uint32_t(cameraMask)&(1u<<worldLayer)) break;
        }
        canvas=api.gameObject("EndCraft HUD");auto* component=api.add(canvas,"Canvas","UnityEngine.UIModule.dll");
        int mode=0,order=32760;
        api.call(api.method("Canvas","set_renderMode","UnityEngine.RenderMode","System.Void","UnityEngine.UIModule.dll"),component,{&mode});
        api.call(api.method("Canvas","set_sortingOrder","System.Int32","System.Void","UnityEngine.UIModule.dll"),component,{&order});
        auto* imageObject=api.gameObject("Minecraft hand and inventory");
        api.add(imageObject,"RectTransform");
        rawImage=api.add(imageObject,"RawImage","UnityEngine.UI.dll","UnityEngine.UI");
        auto* rect=api.call(api.method("Graphic","get_rectTransform","","UnityEngine.RectTransform","UnityEngine.UI.dll","UnityEngine.UI"),rawImage);
        bool worldPositionStays=false,raycast=false;
        api.call(api.method("Transform","SetParent","UnityEngine.Transform|System.Boolean"),rect,{api.transform(canvas),&worldPositionStays});
        unity::V2 zero{0,0},one{1,1};
        api.call(api.method("RectTransform","set_anchorMin","UnityEngine.Vector2"),rect,{&zero});
        api.call(api.method("RectTransform","set_anchorMax","UnityEngine.Vector2"),rect,{&one});
        api.call(api.method("RectTransform","set_offsetMin","UnityEngine.Vector2"),rect,{&zero});
        api.call(api.method("RectTransform","set_offsetMax","UnityEngine.Vector2"),rect,{&zero});
        api.call(api.method("Graphic","set_raycastTarget","System.Boolean","System.Void","UnityEngine.UI.dll","UnityEngine.UI"),rawImage,{&raycast});
        api.setActive(canvas,false);ready=true;
    }
    void visible(bool value) {if(canvas) api.setActive(canvas,value);for(auto& [key,s]:sections) api.setActive(s.object,value);if(!value) for(auto& a:avatar) api.setActive(a.object,false);}
    void collisionEnabled(bool value) {
        for(auto& [key,s]:sections) if(s.collider)
            api.call(api.method("Collider","set_enabled","System.Boolean","System.Void","UnityEngine.PhysicsModule.dll"),s.collider,{&value});
    }
    std::size_t drawImmediate(void* camera) {
        auto view=api.value<unity::Matrix>(api.method("Camera","get_worldToCameraMatrix","","UnityEngine.Matrix4x4"),camera);
        auto projection=api.value<unity::Matrix>(api.method("Camera","get_projectionMatrix","","UnityEngine.Matrix4x4"),camera);
        auto* old=api.call(api.method("RenderTexture","get_active","","UnityEngine.RenderTexture"));
        unity::Api::TemporaryRoot root(api.raw(),old?old:camera);
        api.call(api.method("GL","PushMatrix"));
        std::size_t count=0;
        try {
            int width=api.value<int>(api.method("Screen","get_width","","System.Int32")),height=api.value<int>(api.method("Screen","get_height","","System.Int32"));
            if(width<=0||height<=0||width>4096||height>4096) throw std::runtime_error("world target dimensions invalid");
            if(!worldTarget||targetWidth!=width||targetHeight!=height) {
                if(worldTarget) {api.call(api.method("RenderTexture","Release"),worldTarget);api.destroy(worldTarget);}
                worldTarget=api.object("RenderTexture");int depth=24,format=0;
                api.call(api.method("RenderTexture",".ctor","System.Int32|System.Int32|System.Int32|UnityEngine.RenderTextureFormat"),worldTarget,{&width,&height,&depth,&format});
                int filter=0;api.call(api.method("Texture","set_filterMode","UnityEngine.FilterMode"),worldTarget,{&filter});
                if(!api.value<bool>(api.method("RenderTexture","Create","","System.Boolean"),worldTarget)) throw std::runtime_error("world color/depth target creation failed");
                targetWidth=width;targetHeight=height;
            }
            if(!nativeRendering) {
            api.call(api.method("Graphics","SetRenderTarget","UnityEngine.RenderTexture"),nullptr,{worldTarget});
            bool clear=true;unity::Color transparent{0,0,0,0};
            api.call(api.method("GL","Clear","System.Boolean|System.Boolean|UnityEngine.Color"),nullptr,{&clear,&clear,&transparent});
            api.call(api.method("GL","set_modelview","UnityEngine.Matrix4x4"),nullptr,{&view});
            api.call(api.method("GL","LoadProjectionMatrix","UnityEngine.Matrix4x4"),nullptr,{&projection});
            auto draw=[&](MeshObject& mesh) {
                if(!mesh.drawn||!mesh.material) return;
                int pass=0;
                if(!api.value<bool>(api.method("Material","SetPass","System.Int32","System.Boolean"),mesh.material,{&pass})) return;
                auto matrix=api.value<unity::Matrix>(api.method("Transform","get_localToWorldMatrix","","UnityEngine.Matrix4x4"),api.transform(mesh.object));
                api.call(api.method("Graphics","DrawMeshNow","UnityEngine.Mesh|UnityEngine.Matrix4x4"),nullptr,{mesh.mesh,&matrix});++count;
            };
            for(auto& [key,mesh]:sections) draw(mesh);
            for(auto& mesh:avatar) draw(mesh);
            api.call(api.method("Graphics","SetRenderTarget","UnityEngine.RenderTexture"),nullptr,{nullptr});
            }
            // HGRP draws world meshes with scene depth before native UI. The
            // fallback owns its depth; MC hand/HUD are drawn last in either mode.
            if(hudTexture) {
                if(!hudMaterial) {
                    auto* uiShader=api.call(api.method("Shader","Find","System.String","UnityEngine.Shader"),nullptr,{api.string("UI/Default")});
                    hudMaterial=api.object("Material");api.call(api.method("Material",".ctor","UnityEngine.Shader"),hudMaterial,{uiShader});
                    int always=8;api.call(api.method("Material","SetInt","System.String|System.Int32"),hudMaterial,{api.string("unity_GUIZTestMode"),&always});
                }
                float left=0,top=0,right=float(api.value<int>(api.method("Screen","get_width","","System.Int32"))),bottom=float(api.value<int>(api.method("Screen","get_height","","System.Int32")));
                api.call(api.method("GL","LoadPixelMatrix","System.Single|System.Single|System.Single|System.Single"),nullptr,{&left,&right,&bottom,&top});
                unity::Rect screen{0,0,right,bottom},uv{0,0,1,1};int zero=0,pass=0;unity::Color white{1,1,1,1};
                if(!nativeRendering) api.call(api.method("Graphics","DrawTexture","UnityEngine.Rect|UnityEngine.Texture|UnityEngine.Rect|System.Int32|System.Int32|System.Int32|System.Int32|UnityEngine.Color|UnityEngine.Material|System.Int32"),nullptr,{&screen,worldTarget,&uv,&zero,&zero,&zero,&zero,&white,hudMaterial,&pass});
                api.call(api.method("Graphics","DrawTexture","UnityEngine.Rect|UnityEngine.Texture|UnityEngine.Rect|System.Int32|System.Int32|System.Int32|System.Int32|UnityEngine.Color|UnityEngine.Material|System.Int32"),nullptr,{&screen,hudTexture,&uv,&zero,&zero,&zero,&zero,&white,hudMaterial,&pass});
                ++count;
            }
        } catch(...) {
            api.call(api.method("GL","PopMatrix"));
            api.call(api.method("RenderTexture","set_active","UnityEngine.RenderTexture"),nullptr,{old});throw;
        }
        api.call(api.method("GL","PopMatrix"));
        api.call(api.method("RenderTexture","set_active","UnityEngine.RenderTexture"),nullptr,{old});
        return count;
    }
    std::size_t drawCommands(void* command,void* camera) {
        auto view=api.value<unity::Matrix>(api.method("Camera","get_worldToCameraMatrix","","UnityEngine.Matrix4x4"),camera);
        auto projection=api.value<unity::Matrix>(api.method("Camera","get_projectionMatrix","","UnityEngine.Matrix4x4"),camera);
        bool intoTexture=false;
        projection=api.value<unity::Matrix>(api.method("GL","GetGPUProjectionMatrix","UnityEngine.Matrix4x4|System.Boolean","UnityEngine.Matrix4x4"),nullptr,{&projection,&intoTexture});
        // This player strips SetViewProjectionMatrices. Built-in unlit shaders
        // consume unity_MatrixVP; record the measured GPU matrix explicitly.
        unity::Matrix vp{};
        for(int c=0;c<4;++c) for(int r=0;r<4;++r) for(int k=0;k<4;++k)
            vp.values[c*4+r]+=projection.values[k*4+r]*view.values[c*4+k];
        int id=api.value<int>(api.method("Shader","PropertyToID","System.String","System.Int32"),nullptr,{api.string("unity_MatrixVP")});
        api.call(api.method("CommandBuffer","SetGlobalMatrix","System.Int32|UnityEngine.Matrix4x4","System.Void","UnityEngine.CoreModule.dll","UnityEngine.Rendering"),command,{&id,&vp});
        int targetType=2; // BuiltinRenderTextureType.CameraTarget, constructed by Unity.
        auto target=api.value<unity::RenderTarget>(api.method("RenderTargetIdentifier","op_Implicit","UnityEngine.Rendering.BuiltinRenderTextureType","UnityEngine.Rendering.RenderTargetIdentifier","UnityEngine.CoreModule.dll","UnityEngine.Rendering"),nullptr,{&targetType});
        api.call(api.method("CommandBuffer","SetRenderTarget","UnityEngine.Rendering.RenderTargetIdentifier","System.Void","UnityEngine.CoreModule.dll","UnityEngine.Rendering"),command,{&target});
        auto rect=api.value<unity::Rect>(api.method("Camera","get_pixelRect","","UnityEngine.Rect"),camera);
        api.call(api.method("CommandBuffer","SetViewport","UnityEngine.Rect","System.Void","UnityEngine.CoreModule.dll","UnityEngine.Rendering"),command,{&rect});
        const auto draw=api.method("CommandBuffer","DrawMesh","UnityEngine.Mesh|UnityEngine.Matrix4x4|UnityEngine.Material|System.Int32|System.Int32","System.Void","UnityEngine.CoreModule.dll","UnityEngine.Rendering");
        std::size_t count=0;
        auto append=[&](MeshObject& mesh) {
            if(!mesh.drawn||!mesh.material) return;
            auto matrix=api.value<unity::Matrix>(api.method("Transform","get_localToWorldMatrix","","UnityEngine.Matrix4x4"),api.transform(mesh.object));
            int submesh=0,pass=0;
            api.call(draw,command,{mesh.mesh,&matrix,mesh.material,&submesh,&pass});++count;
        };
        for(auto& [key,mesh]:sections) append(mesh);
        for(auto& mesh:avatar) append(mesh);
        return count;
    }
    void overlay(BridgeMemory& memory) {
        auto* control=reinterpret_cast<volatile LONG*>(memory.data+proto::kOffOverlayCtl);
        const auto state=InterlockedCompareExchange(control,0,0);if(!(state&proto::kOverlayDirty)) return;
        const auto exchanged=InterlockedExchange(control,LONG(front));front=unsigned(exchanged)&3;
        if(front>=3) throw std::runtime_error("overlay slot invalid");
        proto::OverlaySlotHdr header;std::memcpy(&header,memory.data+proto::kOffOverlaySlotHdr+front*0x40,sizeof(header));
        if(!header.width||!header.height||header.width>proto::kMaxOverlayW||header.height>proto::kMaxOverlayH) throw std::runtime_error("overlay dimensions invalid");
        auto bytes=std::size_t(header.width)*header.height*4;
        auto* source=memory.data+proto::kOffOverlayPixels+front*proto::kOverlaySlotBytes;
        pixels.resize(bytes);
        // Unity raw texture data starts at its bottom row. MC publishes its row convention.
        if(header.flags&1) std::memcpy(pixels.data(),source,bytes);
        else for(unsigned row=0;row<header.height;++row) std::memcpy(pixels.data()+row*header.width*4,source+(header.height-1-row)*header.width*4,header.width*4);
        if(!hudTexture||hudWidth!=header.width||hudHeight!=header.height) {
            if(hudTexture) api.destroy(hudTexture);
            hudTexture=api.texture(header.width,header.height,pixels.data());hudWidth=header.width;hudHeight=header.height;
            api.call(api.method("RawImage","set_texture","UnityEngine.Texture","System.Void","UnityEngine.UI.dll","UnityEngine.UI"),rawImage,{hudTexture});
        } else api.upload(hudTexture,hudWidth,hudHeight,pixels.data());
        api.setActive(canvas,true);++hudFrames;
    }
    void message(unsigned type,const std::vector<unsigned char>& payload,unity::V3 player) {
        if(type==proto::kRenAtlas||type==proto::kRenTexture) {
            unsigned id=0,w,h;std::size_t offset;
            if(type==proto::kRenAtlas) {auto header=read<proto::RenAtlas>(payload);w=header.width;h=header.height;offset=sizeof(header);}
            else {auto header=read<proto::RenTexture>(payload);id=header.id;w=header.width;h=header.height;offset=sizeof(header);}
            if(!w||!h||w>8192||h>8192||std::uint64_t(w)*h*4>payload.size()-offset) throw std::runtime_error("texture payload invalid");
            newTexture(id,w,h,payload.data()+offset);return;
        }
        if(type==proto::kRenClearAll) {
            for(auto& [key,s]:sections) {api.destroy(s.object);api.destroy(s.mesh);}sections.clear();return;
        }
        if(type==proto::kRenSection) {
            auto header=read<proto::RenSection>(payload);std::array<int,3> key{header.sx,header.sy,header.sz};
            if(std::uint64_t(header.vertexCount)*sizeof(proto::RenVertex)>payload.size()-sizeof(header)) throw std::runtime_error("section payload invalid");
            if(!header.vertexCount) {if(auto old=sections.find(key);old!=sections.end()) {api.destroy(old->second.object);api.destroy(old->second.mesh);sections.erase(old);}return;}
            auto [entry,inserted]=sections.try_emplace(key);if(inserted) entry->second=createMesh("Minecraft blocks",true);
            updateMesh(entry->second,reinterpret_cast<const proto::RenVertex*>(payload.data()+sizeof(header)),header.vertexCount,0,{float(header.sx*16),float(header.sy*16),float(header.sz*16)});++meshMessages;return;
        }
        if(type==proto::kRenAvatar) {
            auto header=read<proto::RenAvatar>(payload);
            avatarVertices=header.vertexCount;
            auto offset=sizeof(header)+std::uint64_t(header.batchCount)*sizeof(proto::RenBatch);
            if(header.batchCount>64||offset>payload.size()||std::uint64_t(header.vertexCount)*32>payload.size()-offset) throw std::runtime_error("avatar payload invalid");
            while(avatar.size()<header.batchCount) avatar.push_back(createMesh("Minecraft Steve",false));
            for(unsigned i=0;i<header.batchCount;++i) {
                auto batch=read<proto::RenBatch>(payload,sizeof(header)+i*sizeof(proto::RenBatch));
                if(std::uint64_t(batch.first)+batch.count>header.vertexCount) throw std::runtime_error("avatar batch invalid");
                updateMesh(avatar[i],reinterpret_cast<const proto::RenVertex*>(payload.data()+offset)+batch.first,batch.count,batch.texture,player,true);
            }
            for(std::size_t i=header.batchCount;i<avatar.size();++i) {api.setActive(avatar[i].object,false);avatar[i].drawn=false;}
            ++avatarFrames;
        }
    }
};
}
