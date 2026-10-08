#pragma once
#include "unity_renderer.h"
#include "coordinate_map.h"
#include "player_input_mask.h"
#include "input_mode.h"
#include "camera_math.h"
#include "native_combat.h"
#include "host_authority.h"
#include "nlohmann/json.hpp"
#include <atomic>
#include <mutex>
#include <cmath>
#include <algorithm>
#include <fstream>

namespace endcraft {
class GameplayBridge {
    unity::Api api;
    UnityRenderer renderer;
    PlayerInputMask inputMask;
    BridgeMemory memory;
    NativeCombat combat;
    HostAuthority<unity::V3> hostAuthority;
    std::atomic<bool> hostTeleporting=false;
    std::atomic<std::uint64_t> hostTeleportSettleUntil=0;
    std::uint64_t hostTeleports=0;
    std::mutex mutex;
    std::atomic<bool> requested=false;
    bool initialized=false,active=false;
    unity::V3 origin{},mcOrigin{.5f,64,.5f};
    unity::V3 initialHost{},teleportPosition{.5f,64,.5f};
    bool suppliedAnchor=false;
    std::uint32_t epoch=0,teleport=0;
    std::uintptr_t characterId=0;
    std::uint64_t frames=0,moves=0,lastTerrain=0;
    std::uint64_t droppedMessages=0,resyncs=0,badPoses=0;
    // A gap wider than this between where Minecraft says the player is and where the host
    // character actually stands is a teleport (respawn, portal, or drift accumulated while the
    // game window was in the background), not a fault: TeleportTo already jumps any distance.
    static constexpr float kResyncDistance=8.f;
    // Minecraft can produce far more render messages per frame than a section or two: 12 sections
    // (mesh + lights + solids + dug), every animated texture's current frame, the avatar and the
    // scene. Draining a fixed handful per frame lets the 64 MB ring fill up, after which
    // tryWriteRender drops the avatar outright and writeRender stalls Minecraft's render thread.
    static constexpr unsigned kMaxRenderMessages=512;
    static constexpr std::size_t kRenderByteBudget=8ull<<20;
    int terrainIndex=4;
    int terrainSweep=0;
    int terrainCenterX=INT_MIN,terrainCenterZ=INT_MIN;
    unsigned terrainHits=0,terrainTriangles=0;
    std::uint64_t terrainSamples=0,safetyStops=0;
    std::string lastRecoveryReason;
    unity::V3 safeHost{};
    bool keys[256]{};bool buttons[4]{};
    InputMode inputMode;
    std::atomic<bool> exclusiveGate=false;
    std::atomic<int> queuedInputMode{-1};
    bool modeNotification=true;
    std::atomic<std::uint64_t> suppressedNativeKeys=0;
    std::atomic<std::uint64_t> suppressedNativeBindings=0;
    bool nativeKeyHooks=false;
    std::vector<unsigned char> payload;
    std::string error;
    std::string renderError,hideError;
    proto::McState lastMc{};
    bool guestReady=false;
    std::size_t visibleMeshes=0;
    bool cursorReleased=false;
    int savedCursorLock=0;
    bool savedCursorVisible=false;
    std::string scrollError;
    void* drawBuffer=nullptr;
    std::uint64_t pipelineFrames=0,pipelineDraws=0;
    std::string pipelineError;
    bool captured=false;
    std::string captureError;
    bool hostMenu=false;
    std::string queuedShader;
    std::atomic<bool> rejoin=false,recovery=false;
    bool mcRecoveryHeld=false,mcWasDead=false,recoveryShortcutHeld=false;
    std::uint64_t holdStarted=0,recoveryCount=0;
    std::uint64_t keyRepeatAt[256]{};
    std::atomic<long> rawX=0,rawY=0;
    std::atomic<bool> rawReady=false;
    std::atomic<std::uint64_t> rawPackets=0;
    CameraAngles lookAngles;
    bool lookInitialized=false;
    bool probeRequested=false;
    float cameraDistanceUsed=0;
    int viewportW=640,viewportH=360;
    static constexpr const char* gameAssembly="Gameplay.Beyond.dll";
    static constexpr const char* gameSpace="Beyond.Gameplay.Core";
    static float distance(unity::V3 a,unity::V3 b) {return std::sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z));}
    struct HostTerrainQuery {
        UnityRenderer& renderer;
        explicit HostTerrainQuery(UnityRenderer& r):renderer(r) {renderer.collisionEnabled(false);}
        ~HostTerrainQuery() {try {renderer.collisionEnabled(true);} catch(...) {}}
    };
    unity::V3 toHost(unity::V3 value) {return coordinates::toHost(origin,mcOrigin,value);}
    unity::V3 toMc(unity::V3 value) {return coordinates::toMc(origin,mcOrigin,value);}
    void input(bool focus) {
        const int queued=queuedInputMode.exchange(-1);
        if(queued>=0) {inputMode.set(queued!=0);modeNotification=true;}
        if(inputMode.poll(focus,(GetAsyncKeyState(VK_OEM_1)&0x8000)!=0)) {
            modeNotification=true;
            memory.input({proto::kInReleaseAll,0,0,0,0});
            std::fill(std::begin(keys),std::end(keys),false);
            std::fill(std::begin(buttons),std::end(buttons),false);
        }
        if(modeNotification&&focus&&memory.input({proto::kInInputMode,0,inputMode.exclusive()?1:0,0,0})) modeNotification=false;
        if(focus) {
            try {
                const auto delta=api.value<unity::V2>(api.method("Input","get_mouseScrollDelta","","UnityEngine.Vector2","UnityEngine.InputLegacyModule.dll"));
                if(std::isfinite(delta.y)&&delta.y!=0) memory.input({proto::kInScroll,0,int((std::clamp)(delta.y,-20.f,20.f)*120),0,0});
            } catch(const std::exception& e) {scrollError=e.what();}
        }
        if(focus&&(lastMc.flags&proto::kMcScreenOpen)) {
            POINT point{};RECT bounds{};auto window=GetForegroundWindow();
            if(GetCursorPos(&point)&&ScreenToClient(window,&point)&&GetClientRect(window,&bounds)&&bounds.right>0&&bounds.bottom>0)
                memory.input({proto::kInCursor,0,int(point.x*static_cast<long long>(viewportW)/bounds.right),int(point.y*static_cast<long long>(viewportH)/bounds.bottom),0});
        }
        // Preserve Minecraft's SDL scancodes for inventory and camera controls.
        const bool recoverDown=focus&&(GetAsyncKeyState(VK_CONTROL)&0x8000)&&(GetAsyncKeyState(VK_MENU)&0x8000)&&(GetAsyncKeyState('R')&0x8000);
        if(recoverDown&&!recoveryShortcutHeld) recovery.store(true);
        recoveryShortcutHeld=recoverDown;
        if(recoverDown) return;
        const int keyPairs[][2]={{VK_LSHIFT,225},{VK_RSHIFT,229},{VK_LCONTROL,224},{VK_RCONTROL,228},{VK_LMENU,226},{VK_RMENU,230},{'T',23},{VK_OEM_2,56},{VK_RETURN,40},{VK_BACK,42},{VK_TAB,43},
            {VK_LEFT,80},{VK_RIGHT,79},{VK_UP,82},{VK_DOWN,81},{VK_HOME,74},{VK_END,77},{VK_DELETE,76},
            {'B',5},{'C',6},{'G',10},{'H',11},{'I',12},{'J',13},{'K',14},{'L',15},{'M',16},{'N',17},{'O',18},{'P',19},{'R',21},{'U',24},{'V',25},{'X',27},{'Y',28},{'Z',29},
            {VK_OEM_PLUS,46},{VK_OEM_COMMA,54},{VK_OEM_MINUS,45},{VK_OEM_PERIOD,55},{VK_OEM_3,53},{VK_OEM_4,47},{VK_OEM_5,49},{VK_OEM_6,48},{VK_OEM_7,52},
            {'W',26},{'A',4},{'S',22},{'D',7},{VK_SPACE,44},
            {'E',8},{'Q',20},{'F',9},{VK_F5,62},{VK_ESCAPE,41},{'1',30},{'2',31},{'3',32},{'4',33},{'5',34},{'6',35},{'7',36},{'8',37},{'9',38},{'0',39}};
        for(const auto& pair:keyPairs) {
            const bool down=focus&&(GetAsyncKeyState(pair[0])&0x8000)!=0;
            const auto now=GetTickCount64();
            const bool repeat=down&&keys[pair[0]]&&(lastMc.flags&proto::kMcScreenOpen)&&now>=keyRepeatAt[pair[0]];
            if(down!=keys[pair[0]]||repeat) {
                if(memory.input({proto::kInKey,std::uint16_t(pair[1]),down?1:0,0,0})) {
                    const bool wasDown=keys[pair[0]];keys[pair[0]]=down;
                    keyRepeatAt[pair[0]]=now+(wasDown?50:500);
                    if(down&&(lastMc.flags&proto::kMcScreenOpen)) {
                        BYTE state[256]{};GetKeyboardState(state);
                        for(int i=0;i<256;++i) state[i]=(state[i]&1)|((GetAsyncKeyState(i)&0x8000)?0x80:0);
                        wchar_t chars[8]{};auto layout=GetKeyboardLayout(GetWindowThreadProcessId(GetForegroundWindow(),nullptr));
                        const auto count=ToUnicodeEx(UINT(pair[0]),MapVirtualKeyExW(UINT(pair[0]),MAPVK_VK_TO_VSC,layout),state,chars,8,4,layout);
                        for(int i=0;i<count;++i) if(chars[i]>=32&&chars[i]!=127) {
                            std::uint32_t code=chars[i];
                            if(code>=0xd800&&code<=0xdbff&&i+1<count&&chars[i+1]>=0xdc00&&chars[i+1]<=0xdfff) code=0x10000+((code-0xd800)<<10)+(chars[++i]-0xdc00);
                            memory.input({proto::kInText,0,int(code),0,0});
                        }
                    }
                }
            }
        }
        const int mousePairs[][2]={{VK_LBUTTON,1},{VK_MBUTTON,2},{VK_RBUTTON,3}};
        for(const auto& pair:mousePairs) {
            const bool down=focus&&(GetAsyncKeyState(pair[0])&0x8000)!=0;
            if(down!=buttons[pair[1]]) {
                if(memory.input({proto::kInMouseButton,std::uint16_t(pair[1]),down?1:0,0,0})) buttons[pair[1]]=down;
            }
        }
    }
    void cursor(bool inventory) {
        if(inventory&&!cursorReleased) {
            savedCursorLock=api.value<int>(api.method("Cursor","get_lockState","","UnityEngine.CursorLockMode"));
            savedCursorVisible=api.value<bool>(api.method("Cursor","get_visible","","System.Boolean"));
            cursorReleased=true;
        }
        if(inventory) {
            int unlocked=0;bool visible=true;
            api.call(api.method("Cursor","set_lockState","UnityEngine.CursorLockMode"),nullptr,{&unlocked});
            api.call(api.method("Cursor","set_visible","System.Boolean"),nullptr,{&visible});
        } else if(cursorReleased) {
            api.call(api.method("Cursor","set_lockState","UnityEngine.CursorLockMode"),nullptr,{&savedCursorLock});
            api.call(api.method("Cursor","set_visible","System.Boolean"),nullptr,{&savedCursorVisible});
            cursorReleased=false;
        }
    }
    bool ray(void* movement,unity::V3 at,unity::V3& result) {
        alignas(16) unsigned char hit[512]{};
        unity::V3 direction{0,-1,0};float range=80;bool flag=false;
        const auto method=api.method("MovementComponent","CharacterCollisionRaycast","UnityEngine.Vector3|UnityEngine.Vector3|System.Single|UnityEngine.RaycastHit&|System.Boolean","System.Int32",gameAssembly,gameSpace);
        api.value<int>(method,movement,{&at,&direction,&range,hit,&flag});
        unity::V3 normal{};float length=0;
        std::memcpy(&result,hit,sizeof(result));std::memcpy(&normal,hit+12,sizeof(normal));std::memcpy(&length,hit+28,4);
        // The return value is a game-specific hit mask, not a boolean. Validate the
        // actual measured hit instead of treating any nonzero mask as ground.
        return std::isfinite(result.x)&&std::isfinite(result.y)&&std::isfinite(result.z)
            &&std::isfinite(length)&&length>0&&length<=range&&normal.y>.05f
            &&std::abs(result.x-at.x)<.1f&&std::abs(result.z-at.z)<.1f&&std::abs(at.y-result.y-length)<.1f;
    }
    float cameraBoom(void* movement,unity::V3 eye,unity::V3 direction,float range) {
        if(!std::isfinite(range)||range<=0||range>16) return 0;
        alignas(16) unsigned char hit[512]{};bool flag=false;
        auto method=api.method("MovementComponent","CharacterCollisionRaycast","UnityEngine.Vector3|UnityEngine.Vector3|System.Single|UnityEngine.RaycastHit&|System.Boolean","System.Int32",gameAssembly,gameSpace);
        api.value<int>(method,movement,{&eye,&direction,&range,hit,&flag});
        unity::V3 point{},normal{};float length=0;
        std::memcpy(&point,hit,12);std::memcpy(&normal,hit+12,12);std::memcpy(&length,hit+28,4);
        if(!std::isfinite(length)||length<=0||length>range||!std::isfinite(point.x)||!std::isfinite(point.y)||!std::isfinite(point.z)) return range;
        unity::V3 expected{eye.x+direction.x*length,eye.y+direction.y*length,eye.z+direction.z*length};
        if(distance(expected,point)>.1f||normal.x*normal.x+normal.y*normal.y+normal.z*normal.z<.25f) return range;
        return (std::max)(0.f,length-.2f);
    }
    void terrain(void* movement,unity::V3 mc,unity::V3 /*host*/) {
        HostTerrainQuery query(renderer);
        // A measured height field for the first bridge. Vertical walls/caves still need mesh extraction.
        int centerX=int(std::floor(mc.x/8))*8,centerZ=int(std::floor(mc.z/8))*8;
        // Refresh the player's current region every other sample; the ring of
        // distant regions must not delay fresh support under moving feet.
        if(centerX!=terrainCenterX||centerZ!=terrainCenterZ) {terrainIndex=4;terrainCenterX=centerX;terrainCenterZ=centerZ;}
        if((terrainSamples&1)==0) terrainIndex=4;
        else {terrainIndex=terrainSweep;terrainSweep=(terrainSweep+1)%9;if(terrainSweep==4) terrainSweep=5;}
        const int minX=centerX+(terrainIndex%3-1)*8,minZ=centerZ+(terrainIndex/3-1)*8;
        float heights[81];bool valid[81];
        terrainHits=0;++terrainSamples;
        for(int z=0;z<=8;++z) for(int x=0;x<=8;++x) {
            auto p=toHost({float(minX+x),mc.y,float(minZ+z)});p.y=toHost(mc).y+2.f;
            unity::V3 hit{};auto i=z*9+x;valid[i]=ray(movement,p,hit);heights[i]=valid[i]?toMc(hit).y:0;
            terrainHits+=valid[i]?1u:0u;
        }
        std::vector<proto::ColTri> triangles;std::vector<proto::ColBlock> blocks;
        int minY=int(std::floor(mc.y/8))*8-16,maxY=minY+39;
        for(int i=0;i<81;++i) if(valid[i]) minY=(std::min)(minY,int(std::floor(heights[i]/8))*8-8);
        for(int z=0;z<8;++z) for(int x=0;x<8;++x) {
            if(std::abs(minX+x-int(std::floor(mc.x)))<=1&&std::abs(minZ+z-int(std::floor(mc.z)))<=1) {
                float localHeight[25]{};bool localValid[25]{};
                for(int lz=0;lz<=4;++lz) for(int lx=0;lx<=4;++lx) {
                    auto p=toHost({minX+x+lx*.25f,mc.y,minZ+z+lz*.25f});p.y=toHost(mc).y+2.f;
                    unity::V3 hit{};int i=lz*5+lx;localValid[i]=ray(movement,p,hit);
                    if(localValid[i]) localHeight[i]=toMc(hit).y;
                }
                for(int lz=0;lz<4;++lz) for(int lx=0;lx<4;++lx) {
                    int a=lz*5+lx,b=a+1,c=a+5,d=c+1;
                    if(!localValid[a]||!localValid[b]||!localValid[c]||!localValid[d]) continue;
                    float low=(std::min)({localHeight[a],localHeight[b],localHeight[c],localHeight[d]});
                    float high=(std::max)({localHeight[a],localHeight[b],localHeight[c],localHeight[d]});
                    if(high-low>.75f) continue;
                    const float px=minX+x+lx*.25f,pz=minZ+z+lz*.25f;
                    triangles.push_back({{px,localHeight[a],pz,px,localHeight[c],pz+.25f,px+.25f,localHeight[b],pz},proto::kTriTerrain});
                    triangles.push_back({{px+.25f,localHeight[b],pz,px,localHeight[c],pz+.25f,px+.25f,localHeight[d],pz+.25f},proto::kTriTerrain});
                    minY=(std::min)(minY,int(std::floor(low/8))*8-8);
                }
                continue;
            }
            int a=z*9+x,b=a+1,c=a+9,d=c+1;
            if(!valid[a]||!valid[b]||!valid[c]||!valid[d]) continue;
            float low=(std::min)({heights[a],heights[b],heights[c],heights[d]}),high=(std::max)({heights[a],heights[b],heights[c],heights[d]});
            if(high-low>2) continue;
            const float px=float(minX+x),pz=float(minZ+z);
            triangles.push_back({{px,heights[a],pz,px,heights[c],pz+1,px+1,heights[b],pz},proto::kTriTerrain});
            triangles.push_back({{px+1,heights[b],pz,px,heights[c],pz+1,px+1,heights[d],pz+1},proto::kTriTerrain});
            float surface=(heights[a]+heights[b]+heights[c]+heights[d])*.25f;
            int by=int(std::ceil(surface))-1;
            if(by<minY||by>maxY) continue;
            proto::ColBlock block{};block.x=minX+x;block.y=by;block.z=minZ+z;
            for(int y=0;y<8;++y) if(float(by)+float(y)/8<surface) block.bits[y]=~0ull;
            blocks.push_back(block);
        }
        terrainTriangles=unsigned(triangles.size());
        // Duplicate triangles in vertical regions whose boxes can meet their surface.
        for(int ry=minY;ry<=maxY;ry+=8) {
            proto::ColRegion header{minX,ry,minZ,minX+7,ry+7,minZ+7,epoch,std::uint32_t(triangles.size())};
            std::vector<unsigned char> bytes(sizeof(header)+triangles.size()*sizeof(proto::ColTri));
            std::memcpy(bytes.data(),&header,sizeof(header));std::memcpy(bytes.data()+sizeof(header),triangles.data(),triangles.size()*sizeof(proto::ColTri));
            memory.collision(proto::kColTris,bytes.data(),bytes.size());
        }
        proto::ColRegion header{minX,minY,minZ,minX+7,maxY,minZ+7,epoch,std::uint32_t(blocks.size())};
        std::vector<unsigned char> bytes(sizeof(header)+blocks.size()*sizeof(proto::ColBlock));
        std::memcpy(bytes.data(),&header,sizeof(header));std::memcpy(bytes.data()+sizeof(header),blocks.data(),blocks.size()*sizeof(proto::ColBlock));
        memory.collision(proto::kColRegion,bytes.data(),bytes.size());
    }
