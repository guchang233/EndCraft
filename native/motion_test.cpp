#include "motion_probe.h"
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
using motion_probe::Vec;
Vec position{};
bool grounded=true,walkable=true;
unsigned writes=0;
int player,component;
enum Method {External=1,Position,Walkable,Grounded};
BE_Result BE_CALL resolve(void*,const BE_MethodDescriptorV1* descriptor,BE_ResolvedMethodV1* result) {
    const std::string name=descriptor->method_name;
    const auto id=name=="SetExternalPos"||name=="TeleportTo"?External:name=="get_position"?Position:
        name=="IsPositionWalkable"?Walkable:Grounded;
    result->method_info=reinterpret_cast<void*>(std::uintptr_t(id));return BE_Result_Ok;
}
void* BE_CALL invoke(void*,const void* method,void*,void** args,void** exception) {
    *exception=nullptr;
    switch(reinterpret_cast<std::uintptr_t>(method)) {
        case External:position=*static_cast<Vec*>(args[0]);++writes;return nullptr;
        case Position:return &position;
        case Walkable:return &walkable;
        case Grounded:return &grounded;
    }
    return nullptr;
}
void* BE_CALL unbox(void*,void* value) {return value;}
void check(bool value,const char* message) {if(!value) throw std::runtime_error(message);}
void tick(motion_probe::Probe& probe,Vec speed={}) {probe.tick(&player,&component,position,speed,true,true,false);}
}
int main() {
    try {
        BE_HostApiV1 api{};api.abi_version=1;api.resolve_method=resolve;api.runtime_invoke=invoke;api.object_unbox=unbox;
        motion_probe::Probe probe;
        check(probe.queue(nullptr,{1,0,0})==BE_Result_InvalidArgument,"Reject unbounded displacement before runtime access");
        check(probe.queue(nullptr,{.01f,.01f,0})==BE_Result_InvalidArgument,"Reject vertical motion");
        check(probe.queue(&api,{.05f,0,0})==BE_Result_Ok,"Queue");
        check(probe.queue(&api,{.05f,0,0})==BE_Result_Conflict,"Reject duplicate in-flight command");
        tick(probe);check(writes==1&&position.x==.05f,"Shift exactly once");
        for(unsigned i=0;i<8;++i) tick(probe);
        auto result=probe.snapshot();
        check(writes==2&&position.x==0&&result["state"]=="finished"&&result["restore_error_raw_units"]==0,"Restore after eight observations");
        check(result["samples"].size()==8,"Bound observation count");
        walkable=false;check(probe.queue(&api,{.05f,0,0})==BE_Result_Ok,"Requeue");tick(probe);
        check(writes==2&&probe.snapshot()["state"]=="rejected_position_not_walkable","Do not write unwalkable position");
        walkable=true;check(probe.queue(&api,{.05f,0,0})==BE_Result_Ok,"Requeue");tick(probe,{1,0,0});
        check(writes==2&&probe.snapshot()["state"]=="rejected_player_moving","Do not disturb moving player");
        check(probe.queue(&api,{.05f,0,0})==BE_Result_Ok,"Requeue");tick(probe);
        probe.tick(reinterpret_cast<void*>(std::uintptr_t(1)),&component,position,{},true,true,false);
        check(writes==3&&probe.snapshot()["state"]=="interrupted_character_changed","Do not restore a different character");
        position={};check(probe.queue(&api,{.05f,0,0})==BE_Result_Ok,"Requeue");tick(probe);
        position.x=2;
        for(unsigned i=0;i<8;++i) tick(probe);
        check(writes==4&&position.x==2&&probe.snapshot()["state"]=="restore_skipped_player_moved","Do not drag user back after movement");
        std::cout<<"bounded motion state machine tests: PASS (simulated runtime; not game evidence)\n";
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
