#pragma once
namespace endcraft {
class ModelVisibilityShortcut {
    bool visible_=false,held_=false;
public:
    bool visible() const {return visible_;}
    bool poll(bool focus,bool down,bool allowed) {
        const bool pressed=down&&!held_;
        held_=down;
        if(!focus||!allowed||!pressed) return false;
        visible_=!visible_;return true;
    }
};
}
