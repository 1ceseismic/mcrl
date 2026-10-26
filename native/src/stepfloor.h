#pragma once
#include <cstdint>
#include "RocketSim.h"

struct McrlWorld;

namespace mcrl {
struct VirtualFloor {
    bool active = false;
    double zBT = 0;
    double rect[4] = {0, 0, 0, 0};
    double riserP[3] = {0, 0, 0};
    double riserN[3] = {0, 0, 0};
    int idle = 0;
    bool used = false;

    void activate(const VirtualFloor& f);
    void afterTick();
    void clear();
    void shift(const double d[3]);
    bool contains(double x, double y) const;
};

class StepRaycaster final : public btDefaultVehicleRaycaster {
public:
    struct WheelHit {
        const btCollisionObject* obj = nullptr;
        int tri = -1;
    };

    StepRaycaster();
    StepRaycaster(const StepRaycaster&) = delete;
    StepRaycaster& operator=(const StepRaycaster&) = delete;

    void installInto(RocketSim::Car& car);

    void* castRay(const btVector3& from, const btVector3& to, const btCollisionObject* ignoreObj,
                  btVehicleRaycasterResult& result) override;

    VirtualFloor floor;
    // The floor's ground object: static and in no world.
    btRigidBody dummy;
    WheelHit hits[4];

private:
    WheelHit* slot(const btVector3& from);

    RocketSim::Car* car = nullptr;
};

#ifdef MCRL_TEST_HOOKS
namespace testing {
bool forceFloor(McrlWorld& w, int32_t carId, double zMc, const double rectMc[4]);
}
#endif

}
