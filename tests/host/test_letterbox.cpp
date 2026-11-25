// Host tests of the SDK's movie letterbox logic (rex/graphics/util/letterbox.h): which bars count as a letterbox, the
// zoom for bars on all four sides, and the steadying of measurements. Needs the SDK checkout (sdk/rexglue-sdk);
// skipped without it.
#if __has_include(<rex/graphics/util/letterbox.h>)
#include <rex/graphics/util/letterbox.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace lb = rex::graphics::letterbox;

static int failures = 0;
#define CHECK(cond)                                                \
  do {                                                             \
    if (!(cond)) {                                                 \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);  \
      ++failures;                                                  \
    }                                                              \
  } while (0)

struct Frame {
  uint32_t width, height;
  std::vector<uint8_t> luma;
  Frame(uint32_t w, uint32_t h, uint8_t fill) : width(w), height(h), luma(size_t(w) * h, fill) {}
  void Rows(uint32_t from, uint32_t to, uint8_t value) {
    for (uint32_t y = from; y < to; ++y)
      for (uint32_t x = 0; x < width; ++x) luma[size_t(y) * width + x] = value;
  }
  int32_t Measure() const {
    return lb::MeasureBars(width, height, [&](uint32_t x, uint32_t y) { return luma[size_t(y) * width + x]; });
  }
};

int main() {
  // A full picture: no bars.
  Frame full(1280, 720, 90);
  CHECK(full.Measure() == 0);

  // A 2.35:1 film inside 16:9: 88 rows of video black (16) at the top and the bottom.
  Frame film(1280, 720, 90);
  film.Rows(0, 88, 16);
  film.Rows(720 - 88, 720, 16);
  const int32_t bars = film.Measure();
  CHECK(bars >= 86 && bars <= 88);

  // A dark sky at the top only is picture, not a letterbox.
  Frame sky(1280, 720, 90);
  sky.Rows(0, 120, 10);
  CHECK(sky.Measure() == 0);

  // A row that is black except one bright spot is not a bar.
  Frame star(1280, 720, 90);
  star.Rows(0, 88, 16);
  star.Rows(720 - 88, 720, 16);
  for (uint32_t y = 0; y < 88; ++y) star.luma[size_t(y) * 1280 + 8] = 200;
  CHECK(star.Measure() == 0);

  // A fade to black says nothing.
  Frame black(1280, 720, 16);
  CHECK(black.Measure() == -1);

  // Zoom: 20:9 screen, a 16:9 movie pillarboxed (max zoom 2.2222 / 1.7778 = 1.25). The 2.35:1 film fills the width
  // first (1.25 < 720 / 544).
  CHECK(std::fabs(lb::Zoom(1.25f, 720, 88) - 1.25f) < 1e-4f);
  // Thin bars (a 1.9:1 film, 22 rows): the top and bottom bars go first.
  CHECK(std::fabs(lb::Zoom(1.25f, 720, 22) - 720.0f / 676.0f) < 1e-4f);
  // No side bars (a 16:9 screen) or no letterbox: no zoom.
  CHECK(lb::Zoom(1.0f, 720, 88) == 1.0f);
  CHECK(lb::Zoom(1.25f, 720, 0) == 1.0f);
  CHECK(lb::Zoom(1.25f, 720, lb::kMinBars - 1) == 1.0f);

  // Steadying: a letterbox is applied after kStableFrames frames, dark frames change nothing, a different picture
  // has to repeat as long before the zoom follows it.
  lb::BarTracker tracker;
  for (uint32_t i = 0; i + 1 < lb::kStableFrames; ++i) CHECK(tracker.Update(88) == 0);
  CHECK(tracker.Update(88) == 88);
  CHECK(tracker.Update(-1) == 88);
  CHECK(tracker.Update(87) == 88);
  for (uint32_t i = 0; i + 1 < lb::kStableFrames; ++i) CHECK(tracker.Update(0) == 88);
  CHECK(tracker.Update(0) == 0);
  tracker.Reset();
  CHECK(tracker.applied() == 0);

  if (failures) return 1;
  std::printf("test_letterbox: all passed\n");
  return 0;
}
#else
#include <cstdio>
int main() {
  std::printf("test_letterbox: skipped (no SDK checkout)\n");
  return 0;
}
#endif
