#pragma once
#include <cstddef>
#include <memory>
#include <unordered_map>
#include "RocketSim.h"
#include "mesher.h"

struct McrlWorld;

namespace mcrl {
struct SecKey {
    int32_t x, y, z;
    bool operator==(const SecKey&) const = default;
};
struct SecKeyHash {
    size_t operator()(const SecKey& k) const noexcept {
        return (size_t)(uint32_t)k.x * 73856093u ^ (size_t)(uint32_t)k.y * 19349663u ^ (size_t)(uint32_t)k.z * 83492791u;
    }
};

// Must be destroyed before its arena.
class TerrainStore {
public:
    TerrainStore(McrlWorld* world, RocketSim::Arena* arena, int32_t ox, int32_t oy, int32_t oz);
    ~TerrainStore();
    TerrainStore(const TerrainStore&) = delete;
    TerrainStore& operator=(const TerrainStore&) = delete;

    int32_t Commit(std::unique_ptr<McrlBuilt> b);
    void Remove(const SecKey& k);
    bool IsReady(const SecKey& k) const;
    const btRigidBody* body(const SecKey& k) const;
    const uint16_t* cells(const SecKey& k) const;
    const uint8_t* fluid(const SecKey& k) const;
    bool SetFluid(const SecKey& k, const uint8_t* cells);
    void Clear();
    void Shift(int32_t ox, int32_t oy, int32_t oz);

private:
    struct Live {
        std::unique_ptr<McrlBuilt> data;
        std::unique_ptr<btRigidBody> body;
    };
    std::unique_ptr<btRigidBody> MakeBody(btBvhTriangleMeshShape* shape, const SecKey& k) const;
    btTransform SectionTransform(const SecKey& k) const;
    void WakeBallIfOverlapping(const SecKey& k, const btRigidBody* a, const btRigidBody* b);

    McrlWorld* world;
    RocketSim::Arena* arena;
    int32_t ox, oy, oz;
    std::unordered_map<SecKey, Live, SecKeyHash> live;
};
}
