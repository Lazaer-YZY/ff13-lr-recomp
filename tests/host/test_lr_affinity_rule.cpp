// Host unit test of src/lr_affinity_rule.h (the affinity keeper's rules; no SDK, no device): bash
// tools/regress_host.sh builds and runs it with the host clang. Exit code 0 = every check passed.
#include "lr_affinity_rule.h"

#include <cstdio>

static int g_failures = 0;
#define CHECK(cond)                                                     \
  do {                                                                  \
    if (!(cond)) {                                                      \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);       \
      ++g_failures;                                                     \
    }                                                                   \
  } while (0)

// Snapdragon 8 Gen 2 (the AYN Thor): cpus 0-2 A510, 3-6 A715/A710, 7 X3.
static const long kThorKhz[] = {2016000, 2016000, 2016000, 2803200, 2803200, 2803200, 2803200, 3187200};

static void test_clusters() {
  const LrCpuClusters c = LrClustersFromMaxKhz(kThorKhz, 8);
  CHECK(c.little == 0x07);
  CHECK(c.fast == 0xF8);
  const long same[] = {1800000, 1800000, 1800000, 1800000};
  const LrCpuClusters one = LrClustersFromMaxKhz(same, 4);
  CHECK(one.little == 0 && one.fast == 0);
  CHECK(!LrMaskSpansClusters(0x0F, one));
  const LrCpuClusters none = LrClustersFromMaxKhz(nullptr, 0);
  CHECK(none.little == 0 && none.fast == 0);
  // 2 big + 6 little (Dimensity 700 class).
  const long two_big[] = {2000000, 2000000, 2000000, 2000000, 2000000, 2000000, 2200000, 2200000};
  const LrCpuClusters tb = LrClustersFromMaxKhz(two_big, 8);
  CHECK(tb.little == 0x3F && tb.fast == 0xC0);
}

static void test_should_pin() {
  const LrCpuClusters c = LrClustersFromMaxKhz(kThorKhz, 8);
  const uint64_t startup = 0x78;  // cpus 3-6
  // Our own placements never span two clusters: left alone.
  CHECK(!LrKeeperShouldPin(0x78, startup, c));
  CHECK(!LrKeeperShouldPin(0xF8, 0xF8, c));         // LR_MAIN_PRIME=2: prime + middle
  CHECK(!LrKeeperShouldPin(0x80, 0x80, c));         // LR_MAIN_PRIME=1: prime only
  CHECK(!LrKeeperShouldPin(0x07, 0x07, c));         // companion / LR_AUX_LITTLE
  CHECK(!LrKeeperShouldPin(0x38, startup, c));      // startup mask with cpu 6 paused by core_ctl
  CHECK(!LrKeeperShouldPin(0x78, 0xF8, c));         // main thread with the prime core paused
  // The /background cpuset (efficiency cores only): nothing to fix, nothing faster allowed there.
  CHECK(!LrKeeperShouldPin(0x07, startup, c));
  // A cpuset move reset the mask to the cpuset's cores (minus paused ones): pinned again.
  CHECK(LrKeeperShouldPin(0xFF, startup, c));
  CHECK(LrKeeperShouldPin(0x3F, startup, c));       // "0-5": prime and cpu 6 paused
  CHECK(LrKeeperShouldPin(0x5F, startup, c));       // "0-4,6"
  CHECK(LrKeeperShouldPin(0xFF, 0xF8, c));
  CHECK(LrKeeperShouldPin(0xFF, 0x07, c));          // the companion thread back to the efficiency cores
  // A mask set on purpose across both clusters (LR_VFX_MASK=ff) reads as a subset of itself.
  CHECK(!LrKeeperShouldPin(0xFF, 0xFF, c));
  CHECK(!LrKeeperShouldPin(0x7F, 0xFF, c));
  // No target, or no efficiency cluster: never.
  CHECK(!LrKeeperShouldPin(0xFF, 0, c));
  CHECK(!LrKeeperShouldPin(0xFF, startup, LrCpuClusters{}));
  CHECK(!LrKeeperShouldPin(0, startup, c));
}

static void test_mask_list() {
  CHECK(LrMaskList(0) == "none");
  CHECK(LrMaskList(0x78) == "3-6");
  CHECK(LrMaskList(0x87) == "0-2,7");
  CHECK(LrMaskList(0x5F) == "0-4,6");
  CHECK(LrMaskList(0x01) == "0");
  CHECK(LrMaskList(0x05) == "0,2");
  CHECK(LrMaskList(uint64_t(1) << 63) == "63");
  CHECK(LrMaskList(~uint64_t(0)) == "0-63");
}

int main() {
  test_clusters();
  test_should_pin();
  test_mask_list();
  if (g_failures) {
    std::printf("test_lr_affinity_rule: %d failure(s)\n", g_failures);
    return 1;
  }
  std::printf("test_lr_affinity_rule: ok\n");
  return 0;
}
