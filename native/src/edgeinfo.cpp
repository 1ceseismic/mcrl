/*
This file contains code altered from Bullet 3.24, BulletCollision/CollisionDispatch/btInternalEdgeUtility.cpp
(btConnectivityProcessor::processTriangle, the shared-edge case), as vendored in
native/third_party/RocketSim/libsrc/bullet3-3.24. It is an altered source version, not the original software: the
code was ported out of its callback class into the function sharedEdge below and is driven by mesh adjacency instead
of a BVH query. The notice below is Bullet's LICENSE.txt, verbatim, with the copyright line of the same library's
btTriangleInfoMap.h (btInternalEdgeUtility.cpp carries no header of its own).

Bullet Continuous Collision Detection and Physics Library
Copyright (c) 2010 Erwin Coumans  http://bulletphysics.org

This software is provided 'as-is', without any express or implied warranty.
In no event will the authors be held liable for any damages arising from the use of this software.
Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it freely,
subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not claim that you wrote the original software. If you use this software in a product, an acknowledgment in the product documentation would be appreciated but is not required.
2. Altered source versions must be plainly marked as such, and must not be misrepresented as being the original software.
3. This notice may not be removed or altered from any source distribution.
*/
#include "edgeinfo.h"

#include <algorithm>
#include "BulletCollision/BroadphaseCollision/btQuantizedBvh.h"
#include "LinearMath/btQuaternion.h"

