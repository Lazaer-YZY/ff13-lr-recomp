// Host unit test of src/lr_cheats.h (no SDK, no game data): bash tools/regress_host.sh builds
// and runs it with the host clang. Exit code 0 = every check passed.
#include "lr_cheats.h"

#include <cstdio>

static int g_failures = 0;
#define CHECK(cond)                                                     \
  do {                                                                  \
    if (!(cond)) {                                                      \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);       \
      ++g_failures;                                                     \
    }                                                                   \
  } while (0)

static void test_parse() {
  CHECK(lr_parse_cheats("") == 0);
  CHECK(lr_parse_cheats("0") == 0);
  CHECK(lr_parse_cheats("1") == kLrCheatInfiniteEp);
  CHECK(lr_parse_cheats("2") == kLrCheatFreezeClock);
  CHECK(lr_parse_cheats("3") == (kLrCheatInfiniteEp | kLrCheatFreezeClock));
  CHECK(lr_parse_cheats("7") == kLrCheatMask);  // unknown bits dropped
  CHECK(lr_parse_cheats("-1") == 0);
  CHECK(lr_parse_cheats("x") == 0);
  CHECK(lr_parse_cheats("1 ") == 0);
  CHECK(lr_parse_cheats("99999999999") == 0);  // too long, no overflow
}

static void test_ep() {
  // 2000 = 1 EP; Teleport / Chronostasis cost 3 EP (6000) of 5 (10000).
  CHECK(lr_ep_pair_valid(4000, 10000));
  CHECK(lr_ep_top_up(4000, 10000) == 10000);
  CHECK(lr_ep_top_up(0, 10000) == 10000);
  CHECK(lr_ep_top_up(10000, 10000) == 0);  // full: no write
  CHECK(!lr_ep_pair_valid(5, 0));          // party not loaded yet
  CHECK(lr_ep_top_up(5, 0) == 0);
  CHECK(!lr_ep_pair_valid(12000, 10000));  // EP above its maximum: not the EP pair
  CHECK(lr_ep_top_up(12000, 10000) == 0);
  CHECK(!lr_ep_pair_valid(1, 0x6DAE7FDC)); // a pointer where the maximum should be
  CHECK(lr_ep_top_up(1, kLrEpMaxSane) == kLrEpMaxSane);
  CHECK(lr_ep_top_up(1, kLrEpMaxSane + 1) == 0);
}

static void test_be32() {
  uint8_t b[4];
  lr_cheat_store_be32(b, 0x12345678);
  CHECK(b[0] == 0x12 && b[1] == 0x34 && b[2] == 0x56 && b[3] == 0x78);
  CHECK(lr_cheat_load_be32(b) == 0x12345678);
}

int main() {
  test_parse();
  test_ep();
  test_be32();
  if (g_failures) {
    std::printf("test_lr_cheats: %d check(s) failed\n", g_failures);
    return 1;
  }
  std::printf("test_lr_cheats: all checks passed\n");
  return 0;
}
