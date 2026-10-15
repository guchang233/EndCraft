#include "combat_damage.h"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>

int main() {
    using namespace endcraft::combat_damage;
    const double hit=amount(7,500);
    assert(hit==700);
    // The same sword hit has the same absolute damage; native HP sets durability.
    assert(std::ceil(1400/hit)==2);
    assert(std::ceil(14000/hit)==20);
    assert(std::ceil(140000/hit)==200);
    assert(amount(10.5,500)==hit*1.5); // MC already included the critical multiplier
    assert(amount(7,1000)==hit*2);
    assert(amount(7,500,2)==hit*2);
    assert(amount(20,500)==2000); // projectile / explosive amounts use the same units
    assert(amount(20000,500)==amount(maxMcDamage,500));
    const double nan=std::numeric_limits<double>::quiet_NaN(),inf=std::numeric_limits<double>::infinity();
    for(double invalid:{0.,-1.,nan,inf,-inf}) {
        assert(amount(invalid,500)==0);
        assert(amount(7,invalid)==0);
        assert(!validMultiplier(invalid));
        assert(amount(7,500,invalid)==0);
    }
    assert(validMultiplier(.01)&&validMultiplier(100));
    assert(!validMultiplier(.009)&&!validMultiplier(101));
    assert(amount(10000,std::numeric_limits<double>::max(),100)==0); // overflow fails closed
    std::cout<<"damage balance: native HP durability / weapons / crit / character attack / scaling / invalid inputs: PASS\n";
}
