#pragma once

#include <mln/tile/tile_id.hpp>
#include <mln/util/bitmask_operations.hpp>
#include <mln/util/constants.hpp>
#include <mln/util/geometry.hpp>
#include <mln/util/mat4.hpp>

#include <map>
#include <set>
#include <vector>
#include <string>
#include <memory>
#include <thread>
#include <unordered_set>

namespace mln {

class SymbolInstance;
class RenderLayer;
class SymbolBucket;

class IndexedSymbolInstance {
public:
    IndexedSymbolInstance(uint32_t crossTileID_, Point<int64_t> coord_)
        : crossTileID(crossTileID_),
          coord(coord_) {}

    uint32_t crossTileID;
    Point<int64_t> coord;
};

class TileLayerIndex {
public:
    TileLayerIndex(OverscaledTileID coord,
                   std::vector<SymbolInstance>&,
                   uint32_t bucketInstanceId,
                   std::string bucketLeaderId);

    Point<int64_t> getScaledCoordinates(const SymbolInstance&, const OverscaledTileID&) const;
    void findMatches(SymbolBucket&, const OverscaledTileID&, std::set<uint32_t>&) const;

    OverscaledTileID coord;
    uint32_t bucketInstanceId;
    std::string bucketLeaderId;
    std::map<std::u16string, std::vector<IndexedSymbolInstance>> indexedSymbolInstances;
};

class CrossTileSymbolLayerIndex {
public:
    CrossTileSymbolLayerIndex(uint32_t& maxCrossTileID);
    bool addBucket(const OverscaledTileID&, const mat4& tileMatrix, SymbolBucket&);
    bool removeStaleBuckets(const std::unordered_set<uint32_t>& currentIDs);
    void handleWrapJump(float newLng);

    /// False: an already indexed bucket is not re-indexed only because some of its symbols were outside the
    /// viewport filter of an overscaled tile (hasUninitializedSymbols); uninitializedPending records that one was
    /// skipped. Those symbols have no cross-tile ID, so placement hides them either way: the re-index only has
    /// to happen before the next placement, not on every frame.
    bool refreshUninitialized = true;
    bool uninitializedPending = false;

private:
    void removeBucketCrossTileIDs(uint8_t zoom, const TileLayerIndex& removedBucket);

    std::map<uint8_t, std::map<OverscaledTileID, TileLayerIndex>> indexes;
    std::map<uint8_t, std::set<uint32_t>> usedCrossTileIDs;
    float lng = 0;
    uint32_t& maxCrossTileID;
};

class CrossTileSymbolIndex {
public:
    CrossTileSymbolIndex();

    enum class AddLayerResult : uint8_t {
        NoChanges = 0,
        BucketsAdded = 1 << 0,
        BucketsRemoved = 1 << 1,
        /// Buckets with off-viewport symbols were not re-indexed (refreshUninitialized = false).
        UninitializedPending = 1 << 2
    };

    AddLayerResult addLayer(const RenderLayer& layer, float lng, bool refreshUninitialized = true);
    void pruneUnusedLayers(const std::set<std::string>&);

    void reset();

    const std::thread::id renderThreadID = std::this_thread::get_id();

private:
    std::map<std::string, CrossTileSymbolLayerIndex> layerIndexes;
    uint32_t maxCrossTileID = 0;
};

} // namespace mln
