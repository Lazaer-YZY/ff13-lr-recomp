// Cheats: the pure parts (no SDK, no guest memory manager), so tests/host/ can check them on
// the PC. The hooks in lr_hooks.cpp do the rest (the clock hook, the per-frame EP hold).
#pragma once

#include <cstdint>
#include <cstring>
#include <string>

// Bits of g_lr_cheats (the CTRL dialog's switches, android Cheats.java; on PC Settings > Cheats).
constexpr int kLrCheatInfiniteEp = 1;   // EP stays at its maximum (EP abilities cost nothing)
constexpr int kLrCheatFreezeClock = 2;  // the game clock stands still
constexpr int kLrCheatMask = kLrCheatInfiniteEp | kLrCheatFreezeClock;

// LR_CHEATS / cheats.txt / nativeSetOption("cheats"): the mask in decimal. Unknown bits are
// dropped; anything that is not a plain non-negative number is 0 (all off).
inline int lr_parse_cheats(const std::string& text) {
  if (text.empty() || text.size() > 9) return 0;
  int value = 0;
  for (char c : text) {
    if (c < '0' || c > '9') return 0;
    value = value * 10 + (c - '0');
  }
  return value & kLrCheatMask;
}

inline uint32_t lr_cheat_load_be32(const uint8_t* p) {
  return uint32_t(p[0]) << 24 | uint32_t(p[1]) << 16 | uint32_t(p[2]) << 8 | uint32_t(p[3]);
}

// One 32-bit store (game threads read the word meanwhile; byte stores could be seen half done).
// Hosts are little-endian (arm64, x64).
inline void lr_cheat_store_be32(uint8_t* p, uint32_t v) {
  const uint32_t be = __builtin_bswap32(v);
  std::memcpy(p, &be, 4);
}

// Infinite EP. EP ("Gp" in the game: getPartyGp / getPartyMaxGp) is a u32 at party + 0x2850 and
// its maximum at party + 0x2854, in 1/2000 EP (2000 = 1.0 EP; 10000 = 5.0 early on); the party is
// the pointer at 0x8315817C (docs/dual_screen_battle_re.md "EP"; lr_companion.inc reads the same
// pair for the second screen). Teleport and Chronostasis cost 3 EP (6000).
constexpr uint32_t kLrPartyPointer = 0x8315817C;
constexpr uint32_t kLrPartyEp = 0x2850;
constexpr uint32_t kLrPartyEpMax = 0x2854;
constexpr uint32_t kLrEpUnit = 2000;
constexpr uint32_t kLrEpMaxSane = 100000;  // 50 EP: a larger maximum is not the EP pair

// The pair looks like EP (the party loaded; not a stale or foreign structure).
inline bool lr_ep_pair_valid(uint32_t ep, uint32_t ep_max) { return ep_max > 0 && ep_max <= kLrEpMaxSane && ep <= ep_max; }

// The EP to store to hold it at its maximum, or 0 when nothing is to be written (already full,
// or the pair is not EP).
inline uint32_t lr_ep_top_up(uint32_t ep, uint32_t ep_max) {
  return lr_ep_pair_valid(ep, ep_max) && ep < ep_max ? ep_max : 0;
}
