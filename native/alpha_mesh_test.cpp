#include <stdexcept>
#include "alpha_mesh.h"
#include <iostream>
#include <random>
#include <cstring>
#include <chrono>
namespace endcraft::alpha {
inline void referenceTriangle(const Texture& tex,const Vertex* v,std::vector<Vertex>& out) {
    if(!tex.width||!tex.height||tex.rgba.size()!=std::size_t(tex.width)*tex.height*4) throw std::runtime_error("alpha texture size mismatch");
    float u0=std::clamp((std::min)({v[0].u,v[1].u,v[2].u}),0.f,1.f),u1=std::clamp((std::max)({v[0].u,v[1].u,v[2].u}),0.f,1.f);
    float w0=std::clamp((std::min)({v[0].v,v[1].v,v[2].v}),0.f,1.f),w1=std::clamp((std::max)({v[0].v,v[1].v,v[2].v}),0.f,1.f);
    int x0=std::clamp(int(std::floor(u0*tex.width)),0,int(tex.width)-1),x1=std::clamp(int(std::ceil(u1*tex.width)),x0+1,int(tex.width));
    int y0=std::clamp(int(std::floor(w0*tex.height)),0,int(tex.height)-1),y1=std::clamp(int(std::ceil(w1*tex.height)),y0+1,int(tex.height));
    auto solid=[&](int x,int y){return tex.rgba[(std::size_t(y)*tex.width+x)*4+3]>=128;};
    bool all=true,any=false;for(int y=y0;y<y1;++y) for(int x=x0;x<x1;++x) {bool s=solid(x,y);all&=s;any|=s;}
    if(all) {out.insert(out.end(),v,v+3);return;}
    if(!any) return;
    float area=(v[1].u-v[0].u)*(v[2].v-v[0].v)-(v[1].v-v[0].v)*(v[2].u-v[0].u);
    if(std::abs(area)<1e-12f) {if(solid(x0,y0)) out.insert(out.end(),v,v+3);return;}
    std::vector<Rect> rects;
    for(int y=y0;y<y1;++y) for(int x=x0;x<x1;) {
        if(!solid(x,y)) {++x;continue;}int start=x;while(x<x1&&solid(x,y)) ++x;
        auto found=std::find_if(rects.begin(),rects.end(),[&](const Rect& r){return r.left==start&&r.right==x&&r.top==y;});
        if(found!=rects.end()) found->top=y+1;else rects.push_back({start,x,y,y+1});
    }
    for(auto r:rects) {
        std::vector<Vertex> poly(v,v+3);
        poly=plane(poly,float(r.left)/tex.width,true,true);poly=plane(poly,float(r.right)/tex.width,true,false);
        poly=plane(poly,float(r.bottom)/tex.height,false,true);poly=plane(poly,float(r.top)/tex.height,false,false);
        for(std::size_t i=1;i+1<poly.size();++i) {
            float a=(poly[i].u-poly[0].u)*(poly[i+1].v-poly[0].v)-(poly[i].v-poly[0].v)*(poly[i+1].u-poly[0].u);
            if(std::abs(a)>1e-12f) {out.push_back(poly[0]);out.push_back(poly[i]);out.push_back(poly[i+1]);}
        }
    }
}
}
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
    std::mt19937 random(731);
    std::vector<Rect> workspace;
    for(unsigned sample=0;sample<2000;++sample) {
        unsigned width=1+random()%16,height=1+random()%16;
        Texture check{width,height,std::vector<unsigned char>(width*height*4,255)};
        for(std::size_t i=3;i<check.rgba.size();i+=4) check.rgba[i]=static_cast<unsigned char>(random()%256);
        Vertex vertices[3]{};
        for(auto& vertex:vertices) {
            vertex.u=float(int(random()%25)-4)/16;vertex.v=float(int(random()%25)-4)/16;
            vertex.x=vertex.u*3;vertex.y=vertex.v*2;vertex.color=random();
        }
        std::vector<Vertex> expected,actual;
        referenceTriangle(check,vertices,expected);triangle(check,vertices,actual,&workspace);
        if(expected.size()!=actual.size()||(!expected.empty()&&std::memcmp(expected.data(),actual.data(),expected.size()*sizeof(Vertex)))) return 5;
    }
    Texture bench{16,16,std::vector<unsigned char>(16*16*4,255)};
    for(unsigned y=0;y<16;++y)for(unsigned x=0;x<16;++x) bench.rgba[(y*16+x)*4+3]=(x+y)%2?0:255;
    auto measure=[&](bool optimized) {
        std::vector<Vertex> result;auto start=std::chrono::steady_clock::now();
        for(unsigned i=0;i<1000;++i) {result.clear();if(optimized) triangle(bench,tri,result,&workspace);else referenceTriangle(bench,tri,result);}
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
    };
    measure(false);measure(true);
    const auto before=measure(false),after=measure(true);
    std::cout<<"alpha clipping: opaque / holes / area / empty / 2000 exact reference comparisons PASS\n";
    std::cout<<"checkerboard clipping 1000 triangles: reference="<<before<<"ms optimized="<<after<<"ms\n";
}
