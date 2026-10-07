#pragma once
#include <array>
#include <cmath>
#include <algorithm>
namespace endcraft {
struct CameraAngles {
    float yaw=0,pitch=0;
    void mouse(long x,long y,float scale=.10f) {
        yaw=std::remainder(yaw+float(x)*scale,360.f);
        pitch=std::clamp(pitch+float(y)*scale,-90.f,90.f);
    }
    std::array<float,3> forward() const {
        constexpr float rad=3.141592653589793f/180;
        float p=pitch*rad,y=yaw*rad;
        return {std::sin(y)*std::cos(p),-std::sin(p),std::cos(y)*std::cos(p)};
    }
    std::array<float,4> rotation(bool front=false) const {
        constexpr float half=3.141592653589793f/360;
        float p=(front?-pitch:pitch)*half,y=(yaw+(front?180:0))*half;
        return {std::sin(p)*std::cos(y),std::cos(p)*std::sin(y),-std::sin(p)*std::sin(y),std::cos(p)*std::cos(y)};
    }
};
}
