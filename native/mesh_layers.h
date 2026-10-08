#pragma once
#include "alpha_mesh.h"
#include <map>
#include <array>
#include <cstring>
namespace endcraft::layers {
using Vertex=alpha::Vertex;
constexpr unsigned kFluid=128;
using Key=std::array<unsigned,2>; // layer (1 blended block, 2 blended fluid, 3 opaque fluid), RGBA tint
struct Split {std::vector<Vertex> solid;std::map<Key,std::vector<Vertex>> blended;};
inline Split split(const Vertex* vertices,std::size_t count) {
    if(count>200000||count%3) throw std::runtime_error("mesh vertex count invalid");
    Split out;
    for(std::size_t i=0;i<count;i+=3) {
        const auto* triangle=vertices+i;
        if(!(triangle[0].flags&(2|kFluid))) {out.solid.insert(out.solid.end(),triangle,triangle+3);continue;}
        unsigned color=0;
        for(unsigned channel=0;channel<4;++channel) {
            unsigned sum=0;for(unsigned v=0;v<3;++v) sum+=(triangle[v].color>>(channel*8))&255;
            color|=((sum+1)/3)<<(channel*8);
        }
        auto& bucket=out.blended[{(triangle[0].flags&kFluid)?((triangle[0].flags&2)?2u:3u):1u,color}];
        bucket.insert(bucket.end(),triangle,triangle+3);
    }
    return out;
}
inline void patch(alpha::Texture& texture,const skycraft::proto::RenAtlasRegion& region,const unsigned char* bytes,std::size_t count) {
    if(!region.width||!region.height||region.x>texture.width||region.y>texture.height||region.width>texture.width-region.x||region.height>texture.height-region.y||
       std::uint64_t(region.width)*region.height*4!=count||texture.rgba.size()!=std::size_t(texture.width)*texture.height*4)
        throw std::runtime_error("animated atlas region invalid");
    for(unsigned row=0;row<region.height;++row)
        std::memcpy(texture.rgba.data()+((std::size_t(region.y)+row)*texture.width+region.x)*4,bytes+std::size_t(row)*region.width*4,std::size_t(region.width)*4);
}
}
