#include "mesh_layers.h"
#include <iostream>
int main() {
    using namespace endcraft;
    alpha::Vertex vertices[9]{};
    for(unsigned i=0;i<9;++i) {vertices[i].color=i<6?0xffffffffu:0xb3ff663fu;vertices[i].flags=i<3?1:i<6?2:2|layers::kFluid;}
    auto groups=layers::split(vertices,9);
    if(groups.solid.size()!=3||groups.blended.size()!=2||groups.blended.at({1,0xffffffffu}).size()!=3||groups.blended.at({2,0xb3ff663fu}).size()!=3) return 1;
    alpha::Texture tex{4,3,std::vector<unsigned char>(48,255)};
    unsigned char region[16]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16};
    layers::patch(tex,{1,1,2,2},region,16);
    if(tex.rgba[20]!=1||tex.rgba[27]!=8||tex.rgba[36]!=9||tex.rgba[43]!=16||tex.rgba[16]!=255||tex.rgba[28]!=255) return 2;
    try {layers::patch(tex,{3,1,2,2},region,16);return 3;} catch(const std::runtime_error&) {}
    try {layers::patch(tex,{1,1,2,2},region,15);return 4;} catch(const std::runtime_error&) {}
    std::cout<<"glass/fluid layer and tint separation / atlas animation rows / bounds: PASS\n";
}
