#pragma once
#include "../protocol/endcraft_protocol.h"
#include <algorithm>
#include <cmath>
#include <vector>
#include <stdexcept>
namespace endcraft::alpha {
using Vertex=skycraft::proto::RenVertex;
struct Texture {unsigned width=0,height=0;std::vector<unsigned char> rgba;};
struct Rect {int left,right,bottom,top;};
inline Vertex lerp(const Vertex& a,const Vertex& b,float t) {
    auto r=a;
    r.x+=t*(b.x-a.x);r.y+=t*(b.y-a.y);r.z+=t*(b.z-a.z);
    r.u+=t*(b.u-a.u);r.v+=t*(b.v-a.v);
    r.color=0;for(unsigned i=0;i<4;++i) {
        auto va=float((a.color>>(i*8))&255),vb=float((b.color>>(i*8))&255);
        r.color|=unsigned(std::clamp(va+t*(vb-va),0.f,255.f))<<(i*8);
    }
    return r;
}
inline std::vector<Vertex> plane(const std::vector<Vertex>& in,float bound,bool u,bool lower) {
    std::vector<Vertex> out;if(in.empty()) return out;
    auto coord=[&](const Vertex& v){return u?v.u:v.v;};
    for(std::size_t i=0;i<in.size();++i) {
        auto& a=in[i];auto& b=in[(i+1)%in.size()];
        float ac=coord(a),bc=coord(b);
        bool ai=lower?ac>=bound:ac<=bound,bi=lower?bc>=bound:bc<=bound;
        if(ai) out.push_back(a);
        if(ai!=bi) out.push_back(lerp(a,b,(bound-ac)/(bc-ac)));
    }
    return out;
}
// Turn transparent texels into actual holes so the available opaque shader can
// write correct character depth. Point-filtered textures keep the same cutoff.
inline void triangle(const Texture& tex,const Vertex* v,std::vector<Vertex>& out) {
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
