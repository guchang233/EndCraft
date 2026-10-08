#include "input_mode.h"
#include <cassert>
#include <iostream>
int main() {
    endcraft::InputMode mode;
    assert(mode.exclusive());
    assert(mode.poll(true,true)&&!mode.exclusive());
    assert(!mode.poll(true,true)&&!mode.exclusive());
    assert(!mode.poll(true,false));
    assert(mode.poll(true,true)&&mode.exclusive());
    assert(!mode.poll(false,true)&&mode.exclusive());
    assert(!mode.poll(true,false));
    assert(mode.poll(true,true)&&!mode.exclusive());
    std::cout<<"semicolon edge / repeat / focus tests: PASS\n";
}
