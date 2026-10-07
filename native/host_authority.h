#pragma once
#include <cmath>
namespace endcraft {
template<class V> class HostAuthority {
    V last{};bool known=false,paused=false;
public:
    void suspend() {paused=true;}
    void reset() {known=false;}
    void commanded(V at) {last=at;known=true;}
    bool observe(V at) {
        if(!std::isfinite(at.x)||!std::isfinite(at.y)||!std::isfinite(at.z)) return false;
        const bool resumed=paused;paused=false;
        const double dx=at.x-last.x,dy=at.y-last.y,dz=at.z-last.z;
        const bool moved=known&&dx*dx+dy*dy+dz*dz>64;
        last=at;known=true;
        return resumed||moved;
    }
};
}