namespace mcrl {
namespace {
inline btVector3 vertexAt(const std::vector<float>& verts, int32_t i) {
    return btVector3(verts[3 * (size_t)i], verts[3 * (size_t)i + 1], verts[3 * (size_t)i + 2]);
}

// Bullet's btConnectivityProcessor::processTriangle shared-edge case, bit for bit.
void sharedEdge(const btVector3* a, const btVector3* b, int sa0, int sa1, int sb0, int sb1, btTriangleInfo* info,
                const btTriangleInfoMap& map) {
    if (sa0 == 0 && sa1 == 2) {
        sa0 = 2;
        sa1 = 0;
        std::swap(sb0, sb1);
    }
    const int sumvertsA = sa0 + sa1;
    const int otherIndexA = 3 - sumvertsA;
    const int otherIndexB = 3 - (sb0 + sb1);
    btVector3 edge(a[sa1] - a[sa0]);
    btVector3 normalA = (a[1] - a[0]).cross(a[2] - a[0]);
    normalA.normalize();
    btVector3 normalB = (b[sb0] - b[sb1]).cross(b[otherIndexB] - b[sb1]);
    normalB.normalize();
    edge.normalize();
    btVector3 edgeCrossA = edge.cross(normalA).normalize();
    {
        const btVector3 tmp = a[otherIndexA] - a[sa0];
        if (edgeCrossA.dot(tmp) < 0) edgeCrossA *= -1;
    }
    btVector3 edgeCrossB = edge.cross(normalB).normalize();
    {
        const btVector3 tmp = b[otherIndexB] - b[sb0];
        if (edgeCrossB.dot(tmp) < 0) edgeCrossB *= -1;
    }
    btVector3 calculatedEdge = edgeCrossA.cross(edgeCrossB);
    const btScalar len2 = calculatedEdge.length2();
    btScalar correctedAngle(0);
    bool isConvex = false;
    if (len2 >= map.m_planarEpsilon) {
        calculatedEdge.normalize();
        btVector3 calculatedNormalA = calculatedEdge.cross(edgeCrossA);
        calculatedNormalA.normalize();
        const btScalar angle2 = btAtan2(edgeCrossB.dot(calculatedNormalA), edgeCrossB.dot(edgeCrossA));
        const btScalar ang4 = SIMD_PI - angle2;
        const btScalar dotA = normalA.dot(edgeCrossB);
        isConvex = (dotA < 0.);
        correctedAngle = isConvex ? ang4 : -ang4;
    }
    btVector3 axis;
    int swapFlag, convexFlag;
    btScalar* angle;
    switch (sumvertsA) {
        case 1:
            axis = a[0] - a[1];
            swapFlag = TRI_INFO_V0V1_SWAP_NORMALB;
            convexFlag = TRI_INFO_V0V1_CONVEX;
            angle = &info->m_edgeV0V1Angle;
            break;
        case 2:
            axis = a[2] - a[0];
            swapFlag = TRI_INFO_V2V0_SWAP_NORMALB;
            convexFlag = TRI_INFO_V2V0_CONVEX;
            angle = &info->m_edgeV2V0Angle;
            break;
        default:
            axis = a[1] - a[2];
            swapFlag = TRI_INFO_V1V2_SWAP_NORMALB;
            convexFlag = TRI_INFO_V1V2_CONVEX;
            angle = &info->m_edgeV1V2Angle;
            break;
    }
    const btQuaternion orn(axis, -correctedAngle);
    const btVector3 computedNormalB = quatRotate(orn, normalA);
    if (computedNormalB.dot(normalB) < 0) info->m_flags |= swapFlag;
    *angle = -correctedAngle;
    if (isConvex) info->m_flags |= convexFlag;
}
}

void edge_info(const std::vector<float>& verts, const std::vector<int32_t>& idx, int32_t tris, btTriangleInfoMap& map) {
    const int32_t all = (int32_t)(idx.size() / 3);
    const int32_t nv = (int32_t)(verts.size() / 3);
    std::vector<int32_t> start((size_t)nv + 1, 0), list(idx.size());
    for (int32_t i : idx) start[(size_t)i + 1]++;
    for (int32_t v = 0; v < nv; v++) start[(size_t)v + 1] += start[v];
    {
        std::vector<int32_t> fill(start.begin(), start.end() - 1);
        for (int32_t t = 0; t < all; t++)
            for (int k = 0; k < 3; k++) list[(size_t)fill[idx[3 * (size_t)t + k]]++] = t;
    }
    auto degenerate = [&map](const btVector3* v) {
        return ((v[1] - v[0]).cross(v[2] - v[0])).length2() < map.m_equalVertexThreshold;
    };
    std::vector<int32_t> cand;
    for (int32_t t = 0; t < tris; t++) {
        const int32_t* ia = &idx[3 * (size_t)t];
        const btVector3 a[3] = {vertexAt(verts, ia[0]), vertexAt(verts, ia[1]), vertexAt(verts, ia[2])};
        if (degenerate(a)) continue;
        cand.clear();
        for (int k = 0; k < 3; k++)
            for (int32_t j = start[ia[k]]; j < start[(size_t)ia[k] + 1]; j++)
                if (list[j] != t) cand.push_back(list[j]);
        std::sort(cand.begin(), cand.end());
        for (size_t j = 0; j + 1 < cand.size(); j++) {
            if (cand[j] != cand[j + 1]) continue;
            const int32_t u = cand[j];
            while (j + 1 < cand.size() && cand[j + 1] == u) j++;
            const int32_t* ib = &idx[3 * (size_t)u];
            const btVector3 b[3] = {vertexAt(verts, ib[0]), vertexAt(verts, ib[1]), vertexAt(verts, ib[2])};
            if (degenerate(b)) continue;
            int sa[3], sb[3], shared = 0;
            for (int i = 0; i < 3; i++)
                for (int k = 0; k < 3; k++)
                    if (ia[i] == ib[k]) {
                        sa[shared] = i;
                        sb[shared] = k;
                        shared++;
                    }
            if (shared != 2) continue;
            const btHashInt key((0 << (31 - MAX_NUM_PARTS_IN_BITS)) | t);
            btTriangleInfo* info = map.find(key);
            if (!info) {
                map.insert(key, btTriangleInfo());
                info = map.find(key);
            }
            sharedEdge(a, b, sa[0], sa[1], sb[0], sb[1], info, map);
        }
    }
}
}
