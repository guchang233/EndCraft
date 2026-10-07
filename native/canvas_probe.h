#pragma once
#include "unity_bridge.h"
#include "nlohmann/json.hpp"
#include <mutex>
namespace endcraft {
class CanvasProbe {
    unity::Api api;
    std::mutex mutex;
    bool requested=false;
    bool visible=false;
    void* root=nullptr;
    void* render=nullptr;
    void* mat=nullptr;
    void* source=nullptr;
    std::string error;
    std::uint64_t frames=0;
public:
    void enable(bool value) {std::lock_guard lock(mutex);requested=value;}
    void tick(const BE_HostApiV1* host) {
        std::lock_guard lock(mutex);api.bind(host);
        try {
            if(!requested) {if(root) api.setActive(root,false);visible=false;return;}
            if(!source) source=api.call(api.method("GameObject","Find","System.String","UnityEngine.GameObject"),nullptr,{api.string("Minecraft Steve")});
            if(!source) {error="waiting for active Minecraft Steve mesh";return;}
            auto filterClass=api.klass("MeshFilter");
            auto rendererClass=api.klass("MeshRenderer");
            auto* filter=api.call(api.method("GameObject","GetComponent","System.Type","UnityEngine.Component"),source,{filterClass.type_object});
            auto* renderer=api.call(api.method("GameObject","GetComponent","System.Type","UnityEngine.Component"),source,{rendererClass.type_object});
            if(!filter||!renderer) throw std::runtime_error("guest mesh components absent");
            auto* mesh=api.call(api.method("MeshFilter","get_sharedMesh","","UnityEngine.Mesh"),filter);
            auto* sourceMat=api.call(api.method("Renderer","get_sharedMaterial","","UnityEngine.Material"),renderer);
            auto* tex=api.call(api.method("Material","get_mainTexture","","UnityEngine.Texture"),sourceMat);
            auto* camera=api.call(api.method("Camera","get_main","","UnityEngine.Camera"));
            if(!camera||!mesh||!tex) return;
            if(!root) {
                root=api.gameObject("EndCraft world canvas diagnostic");
                auto* canvas=api.add(root,"Canvas","UnityEngine.UIModule.dll");int mode=2,order=0;
                api.call(api.method("Canvas","set_renderMode","UnityEngine.RenderMode","System.Void","UnityEngine.UIModule.dll"),canvas,{&mode});
                api.call(api.method("Canvas","set_worldCamera","UnityEngine.Camera","System.Void","UnityEngine.UIModule.dll"),canvas,{camera});
                api.call(api.method("Canvas","set_sortingOrder","System.Int32","System.Void","UnityEngine.UIModule.dll"),canvas,{&order});
                unity::V3 scale{1,1,1};api.call(api.method("Transform","set_localScale","UnityEngine.Vector3"),api.transform(root),{&scale});
                auto* child=api.gameObject("EndCraft diagnostic skin");api.add(child,"RectTransform");
                bool worldStays=false;
                api.call(api.method("Transform","SetParent","UnityEngine.Transform|System.Boolean"),api.transform(child),{api.transform(root),&worldStays});
                render=api.add(child,"CanvasRenderer","UnityEngine.UIModule.dll");
                int count=1;bool cull=false;unity::Color white{1,1,1,1};
                api.call(api.method("CanvasRenderer","set_materialCount","System.Int32","System.Void","UnityEngine.UIModule.dll"),render,{&count});
                api.call(api.method("CanvasRenderer","set_cull","System.Boolean","System.Void","UnityEngine.UIModule.dll"),render,{&cull});
                api.call(api.method("CanvasRenderer","SetColor","UnityEngine.Color","System.Void","UnityEngine.UIModule.dll"),render,{&white});
                auto* shader=api.call(api.method("Shader","Find","System.String","UnityEngine.Shader"),nullptr,{api.string("UI/Default")});
                if(!shader) throw std::runtime_error("world canvas shader absent");
                mat=api.object("Material");api.call(api.method("Material",".ctor","UnityEngine.Shader"),mat,{shader});
            }
            auto at=api.value<unity::V3>(api.method("Transform","get_position","","UnityEngine.Vector3"),api.transform(source));
            auto right=api.value<unity::V3>(api.method("Transform","get_right","","UnityEngine.Vector3"),api.transform(camera,true));
            at.x+=right.x*2.5f;at.y+=right.y*2.5f;at.z+=right.z*2.5f;
            api.setPosition(api.transform(root),at);
            api.call(api.method("CanvasRenderer","SetMaterial","UnityEngine.Material|UnityEngine.Texture","System.Void","UnityEngine.UIModule.dll"),render,{mat,tex});
            api.call(api.method("CanvasRenderer","SetMesh","UnityEngine.Mesh","System.Void","UnityEngine.UIModule.dll"),render,{mesh});
            api.setActive(root,true);visible=true;++frames;error.clear();
        } catch(const std::exception& e) {error=e.what();requested=false;visible=false;if(root) try {api.setActive(root,false);} catch(...) {}}
    }
    nlohmann::json snapshot() {std::lock_guard lock(mutex);return {{"requested",requested},{"created",root!=nullptr},{"visible",visible},{"frames",frames},{"error",error},{"offset_camera_right",2.5}};}
};
}
