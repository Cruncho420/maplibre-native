#include <mln/test/util.hpp>

#include <mln/gfx/index_vector.hpp>
#include <mln/gfx/vertex_vector.hpp>

#include <memory>

using namespace mln;

namespace {
struct TestVertexBuffer : gfx::VertexBufferBase {};
struct TestIndexBuffer : gfx::IndexBufferBase {};
} // namespace

TEST(VertexVector, ReleasedDataIsFreedOnceABufferIsSetAndTheCountIsKept) {
    auto vec = std::make_shared<gfx::VertexVector<int32_t>>();
    vec->emplace_back(1);
    vec->emplace_back(2);
    vec->emplace_back(3);
    vec->updateModified();

    vec->release(); // no buffer yet: the data is still needed for the first upload
    EXPECT_EQ(3u, vec->elements());
    EXPECT_NE(nullptr, vec->data());

    const auto modified = vec->getLastModified();
    vec->setBuffer(std::make_unique<TestVertexBuffer>());
    EXPECT_EQ(nullptr, vec->data());
    EXPECT_TRUE(vec->vector().empty());
    EXPECT_EQ(3u, vec->elements());
    EXPECT_EQ(3u, vec->getRawCount());
    EXPECT_EQ(3 * sizeof(int32_t), vec->bytes());
    EXPECT_FALSE(vec->empty());

    // Nothing can be re-uploaded from a dropped copy, so the timestamp must not move.
    vec->updateModified(true);
    EXPECT_EQ(modified, vec->getLastModified());
}

TEST(VertexVector, UnreleasedDataSurvivesTheBuffer) {
    auto vec = std::make_shared<gfx::VertexVector<int32_t>>();
    vec->emplace_back(7);
    vec->setBuffer(std::make_unique<TestVertexBuffer>());
    ASSERT_NE(nullptr, vec->data());
    EXPECT_EQ(7, vec->at(0));
}

TEST(IndexVector, ReleasedDataIsFreedOnceABufferIsSetAndTheCountIsKept) {
    auto vec = std::make_shared<gfx::IndexVector<gfx::Triangles>>();
    vec->emplace_back(0, 1, 2);
    vec->setBuffer(std::make_unique<TestIndexBuffer>());
    EXPECT_EQ(3u, vec->elements()); // buffer set before release: data kept
    ASSERT_NE(nullptr, vec->data());

    vec->release(); // a buffer exists: freed now
    EXPECT_EQ(nullptr, vec->data());
    EXPECT_EQ(3u, vec->elements());
    EXPECT_EQ(3 * sizeof(uint16_t), vec->bytes());
}
