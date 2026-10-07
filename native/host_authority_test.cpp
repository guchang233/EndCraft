#include "host_authority.h"
#include <cassert>
#include <limits>
struct V {float x,y,z;};
int main() {
    endcraft::HostAuthority<V> a;
    assert(!a.observe({100,50,100}));
    a.commanded({110,50,100});assert(!a.observe({110.1f,50,100}));
    // A host teleport wins even when the guest still publishes its old position.
    assert(a.observe({400,70,-200}));assert(!a.observe({400,70,-200}));
    a.suspend();assert(a.observe({400,70,-200}));assert(!a.observe({400,70,-200}));
    a.commanded({401,70,-200});assert(!a.observe({std::numeric_limits<float>::quiet_NaN(),70,-200}));
    assert(!a.observe({401,70,-200}));
}
