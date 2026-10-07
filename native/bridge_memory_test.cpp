#include "bridge_memory.h"
#include <array>
#include <iostream>
void check(bool okay,const char* message) {if(!okay) throw std::runtime_error(message);}
struct TestMapping {
    endcraft::BridgeMemory bridge;
    std::vector<std::uint64_t> bytes;
    TestMapping():bytes((endcraft::proto::kOffEventRing+0x80+sizeof(endcraft::proto::McEvent)*endcraft::proto::kEventRingEntries+7)/8) {
        bridge.data=reinterpret_cast<unsigned char*>(bytes.data());
    }
    ~TestMapping() {bridge.data=nullptr;} // This test owns heap storage, never a mapped view.
};
int main() {
    try {
        alignas(64) std::array<unsigned char,256> ring{};
        std::uint32_t type=0;std::vector<unsigned char> payload;
        auto read=[&]{return endcraft::readRenderMessage(ring.data(),128,type,payload);};
        check(!read(),"empty ring");
        // A padding record at the end followed by a mesh at the beginning of the ring.
        endcraft::release64(ring.data()+0x40,120);endcraft::release64(ring.data(),144);
        std::uint32_t header[]={2,3};std::memcpy(ring.data()+0x80,header,8);
        std::memcpy(ring.data()+0x88,"MC!",3);
        check(!read()&&endcraft::acquire64(ring.data()+0x40)==128,"wrap padding advances tail");
        check(read()&&type==2&&payload.size()==3&&payload[2]=='!',"wrapped mesh copied");
        check(endcraft::acquire64(ring.data()+0x40)==144&&!read(),"message consumed once");
        endcraft::release64(ring.data(),272);
        header[1]=200;std::memcpy(ring.data()+0x80+16,header,8);
        bool rejected=false;try {read();}catch(const std::runtime_error&) {rejected=true;}
        check(rejected&&endcraft::acquire64(ring.data()+0x40)==144,"bad length never advances tail");
        endcraft::release64(ring.data(),273);rejected=false;
        try {read();}catch(const std::runtime_error&) {rejected=true;}
        check(rejected,"overwritten ring rejected");
        TestMapping test;
        auto* table=reinterpret_cast<endcraft::proto::ActorTable*>(test.bridge.data+endcraft::proto::kOffActorTable);
        std::vector<endcraft::proto::ActorRecord> actors(endcraft::proto::kMaxActors+1);
        for(unsigned i=0;i<actors.size();++i) actors[i].formId=i+1;
        test.bridge.actors(actors);
        check(table->count==endcraft::proto::kMaxActors&&(table->seq&1)==0,"actor table bounded and publication complete");
        check(table->actors[255].formId==256,"actor identifiers preserved");
        test.bridge.actors({});check(table->count==0&&(table->seq&1)==0,"inactive actors withdrawn");
        auto* events=test.bridge.data+endcraft::proto::kOffEventRing;
        endcraft::proto::McEvent hit{},received{};hit.type=endcraft::proto::kEvHitActor;hit.formId=73;hit.a=4;
        check(!test.bridge.event(received),"empty combat ring");
        auto tail=endcraft::proto::kEventRingEntries-1;
        endcraft::release64(events+0x40,tail);endcraft::release64(events,tail+2);
        std::memcpy(events+0x80+tail*sizeof(hit),&hit,sizeof(hit));
        hit.formId=74;std::memcpy(events+0x80,&hit,sizeof(hit));
        check(test.bridge.event(received)&&received.formId==73&&received.a==4,"last combat slot preserved");
        check(test.bridge.event(received)&&received.formId==74&&!test.bridge.event(received),"wrapped combat events consumed once");
        auto savedTail=endcraft::acquire64(events+0x40);
        endcraft::release64(events,savedTail+endcraft::proto::kEventRingEntries+1);rejected=false;
        try {test.bridge.event(received);}catch(const std::runtime_error&) {rejected=true;}
        check(rejected&&endcraft::acquire64(events+0x40)==savedTail,"overwritten combat ring rejected without dispatch");
        std::cout<<"actor bounds / withdrawal / combat ring wrap / single dispatch / corruption checks: PASS\n";
        std::cout<<"render ring wrap / single consumption / corrupt payload checks: PASS\n";
    } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
