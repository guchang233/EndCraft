#include "bridge_memory.h"
#include <array>
#include <iostream>
void check(bool okay,const char* message) {if(!okay) throw std::runtime_error(message);}
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
        std::cout<<"render ring wrap / single consumption / corrupt payload checks: PASS\n";
    } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';return 1;}
}
