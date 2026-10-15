#pragma once
#include <algorithm>
#include <cmath>

namespace endcraft::combat_damage {
// Five Minecraft damage points correspond to one native character attack.
// Target HP deliberately does not participate in this conversion.
inline constexpr double mcPointsPerAttack=5.0;
inline constexpr double defaultMultiplier=1.0;
inline constexpr double maxMcDamage=10000.0;

inline bool validMultiplier(double value) noexcept {
    return std::isfinite(value)&&value>=0.01&&value<=100.0;
}

inline double amount(double mcDamage,double playerAttack,double multiplier=defaultMultiplier) noexcept {
    if(!std::isfinite(mcDamage)||mcDamage<=0||!std::isfinite(playerAttack)||playerAttack<=0||!validMultiplier(multiplier)) return 0;
    const double result=(std::min)(mcDamage,maxMcDamage)*(playerAttack/mcPointsPerAttack)*multiplier;
    return std::isfinite(result)&&result>0?result:0;
}
}
