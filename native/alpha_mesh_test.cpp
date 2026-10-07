#include <stdexcept>
#include "alpha_mesh.h"
#include <iostream>
int main() {
    using namespace endcraft::alpha;
    Texture tex{2,2,std::vector<unsigned char>(16,255)};
    Vertex tri[3]{};tri[0].u=0;tri[0].v=0;tri[1].u=1;tri[1].v=0;tri[2].u=0;tri[2].v=1;
    for(auto& v:tri) {v.x=v.u;v.z=v.v;v.color=0xffffffff;}
    std::vector<Vertex> out;triangle(tex,tri,out);
    if(out.size()!=3) return 1;
    tex.rgba[3]=0;out.clear();triangle(tex,tri,out);
    double area=0;
    for(std::size_t i=0;i<out.size();i+=3) {
        auto a=out[i],b=out[i+1],c=out[i+2];float u=(a.u+b.u+c.u)/3,v=(a.v+b.v+c.v)/3;
        if(u<.5f&&v<.5f) return 2;
        area+=std::abs((b.u-a.u)*(c.v-a.v)-(b.v-a.v)*(c.u-a.u))*.5;
    }
    if(std::abs(area-.25)>1e-6) return 3;
    for(std::size_t i=3;i<tex.rgba.size();i+=4) tex.rgba[i]=0;
    out.clear();triangle(tex,tri,out);if(!out.empty()) return 4;
    std::cout<<"alpha clipping: opaque preservation / transparent holes / area / empty output PASS\n";
}
