#include "coordinate_map.h"
#include <stdexcept>
#include <iostream>
struct V {float x,y,z;};
void check(bool v) {if(!v) throw std::runtime_error("coordinate mapping mismatch");}
bool near(float a,float b) {return std::abs(a-b)<.0001f;}
int main() {
    using namespace endcraft::coordinates;
    V origin{-365,235,1333},anchor{.5f,64,.5f},p{4,67,-8};
    auto round=toMc(origin,anchor,toHost(origin,anchor,p));
    check(near(round.x,p.x)&&near(round.y,p.y)&&near(round.z,p.z));
    for(V f:{V{0,0,1},V{1,0,0},V{0,0,-1},V{-1,0,0},V{.6f,0,.8f}}) {
        float yaw=yawRadians(f.x,f.z);
        V mcForward{-std::sin(yaw),0,std::cos(yaw)},mcRight{-std::cos(yaw),0,-std::sin(yaw)};
        auto forward=reflect(mcForward),right=reflect(mcRight);
        check(near(forward.x,f.x)&&near(forward.z,f.z));
        check(near(right.x,f.z)&&near(right.z,-f.x));
    }
    std::cout<<"coordinate round-trip / forward / strafe directions: PASS\n";
}
