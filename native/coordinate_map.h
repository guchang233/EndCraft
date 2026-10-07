#pragma once
#include <cmath>
namespace endcraft::coordinates {
template<class V> V reflect(V p) {return {p.x,p.y,-p.z};}
template<class V> V toHost(V hostOrigin,V mcOrigin,V p) {
    auto delta=reflect(V{p.x-mcOrigin.x,p.y-mcOrigin.y,p.z-mcOrigin.z});
    return {hostOrigin.x+delta.x,hostOrigin.y+delta.y,hostOrigin.z+delta.z};
}
template<class V> V toMc(V hostOrigin,V mcOrigin,V p) {
    auto delta=reflect(V{p.x-hostOrigin.x,p.y-hostOrigin.y,p.z-hostOrigin.z});
    return {mcOrigin.x+delta.x,mcOrigin.y+delta.y,mcOrigin.z+delta.z};
}
inline float yawRadians(float forwardX,float forwardZ) {return -std::atan2(forwardX,-forwardZ);}
}
