// lr - the affinity keeper's rules (src/android/lr_affinity.cpp), free of Android headers so the host
// tests (tests/host/test_lr_affinity_rule.cpp) can include them. CPU masks are bit sets: bit n = cpu n
// (the first 64 CPUs; phones have at most 16).
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

struct LrCpuClusters {
  uint64_t little = 0;  // the efficiency cores: the lowest maximum clock
  uint64_t fast = 0;    // every faster core (middle and prime clusters)
};

// From each CPU's maximum clock (cpuinfo_max_freq in kHz, in CPU order). One clock for every CPU means
// no efficiency cluster: both masks stay 0 and no mask ever spans two clusters.
inline LrCpuClusters LrClustersFromMaxKhz(const long* max_khz, size_t count) {
  LrCpuClusters c;
  if (count == 0) return c;
  long slowest = max_khz[0], fastest = max_khz[0];
  for (size_t i = 1; i < count; ++i) {
    if (max_khz[i] < slowest) slowest = max_khz[i];
    if (max_khz[i] > fastest) fastest = max_khz[i];
  }
  if (slowest == fastest) return c;
  for (size_t cpu = 0; cpu < count && cpu < 64; ++cpu) {
    if (max_khz[cpu] == slowest) {
      c.little |= uint64_t(1) << cpu;
    } else {
      c.fast |= uint64_t(1) << cpu;
    }
  }
  return c;
}

// True when the mask holds both an efficiency core and a faster one. None of the game's placements does
// (startup: the middle cluster; LR_MAIN_PRIME: prime + middle; the companion and LR_AUX_LITTLE: the
// efficiency cores); a mask that a cpuset move reset does (the cpuset's cores).
inline bool LrMaskSpansClusters(uint64_t mask, const LrCpuClusters& c) {
  return (mask & c.little) != 0 && (mask & c.fast) != 0;
}

// Whether the keeper pins a thread whose mask reads `now` to `want`: only a mask spanning both clusters
// that is not already inside `want`. The kernel reports a thread's mask without the cores core_ctl has
// paused, so a mask set on purpose across both clusters (LR_VFX_MASK=ff) reads as a subset of itself
// and is left alone, and a reset mask is never inside a one-cluster `want`.
inline bool LrKeeperShouldPin(uint64_t now, uint64_t want, const LrCpuClusters& c) {
  return want != 0 && LrMaskSpansClusters(now, c) && (now & ~want) != 0;
}

// "0-2,7" for the log; "none" for an empty mask.
inline std::string LrMaskList(uint64_t mask) {
  if (mask == 0) return "none";
  std::string out;
  for (int cpu = 0; cpu < 64;) {
    if (!((mask >> cpu) & 1)) {
      ++cpu;
      continue;
    }
    int last = cpu;
    while (last + 1 < 64 && ((mask >> (last + 1)) & 1)) ++last;
    if (!out.empty()) out += ',';
    out += std::to_string(cpu);
    if (last > cpu) out += '-' + std::to_string(last);
    cpu = last + 1;
  }
  return out;
}
