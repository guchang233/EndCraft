#pragma once
namespace endcraft {
// Keep the close click, including its release frame, away from the host UI.
class MenuInputGate {
    bool draining_=false;
public:
    bool suppress(bool available,bool menuOpen,bool focus,bool mouseDown) noexcept {
        if(!available||!focus) {draining_=false;return false;}
        if(menuOpen) {draining_=true;return true;}
        if(draining_) {draining_=mouseDown;return true;}
        return false;
    }
};
}
