#include "input_mode.h"
#include "model_visibility_shortcut.h"
#include "menu_input_gate.h"
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
    endcraft::ModelVisibilityShortcut model;
    assert(!model.visible());
    assert(model.poll(true,true,true)&&model.visible());
    assert(!model.poll(true,true,true)&&model.visible());
    assert(!model.poll(true,false,true));
    assert(model.poll(true,true,true)&&!model.visible());
    assert(!model.poll(false,false,true));
    assert(!model.poll(false,true,true));
    assert(!model.poll(true,true,true)&&!model.visible());
    assert(!model.poll(true,false,true));
    assert(!model.poll(true,true,false));
    assert(!model.poll(true,true,true)&&!model.visible());
    assert(!model.poll(true,false,true));
    assert(model.poll(true,true,true)&&model.visible());
    assert(!model.poll(true,false,false)&&model.visible());
    endcraft::MenuInputGate ui;
    assert(!ui.suppress(true,false,true,false));
    assert(ui.suppress(true,true,true,true));
    assert(ui.suppress(true,true,true,false));
    assert(ui.suppress(true,false,true,true));
    assert(ui.suppress(true,false,true,true));
    assert(ui.suppress(true,false,true,false)); // release must not click through
    assert(!ui.suppress(true,false,true,false));
    assert(ui.suppress(true,true,true,false));
    assert(ui.suppress(true,false,true,false)); // keyboard close also drains one frame
    assert(!ui.suppress(true,false,true,false));
    assert(ui.suppress(true,true,true,true));
    assert(!ui.suppress(false,true,true,true)); // bridge disabled
    assert(!ui.suppress(true,false,true,false));
    assert(ui.suppress(true,true,true,true));
    assert(!ui.suppress(true,true,false,true)); // focus lost
    assert(!ui.suppress(true,false,true,false));
    std::cout<<"semicolon / model F6 / menu input ownership, release, focus, disable: PASS\n";
}
