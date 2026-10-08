#pragma once
namespace endcraft {
// The toggle is independent of guest input: releasing guest keys cannot latch it.
class InputMode {
    bool exclusive_=true, held_=false;
public:
    bool poll(bool focus,bool down) {
        if(!focus) {held_=false;return false;}
        const bool changed=down&&!held_;
        held_=down;
        if(changed) exclusive_=!exclusive_;
        return changed;
    }
    void set(bool value) {exclusive_=value;}
    bool exclusive() const {return exclusive_;}
};
}
