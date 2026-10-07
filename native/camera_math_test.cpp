#include "camera_math.h"
#include <iostream>
int main() {
    endcraft::CameraAngles a;a.mouse(900,-900);
    auto f=a.forward();if(std::abs(f[1]-1)>1e-5) return 1;
    a.mouse(0,1800);f=a.forward();if(std::abs(f[1]+1)>1e-5) return 2;
    a.mouse(0,-900);f=a.forward();if(std::abs(f[0]-1)>1e-5||std::abs(f[2])>1e-5) return 3;
    auto q=a.rotation(true);float fx=2*(q[0]*q[2]+q[3]*q[1]);if(std::abs(fx+1)>1e-5) return 4;
    a.mouse(0,100000);if(a.pitch!=90) return 5;
    std::cout<<"camera vertical endpoints / yaw / front view / mouse pitch bounds PASS\n";
}
