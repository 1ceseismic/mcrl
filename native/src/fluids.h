#pragma once
#include <cstdint>
#include "mcrl.h"

struct McrlWorld;

namespace mcrl::fluids {

constexpr float UNDERWATER_TRACTION = 0.7f;
constexpr float TRACTION_IMMERSION = 0.5f;

bool valid_plane(const uint8_t* cells);

void before_tick(McrlWorld& w);

}