public:
    bool suppressNativeKey() noexcept {
        if(exclusiveGate.load(std::memory_order_acquire)) {++suppressedNativeKeys;return true;}
        return false;
    }
    bool suppressNativeBindings() noexcept {
        if(exclusiveGate.load(std::memory_order_acquire)) {++suppressedNativeBindings;return true;}
        return false;
    }
    void inputHooksReady(bool value) {nativeKeyHooks=value;}
    void inputExclusive(bool value) {queuedInputMode.store(value?1:0);}
    void observeNativeBomb(void* id,float strength,std::int64_t mask) {combat.observeNativeBomb(id,strength,mask);}
    void rawMouse(long x,long y) noexcept {if(requested.load()) {++rawPackets;rawX.fetch_add(std::clamp(x,-10000L,10000L));rawY.fetch_add(std::clamp(y,-10000L,10000L));}}
    void rawInputReady(bool value) {rawReady.store(value);}
    void look(float yaw,float pitch) {
        std::lock_guard lock(mutex);
        if(!std::isfinite(yaw)||!std::isfinite(pitch)) throw std::runtime_error("invalid look angles");
        lookAngles.yaw=std::remainder(yaw,360.f);lookAngles.pitch=std::clamp(pitch,-90.f,90.f);lookInitialized=true;
    }
    void initialAnchor(unity::V3 host,unity::V3 guest) {
        std::lock_guard lock(mutex);
        if(initialized) throw std::runtime_error("initial anchor is immutable after initialization");
        if(!std::isfinite(host.x)||!std::isfinite(host.y)||!std::isfinite(host.z)||!std::isfinite(guest.x)||!std::isfinite(guest.y)||!std::isfinite(guest.z)) throw std::runtime_error("nonfinite initial anchor");
        initialHost=host;teleportPosition=guest;suppliedAnchor=true;
    }
    void recover() {requested.store(true);recovery.store(true);}
    void hostTeleportBegin() {hostTeleporting.store(true);}
    void hostTeleportFinish() {hostTeleportSettleUntil.store(GetTickCount64()+750);hostTeleporting.store(false);recovery.store(true);}
    void enable(bool value) {if(value&&!requested.exchange(true)) rejoin.store(true);else if(!value) {requested.store(false);exclusiveGate.store(false);}}
    void capture() {std::lock_guard lock(mutex);captured=false;captureError.clear();}
    void shader(const std::string& name) {std::lock_guard lock(mutex);queuedShader=name;}
    void rendererProbe() {std::lock_guard lock(mutex);probeRequested=true;}
    void nativeRendering(bool value) {std::lock_guard lock(mutex);renderer.nativeRendering=value;}
    void renderFrame(void* camera) {
        std::lock_guard lock(mutex);
        if(!active||!guestReady||!renderer.ready||!camera) return;
        try {
            ++pipelineFrames;
            renderer.prepareNative();
            if(probeRequested) {probeRequested=false;renderer.diagnostics();}
            if(!drawBuffer) {
                drawBuffer=api.keep(api.raw()->object_new(api.raw()->context,api.klass("CommandBuffer","UnityEngine.CoreModule.dll","UnityEngine.Rendering").class_info));
                api.call(api.method("CommandBuffer",".ctor","","System.Void","UnityEngine.CoreModule.dll","UnityEngine.Rendering"),drawBuffer);
            }
            api.call(api.method("CommandBuffer","Clear","","System.Void","UnityEngine.CoreModule.dll","UnityEngine.Rendering"),drawBuffer);
            const auto count=renderer.drawImmediate(camera);
            if(count) {
                pipelineDraws+=count;
            }
            if(!captured&&pipelineFrames>2&&count) {
                captured=true;
                try {
                    int w=api.value<int>(api.method("Screen","get_width","","System.Int32"));
                    int h=api.value<int>(api.method("Screen","get_height","","System.Int32"));
                    if(w<=0||h<=0||w>4096||h>4096) throw std::runtime_error("capture dimensions invalid");
                    std::vector<unsigned char> blank(std::size_t(w)*h*4);
                    auto* image=api.texture(w,h,blank.data());
                    auto* old=api.call(api.method("RenderTexture","get_active","","UnityEngine.RenderTexture"));
                    unity::Api::TemporaryRoot oldRoot(api.raw(),old?old:image);
                    api.call(api.method("RenderTexture","set_active","UnityEngine.RenderTexture"),nullptr,{nullptr});
                    try {
                        unity::Rect rect{0,0,float(w),float(h)};int zero=0;bool mip=false;
                        api.call(api.method("Texture2D","ReadPixels","UnityEngine.Rect|System.Int32|System.Int32|System.Boolean"),image,{&rect,&zero,&zero,&mip});
                    } catch(...) {api.call(api.method("RenderTexture","set_active","UnityEngine.RenderTexture"),nullptr,{old});throw;}
                    api.call(api.method("RenderTexture","set_active","UnityEngine.RenderTexture"),nullptr,{old});
                    auto* encoded=api.call(api.method("ImageConversion","EncodeToPNG","UnityEngine.Texture2D","System.Byte[]","UnityEngine.ImageConversionModule.dll"),nullptr,{image});
                    unity::Api::TemporaryRoot root(api.raw(),encoded);
                    auto length=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_array_length"));
                    const auto n=length?length(encoded):0;if(!n||n>64ull*1024*1024) throw std::runtime_error("capture encoding invalid");
                    std::ofstream output("D:\\MC x ENDFIELD\\reports\\gameplay27-frame.png",std::ios::binary);
                    output.write(reinterpret_cast<const char*>(encoded)+32,std::streamsize(n));if(!output) throw std::runtime_error("capture write failed");
                    api.destroy(image);
                } catch(const std::exception& e) {captureError=e.what();}
            }
        } catch(const std::exception& e) {pipelineError=e.what();}
    }
    /**
     * Hides the game's own character so the player controls Minecraft's Steve instead of seeing
     * two bodies standing in the same place. Only the renderers are switched off; the GameObject
     * stays active, so MovementComponent::TeleportTo, physics, health and quest logic all keep
     * running. SetActive(false) would stop the component and the character would stop following
     * Minecraft.
     *
     * Path: Entity::get_rootCom -> RootComponent::get_gameObject -> GetComponentsInChildren(
     * Renderer, true) -> Renderer::set_enabled. The pointer is not rooted: the game already holds
     * the character alive, and pinning it would keep a discarded scene object from unloading.
     */
    bool characterHidden=false,hideFailed=false;
    std::size_t hiddenRendererCount=0;
    void* characterObject=nullptr;
    struct SavedRenderer {void* object;bool enabled;std::uint32_t handle;};
    std::vector<SavedRenderer> savedRenderers;
    void* visualModel=nullptr;
    std::uint32_t visualModelHandle=0;
    void* nativeRenderHelper=nullptr;
    std::uint32_t nativeRenderHelperRoot=0;
    bool visualModelWasActive=false;
    int hideAttempts=0;
    static constexpr std::size_t kArrayPrefixBytes=32;
    void setCharacterVisible(bool show) {
        if(show) {
            if(visualModel) {
                try {api.setActive(visualModel,visualModelWasActive);} catch(const std::exception& e) {hideError=e.what();}
                api.raw()->gchandle_free(api.raw()->context,visualModelHandle);
                visualModel=nullptr;visualModelHandle=0;
            }
            for(const auto& saved:savedRenderers) {
                try {
                    if(nativeRenderHelper) api.call(api.method("EntityRenderHelper","ResetVisibleByRenderer","UnityEngine.Renderer","System.Void","Gameplay.Beyond.dll","Beyond.Gameplay.View"),nativeRenderHelper,{saved.object});
                    bool enabledBefore=saved.enabled;
                    api.call(api.method("Renderer","set_enabled","System.Boolean"),saved.object,{&enabledBefore});
                } catch(const std::exception& e) {hideError=e.what();}
                api.raw()->gchandle_free(api.raw()->context,saved.handle);
            }
            if(nativeRenderHelperRoot) api.raw()->gchandle_free(api.raw()->context,nativeRenderHelperRoot);
            nativeRenderHelper=nullptr;nativeRenderHelperRoot=0;
            savedRenderers.clear();characterHidden=false;hiddenRendererCount=0;return;
        }
        if(hideFailed||!characterObject||characterHidden) return;
        try {
            auto* root=api.call(api.method("Entity","get_rootCom","","Beyond.Gameplay.Core.RootComponent","Gameplay.Beyond.dll","Beyond.Gameplay.Core"),characterObject);
            if(!root) return;
            auto* object=api.call(api.method("RootComponent","get_gameObject","","UnityEngine.GameObject","Gameplay.Beyond.dll","Beyond.Gameplay.Core"),root);
            if(!object) return;
            auto rendererClass=api.klass("Renderer","UnityEngine.CoreModule.dll","UnityEngine");
            bool includeInactive=true;
            auto* list=api.call(api.method("GameObject","GetComponentsInChildren","System.Type|System.Boolean","UnityEngine.Component[]"),object,{rendererClass.type_object,&includeInactive});
            if(!list) return;
            unity::Api::TemporaryRoot listRoot(api.raw(),list);
            const auto lengthOf=reinterpret_cast<std::uintptr_t(*)(void*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_array_length"));
            if(!lengthOf) {hideFailed=true;return;}
            const auto count=lengthOf(list);
            if(!count) {hideError="player renderer hierarchy is empty";return;}
            auto* slots=reinterpret_cast<void**>(static_cast<unsigned char*>(list)+kArrayPrefixBytes);
            if(count>2048) throw std::runtime_error("player renderer hierarchy exceeds limit");
            for(std::uintptr_t i=0;i<count;++i) if(slots[i]) {
                bool wasEnabled=api.value<bool>(api.method("Renderer","get_enabled","","System.Boolean"),slots[i]);
                auto handle=api.raw()->gchandle_new(api.raw()->context,slots[i],0);
                if(!handle) throw std::runtime_error("player renderer root allocation failed");
                savedRenderers.push_back({slots[i],wasEnabled,handle});
                api.call(api.method("Renderer","set_enabled","System.Boolean"),slots[i],{&show});
            }
            // Hide HGRP's own renderer proxies while leaving Animator/timelines active.
            // Deactivating the model child also stops the native combat animation graph.
            auto* model=api.call(api.method("Entity","get_modelCom","","Beyond.Gameplay.View.ModelComponent","Gameplay.Beyond.dll","Beyond.Gameplay.Core"),characterObject);
            if(model) {
                nativeRenderHelper=api.call(api.method("BaseModelComponent","get_entityRenderHelper","","Beyond.Gameplay.View.EntityRenderHelper","Gameplay.Beyond.dll","Beyond.Gameplay.View"),model);
                if(!nativeRenderHelper) throw std::runtime_error("native render helper not ready");
                nativeRenderHelperRoot=api.raw()->gchandle_new(api.raw()->context,nativeRenderHelper,0);
                if(!nativeRenderHelperRoot) throw std::runtime_error("native render helper root failed");
                bool hidden=false;
                for(const auto& saved:savedRenderers) api.call(api.method("EntityRenderHelper","SetVisibleByRenderer","UnityEngine.Renderer|System.Boolean","System.Void","Gameplay.Beyond.dll","Beyond.Gameplay.View"),nativeRenderHelper,{saved.object,&hidden});
                auto* modelGo=api.call(api.method("BaseModelComponent","GetModelGo","","UnityEngine.GameObject","Gameplay.Beyond.dll","Beyond.Gameplay.View"),model);
                if(modelGo&&modelGo!=object) {
                    visualModelWasActive=api.value<bool>(api.method("GameObject","get_activeSelf","","System.Boolean"),modelGo);
                    visualModelHandle=api.raw()->gchandle_new(api.raw()->context,modelGo,0);
                    if(!visualModelHandle) throw std::runtime_error("visual model root allocation failed");
                    visualModel=modelGo;
                }
            }
            characterHidden=!show;hideAttempts=0;
            hiddenRendererCount=show?0:count;hideError.clear();
        } catch(const std::exception& e) {
            const std::string reason=e.what();setCharacterVisible(true);hideError=reason;
            if(++hideAttempts>=5) hideFailed=true;
        }
    }
    /**
     * Empties the render ring. This must run on every exit path, not only while gameplay is
     * active: Minecraft blocks its render thread inside writeRender once the 64 MB ring fills,
     * so a bridge that stopped draining froze the whole Minecraft client. Messages go to the
     * renderer while it is live and are counted as dropped otherwise; either way they leave the
     * ring.
     */
    void drain(unity::V3 at) noexcept {
        if(!memory.data) return;
        try {
            const bool consume=renderer.ready;
            std::vector<unsigned char> newestAvatar,newestScene;
            unsigned type;unsigned processed=0;std::size_t budget=kRenderByteBudget;
            while(processed<kMaxRenderMessages&&memory.render(type,payload)) {
                if(consume) {
                    try {if(type==proto::kRenAvatar) newestAvatar=payload;else if(type==proto::kRenScene) newestScene=payload;else renderer.message(type,payload,at);}
                    catch(const std::exception& e) {renderError=e.what();++droppedMessages;}
                } else ++droppedMessages;
                ++processed;
                const std::size_t used=payload.size()+8;
                if(used>=budget) break;
                budget-=used;
            }
            if(consume&&!newestAvatar.empty()) renderer.message(proto::kRenAvatar,newestAvatar,at);
            if(consume&&!newestScene.empty()) renderer.message(proto::kRenScene,newestScene,at);
        } catch(const std::exception& e) {renderError=e.what();}
    }
    struct RingDrain {
        GameplayBridge* owner;unity::V3 at;bool hide=false;
        // Feeding a mesh message to the renderer activates its section, so while the bridge is
        // idle the drain has to be followed by hiding everything again.
        ~RingDrain() {owner->drain(at);if(hide) owner->renderer.visible(false);}
    };
    void tick(const BE_HostApiV1* host,void* character,void* movement,unity::V3 where,bool valid,bool living,bool cinematic) {
        exclusiveGate.store(false,std::memory_order_release);
        std::lock_guard lock(mutex);
        try {
            inputMask.bind(host);
            if(!memory.open()) return;
            RingDrain guard{this,mcOrigin};
            if(!requested.load()||!valid||!living||cinematic||hostTeleporting.load()||GetTickCount64()<hostTeleportSettleUntil.load()||!character||!movement) {
                hostAuthority.suspend();
                rawX.exchange(0);rawY.exchange(0);lookInitialized=false;
                inputMask.release();
                if(active) {input(false);cursor(false);renderer.visible(false);setCharacterVisible(true);combat.stop(memory);active=false;}
                guard.hide=true;
                proto::SkyState state{};state.flags=proto::kSkyLoading|proto::kSkyMenuOpen;state.worldId=epoch;state.collisionEpoch=epoch;
                memory.sky(state);return;
            }
            api.bind(host);
            combat.bind(host);
            if(!initialized) {
                epoch=std::uint32_t(GetTickCount64())|1u;teleport=epoch;
                origin=suppliedAnchor?initialHost:where;safeHost=where;characterId=reinterpret_cast<std::uintptr_t>(character);
                // Reuse the saved world anchor but start at the actual host feet,
                // never at a stale guest pose from a previous game session.
                teleportPosition=toMc(where);
                renderer.init(host,origin);initialized=true;
                // The game's RaycastHit has ECS fields; get its actual value size rather than using stock Unity size.
                auto cls=api.klass("RaycastHit","UnityEngine.PhysicsModule.dll");
                const auto valueSize=reinterpret_cast<int(*)(const void*,unsigned*)>(GetProcAddress(GetModuleHandleW(L"GameAssembly.dll"),"il2cpp_class_value_size"));
                unsigned alignment=0;if(!valueSize||valueSize(cls.class_info,&alignment)>512) throw std::runtime_error("RaycastHit ABI unsupported");
                memory.collision(proto::kColClear,&epoch,sizeof(epoch));
            }
            // Party switches and host respawns replace the body without replacing the world.
            // Preserve the world anchor and seed the guest at the new body's real feet.
            if(characterId!=reinterpret_cast<std::uintptr_t>(character)) {
                lookInitialized=false;
                inputMask.release();
                // A new scene means a new body: show the old one again and let the next attempt
                // re-resolve the renderers instead of carrying a stale failure forward.
                setCharacterVisible(true);characterHidden=false;hideFailed=false;hideAttempts=0;
                input(false);renderer.visible(false);
                safeHost=where;teleportPosition=toMc(where);terrainIndex=4;terrainCenterX=terrainCenterZ=INT_MIN;characterId=reinterpret_cast<std::uintptr_t>(character);
                characterObject=character;holdStarted=0;
                hostAuthority.reset();
                ++epoch;teleport=epoch;guestReady=false;
                memory.collision(proto::kColClear,&epoch,sizeof(epoch));
                error.clear();
            }
            active=true;++frames;
            if(rejoin.exchange(false)) {++teleport;guestReady=false;error.clear();}
            if(!queuedShader.empty()&&renderer.ready) {renderer.configureShader(queuedShader);queuedShader.clear();}
            characterObject=character;
            proto::McState protectionState{};
            if(memory.mc(protectionState)) combat.protect(character,(protectionState.flags&proto::kMcInWorld)&&(protectionState.flags&proto::kMcInvulnerable));
            else combat.releaseProtection();
            DWORD foreground=0;auto window=GetForegroundWindow();GetWindowThreadProcessId(window,&foreground);
            const bool focus=foreground==GetCurrentProcessId();input(focus);
            auto* camera=api.call(api.method("Camera","get_main","","UnityEngine.Camera"));
            if(!camera) return;
            hostMenu=api.value<int>(api.method("Camera","get_cullingMask","","System.Int32"),camera)==0;
            if(hostMenu) {
                hostAuthority.suspend();
                rawX.exchange(0);rawY.exchange(0);lookInitialized=false;
                inputMask.release();
                combat.stop(memory,false);
                input(false);cursor(false);renderer.visible(false);setCharacterVisible(true);
                guestReady=false;guard.hide=true;
                proto::SkyState paused{};paused.flags=proto::kSkyLoading|proto::kSkyMenuOpen;paused.worldId=epoch;paused.collisionEpoch=epoch;
                memory.sky(paused);return;
            }
            if(hostAuthority.observe(where)) {
                ++hostTeleports;recovery.store(true);lookInitialized=false;
                ++epoch;memory.collision(proto::kColClear,&epoch,sizeof(epoch));
            }
            auto* transform=api.transform(camera,true);
            auto rotation=api.value<unity::Quaternion>(api.method("Transform","get_rotation","","UnityEngine.Quaternion"),transform);
            unity::V3 forward{2*(rotation.x*rotation.z+rotation.w*rotation.y),2*(rotation.y*rotation.z-rotation.w*rotation.x),1-2*(rotation.x*rotation.x+rotation.y*rotation.y)};
            if(!lookInitialized) {
                lookAngles.yaw=std::atan2(forward.x,forward.z)*180/3.14159265f;
                lookAngles.pitch=-std::asin(std::clamp(forward.y,-1.f,1.f))*180/3.14159265f;lookInitialized=true;
            }
            auto dx=rawX.exchange(0),dy=rawY.exchange(0);
            if(focus&&!(lastMc.flags&proto::kMcScreenOpen)) lookAngles.mouse(dx,dy);
            auto lookForward=lookAngles.forward();forward={lookForward[0],lookForward[1],lookForward[2]};
            proto::SkyState state{};state.flags=proto::kSkyInGame|(focus?0u:proto::kSkyMenuOpen);state.worldId=epoch;state.collisionEpoch=epoch;
            state.posX=teleportPosition.x;state.posY=teleportPosition.y;state.posZ=teleportPosition.z;state.teleportSeq=teleport;
            state.yaw=std::remainder(lookAngles.yaw-180.f,360.f);state.pitch=lookAngles.pitch;
            const int screenW=api.value<int>(api.method("Screen","get_width","","System.Int32"));
            const int screenH=api.value<int>(api.method("Screen","get_height","","System.Int32"));
            if(screenW>0&&screenH>0) {
                const double factor=(std::min)({1.0,double(proto::kMaxOverlayW)/screenW,double(proto::kMaxOverlayH)/screenH});
                viewportW=(std::max)(1,int(screenW*factor));viewportH=(std::max)(1,int(screenH*factor));
            }
            state.viewportW=viewportW;state.viewportH=viewportH;state.gameHour=12;memory.sky(state);
            proto::McState mc{};
            const bool fresh=memory.mc(mc);
            const bool mcDead=fresh&&(mc.flags&proto::kMcDead);
            const bool mcRecover=fresh&&(mc.flags&proto::kMcRecover);
            if(mcRecover&&!mcRecoveryHeld) recovery.store(true);
            mcRecoveryHeld=mcRecover;
            if(mcDead&&!mcWasDead) recovery.store(true);
            mcWasDead=mcDead;
            bool ready=fresh&&(mc.flags&proto::kMcInWorld)&&!mcDead&&mc.teleportAck==teleport;
            if(!ready&&fresh&&!mcDead&&(mc.flags&proto::kMcInWorld)) {
                if(!holdStarted) holdStarted=GetTickCount64();
                if(GetTickCount64()-holdStarted>5000) {recovery.store(true);holdStarted=GetTickCount64();}
            } else holdStarted=0;
            if(recovery.exchange(false)) {
                unity::V3 floor{},at=where;at.y+=.75f;
                {HostTerrainQuery query(renderer);if(ray(movement,at,floor)&&where.y>=floor.y-.75f&&where.y-floor.y<2.5f) where.y=(std::max)(where.y,floor.y+.02f);}
                safeHost=where;teleportPosition=toMc(where);++teleport;++recoveryCount;ready=false;
                hideFailed=false;hideAttempts=0;setCharacterVisible(true);
                terrainIndex=4;terrainCenterX=terrainCenterZ=INT_MIN;
                state.posX=teleportPosition.x;state.posY=teleportPosition.y;state.posZ=teleportPosition.z;state.teleportSeq=teleport;memory.sky(state);
                memory.input({proto::kInReleaseAll,0,0,0,0});
            }
            lastMc=mc;guestReady=ready;
            exclusiveGate.store(ready&&focus&&inputMode.exclusive(),std::memory_order_release);
            if(ready&&inputMode.exclusive()) inputMask.apply(character);else inputMask.release();
            if(ready&&!characterHidden&&!hideFailed) setCharacterVisible(false);
            if(!ready&&characterHidden) setCharacterVisible(true);
            cursor(ready&&(mc.flags&proto::kMcScreenOpen)&&focus);
            unity::V3 mcPosition=ready?unity::V3{float(mc.x),float(mc.y),float(mc.z)}:teleportPosition;
            guard.at=mcPosition;
            if(GetTickCount64()-lastTerrain>120) {terrain(movement,mcPosition,where);lastTerrain=GetTickCount64();}
            const bool poseOk=std::isfinite(mc.x)&&std::isfinite(mc.y)&&std::isfinite(mc.z)
                &&std::isfinite(mc.eyeX)&&std::isfinite(mc.eyeY)&&std::isfinite(mc.eyeZ);
            if(ready&&!poseOk) {
                // Half-published state while a teleport is in flight. Skipping one frame beats
                // tearing the bridge down for the rest of the session.
                ++badPoses;
            }
            if(ready&&poseOk) {
                auto desired=toHost(mcPosition);
                unity::V3 floor{};auto test=desired;test.y=desired.y+.75f;
                bool measured=false;
                {HostTerrainQuery query(renderer);measured=ray(movement,test,floor);}
                if((measured&&desired.y<floor.y-.75f)
                   ||(!measured&&desired.y<safeHost.y-3.f)) {
                    ++safetyStops;
                    lastRecoveryReason=(mc.flags&proto::kMcDead)?"guest_dead":(measured?"below_measured_floor":"unmeasured_drop");
                    bool warning=false;
                    api.call(api.method("MovementComponent","TeleportTo","UnityEngine.Vector3|System.Boolean","System.Void",gameAssembly,gameSpace),movement,{&safeHost,&warning});
                    hostAuthority.commanded(safeHost);
                    // Recover both processes together instead of disabling the bridge and
                    // leaving Minecraft frozen below the playable world for the session.
                    recovery.store(true);guestReady=false;
                    memory.input({proto::kInReleaseAll,0,0,0,0});
                    std::fill(std::begin(keys),std::end(keys),false);std::fill(std::begin(buttons),std::end(buttons),false);
                    inputMask.release();combat.stop(memory);guard.hide=true;return;
                }
                if(measured&&desired.y>=floor.y-.1f&&desired.y-floor.y<1.f) safeHost=desired;
                // Hold the body on Minecraft's player whenever Minecraft is in the world, not only
                // while the game window has focus and no screen is open. Nothing else holds it up,
                // so every skipped frame was a frame of free fall: opening the inventory or
                // alt-tabbing dropped the character out of the world for good.
                auto delta=distance(desired,where);
                // A wide gap is a teleport, not a fault. Throwing here used to disable the
                // bridge permanently, which also stopped the drain and froze Minecraft.
                if(delta>kResyncDistance) ++resyncs;
                if(delta>.0001f) {
                    bool showWarning=false;
                    api.call(api.method("MovementComponent","TeleportTo","UnityEngine.Vector3|System.Boolean","System.Void",gameAssembly,gameSpace),movement,{&desired,&showWarning});++moves;
                }
                hostAuthority.commanded(desired);
            }
            // Keep scene matrices aligned while the host is in the background as well.
            // Input focus is independent of the camera's world transform.
            if(ready&&poseOk) {
                auto eye=toHost({float(mc.eyeX),float(mc.eyeY),float(mc.eyeZ)});
                cameraDistanceUsed=0;
                if(mc.cameraMode==1||mc.cameraMode==2) {
                    float sign=mc.cameraMode==1?-1.f:1.f;
                    unity::V3 boom{forward.x*sign,forward.y*sign,forward.z*sign};
                    {HostTerrainQuery query(renderer);cameraDistanceUsed=cameraBoom(movement,eye,boom,mc.cameraDistance);}
                    eye.x+=boom.x*cameraDistanceUsed;eye.y+=boom.y*cameraDistanceUsed;eye.z+=boom.z*cameraDistanceUsed;
                }
                api.setPosition(transform,eye);
                auto q=lookAngles.rotation(mc.cameraMode==2);unity::Quaternion pose{q[0],q[1],q[2],q[3]};
                api.call(api.method("Transform","set_rotation","UnityEngine.Quaternion"),transform,{&pose});
                if(std::isfinite(mc.fovDeg)&&mc.fovDeg>10&&mc.fovDeg<150) api.call(api.method("Camera","set_fieldOfView","System.Single"),camera,{&mc.fovDeg});
            }
            if(renderer.ready) renderer.overlay(memory);
            if(ready&&poseOk) combat.tick(character,where,origin,mcOrigin,memory);
            else combat.stopPassengers();
            if(renderer.ready&&frames%60==0) visibleMeshes=renderer.visibleMeshes();
        } catch(const std::exception& e) {
            inputMask.release();combat.stop(memory);
            error=e.what();requested.store(false);if(memory.data) {input(false);proto::SkyState state{};state.flags=proto::kSkyLoading|proto::kSkyMenuOpen;memory.sky(state);}
            if(renderer.ready) renderer.visible(false);active=false;setCharacterVisible(true);
            try {cursor(false);} catch(...) {}
        }
    }
    nlohmann::json snapshot() {
        std::lock_guard lock(mutex);
        return {{"requested",requested.load()},{"active",active},{"initialized",initialized},{"error",error},{"frames",frames},{"host_moves",moves},
            {"hud_frames",renderer.hudFrames},{"section_meshes",renderer.meshMessages},{"avatar_frames",renderer.avatarFrames},{"scene_frames",renderer.sceneFrames},{"scene_vertices",renderer.sceneVertices},{"shader",renderer.shaderName},
            {"host_teleports",hostTeleports},{"host_teleporting",hostTeleporting.load()},{"recoveries",recoveryCount},{"resyncs",resyncs},{"bad_poses",badPoses},{"dropped_render_messages",droppedMessages},{"character_hidden",characterHidden},
            {"render_error",renderError},{"character_hide_error",hideError},{"guest_ready",guestReady},
            {"hidden_renderer_count",hiddenRendererCount},
            {"visual_model_hidden",nativeRenderHelper!=nullptr},
            {"native_animation_model_kept_active",visualModel!=nullptr&&visualModelWasActive},
            {"host_input_mask",inputMask.snapshot()},
            {"mc_exclusive_hotkeys",inputMode.exclusive()},{"native_hotkeys_suppressed",exclusiveGate.load()},
            {"native_keyboard_hooks",nativeKeyHooks},{"suppressed_native_key_queries",suppressedNativeKeys.load()},
            {"transparency",renderer.transparencyState()},
            {"suppressed_native_binding_updates",suppressedNativeBindings.load()},
            {"scroll_error",scrollError},
            {"pipeline_frames",pipelineFrames},{"pipeline_draw_commands",pipelineDraws},{"pipeline_error",pipelineError},
            {"frame_captured",captured&&captureError.empty()},{"capture_error",captureError},
            {"shader_supported",renderer.shaderSupported},
            {"raw_mouse_hook",rawReady.load()},{"look_yaw_degrees",lookAngles.yaw},{"look_pitch_degrees",lookAngles.pitch},
            {"independent_mc_depth",renderer.independentDepth()},
            {"raw_mouse_packets",rawPackets.load()},
            {"requested_hud_resolution",{viewportW,viewportH}},
            {"guest_fall_flying",bool(lastMc.flags&proto::kMcFallFlying)},
            {"combat",combat.snapshot()},
            {"renderer_probe",renderer.probeResult},{"native_rendering",renderer.nativeRendering},{"host_camera_distance",cameraDistanceUsed},
            {"world_render_path",renderer.nativeRendering?"native_hgrp_scene":"independent_depth_overlay"},
            {"host_menu",hostMenu},
            {"terrain_samples",terrainSamples},{"terrain_ray_hits",terrainHits},{"terrain_triangles",terrainTriangles},{"safety_stops",safetyStops},{"last_recovery_reason",lastRecoveryReason},
            {"avatar_vertices",renderer.avatarVertices},{"loaded_sections",renderer.sectionCount()},{"loaded_textures",renderer.textureCount()},
            {"visible_meshes",visibleMeshes},{"world_layer",renderer.layer()},{"camera_culling_mask",renderer.mask()},
            {"world_epoch",epoch},{"guest_camera_mode",lastMc.cameraMode},{"guest_camera_distance",lastMc.cameraDistance},
            {"guest_position",{lastMc.x,lastMc.y,lastMc.z}},
            {"origin_raw_units",{origin.x,origin.y,origin.z}},{"terrain_mode","sampled_height_field"}};
    }
};
}
