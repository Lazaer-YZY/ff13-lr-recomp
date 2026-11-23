// lr - the affinity keeper: game threads pinned again after Android moves the process between cpusets,
// and the debug log's "LR placement" line (declared in android_glue.h). Ported from the sister DS2
// project's ds2_affinity.cpp, which was itself built on this project's PinThreadsToFastCores.
//
// Moving a process between cpusets (Android regroups an app from /top-app to /foreground when another
// app, window or display takes focus, e.g. the bottom screen's app on a dual-screen handheld; a power
// daemon may rewrite cpusets too) resets every thread's CPU affinity to the cpuset's cores, silently
// dropping PinThreadsToFastCores' startup mask and LR's per-thread placements. Measured in DS2 on the
// same AYN Thor (2026-11-20): on battery core_ctl then parks the prime core and the scheduler puts the
// reset threads on the efficiency cores (cores 0-2 80 % busy, the job threads at twice their CPU time,
// the main thread waiting ~6 ms a frame, 10-20 % of frames late); on the charger core_ctl keeps the fast
// cores up and nothing shows.
//
// Once a second a low-priority thread looks for threads whose mask spans both the efficiency and the
// faster cores (none of LR's placements does; a reset mask does) and pins them again: to the mask LR set
// for that thread on purpose (NoteThreadMask: LR_MAIN_PRIME's main thread, LR_VFX_MASK's effect workers,
// LR_AUX_LITTLE's and the companion's efficiency-core threads), else to the startup mask. LR has no
// prime-core governor that moves threads at run time (DS2's moves its main, GPU command and draw
// threads); LrPlaceMainOnPrime is the one placement of the main thread, and a future governor would
// call NoteThreadMask on every change. Android's own threads (UI, binder, Java runtime, HWUI) are left
// alone. The SDK's shader compile threads (prime + middle, or the efficiency cores while a library
// precompile runs into gameplay) get the startup mask after a reset: their placement is not visible
// from here. LR_AFFINITY_KEEPER=0 turns the pinning off; cpuset moves are logged either way.

#if defined(__ANDROID__)

#include "android/android_glue.h"
#include "lr_affinity_rule.h"

#include <android/log.h>
#include <dirent.h>
#include <pthread.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <rex/logging/api.h>
#include <rex/logging/macros.h>

namespace lr::android {

namespace {

std::string ReadLine(const std::string& path) {
  std::string line;
  if (FILE* f = std::fopen(path.c_str(), "r")) {
    char buf[256] = {};
    if (std::fgets(buf, sizeof(buf), f)) line = buf;
    std::fclose(f);
  }
  while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
  return line;
}

std::string ThreadComm(int tid) { return ReadLine("/proc/self/task/" + std::to_string(tid) + "/comm"); }

uint64_t ToMask(const cpu_set_t& set) {
  uint64_t mask = 0;
  for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &set)) mask |= uint64_t(1) << cpu;
  }
  return mask;
}

uint64_t ThreadMask(int tid) {
  cpu_set_t set;
  CPU_ZERO(&set);
  return sched_getaffinity(tid, sizeof(set), &set) == 0 ? ToMask(set) : 0;
}

bool SetThreadMask(int tid, uint64_t mask) {
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
    if ((mask >> cpu) & 1) CPU_SET(cpu, &set);
  }
  return sched_setaffinity(tid, sizeof(set), &set) == 0;
}

LrCpuClusters ReadClusters() {
  std::vector<long> max_khz;
  for (int cpu = 0; cpu < 64; ++cpu) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = std::fopen(path, "r");
    if (!f) break;
    long khz = 0;
    if (std::fscanf(f, "%ld", &khz) != 1) khz = 0;
    std::fclose(f);
    max_khz.push_back(khz);
  }
  return LrClustersFromMaxKhz(max_khz.data(), max_khz.size());
}

// Threads whose mask LR set on purpose (NoteThreadMask), with the thread's name at that time: a tid
// reused by a new thread (another name) does not inherit the note.
struct Note {
  uint64_t mask;
  std::string comm;
};
std::mutex g_notes_mutex;
std::unordered_map<int, Note> g_notes;
std::atomic<int> g_main_tid{0};

bool IsAndroidThread(int tid, const std::string& comm) {
  if (tid == getpid()) return true;  // the Java main / UI thread
  static const char* const kPrefixes[] = {"binder:", "HwBinder:", "RenderThread", "hwuiTask", "Jit thread pool",
                                          "HeapTaskDaemon", "FinalizerDaemon", "FinalizerWatchd", "ReferenceQueueD",
                                          "Signal Catcher", "perfetto", "ADB-JDWP", "Profile Saver", "Binder:",
                                          "queued-work-loo", "android.", "VsyncReceiver", "InsetsAnimation",
                                          "Choreographer", "SDLActivity"};
  for (const char* prefix : kPrefixes) {
    if (comm.rfind(prefix, 0) == 0) return true;
  }
  return false;
}

// The debug log's placement line (every 10 s with "Debug logging", LR_DIAG): the process's cpuset and
// its cores, core_ctl's active cores per cluster, and per thread group the CPU time since the previous
// line, split by the core each thread last ran on (/proc stat field 39; an approximation), and the
// masks of the threads that ran. Called from the keeper's thread only.
std::string PlacementReport() {
  const std::string cpuset = ReadLine("/proc/self/cpuset");
  std::string out = "cpuset " + (cpuset.empty() ? std::string("?") : cpuset) + " (cpus " +
                    (cpuset.empty() ? std::string("?") : ReadLine("/dev/cpuset" + cpuset + "/cpus")) + ")";
  // core_ctl sits on the first CPU of each cluster (cpufreq policyN).
  for (int cpu = 0; cpu < 16; ++cpu) {
    if (access(("/sys/devices/system/cpu/cpufreq/policy" + std::to_string(cpu)).c_str(), F_OK) != 0) continue;
    const std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/core_ctl/";
    const std::string active = ReadLine(base + "active_cpus");
    if (!active.empty()) {
      out += "; core_ctl cpu" + std::to_string(cpu) + " active " + active + " need " + ReadLine(base + "need_cpus");
    }
  }
  struct Group {
    Group(const char* name) : label(name) {}
    const char* label;
    int threads = 0;
    uint64_t ticks = 0;
    uint64_t by_cpu[16] = {};
    std::set<std::string> masks;
  };
  Group groups[] = {{"main"}, {"gpu"}, {"effects"}, {"jobs"}, {"audio"}, {"shaders"}, {"other"}};
  constexpr int kOther = 6;
  static std::map<int, uint64_t> last_ticks;
  static auto last_time = std::chrono::steady_clock::now();
  const auto now_time = std::chrono::steady_clock::now();
  const double window_s = std::max(0.001, std::chrono::duration<double>(now_time - last_time).count());
  last_time = now_time;
  const long clk_tck = std::max(1L, sysconf(_SC_CLK_TCK));
  std::map<int, uint64_t> now_ticks;
  struct Other {
    uint64_t ticks;
    std::string name;
    int cpu;
    std::string mask;
  };
  std::vector<Other> others;
  const int main_tid = g_main_tid.load(std::memory_order_relaxed);
  if (DIR* dir = opendir("/proc/self/task")) {
    while (dirent* e = readdir(dir)) {
      if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
      const int tid = std::atoi(e->d_name);
      const std::string stat = ReadLine(std::string("/proc/self/task/") + e->d_name + "/stat");
      const size_t open = stat.find('('), close = stat.rfind(')');
      if (open == std::string::npos || close == std::string::npos || close + 2 > stat.size()) continue;
      const std::string comm = stat.substr(open + 1, close - open - 1);
      // After ") ": state(3) ... utime(14) stime(15) ... processor(39).
      std::vector<std::string> f;
      size_t pos = close + 2;
      while (pos < stat.size()) {
        const size_t sp = stat.find(' ', pos);
        f.push_back(stat.substr(pos, sp == std::string::npos ? std::string::npos : sp - pos));
        if (sp == std::string::npos) break;
        pos = sp + 1;
      }
      if (f.size() < 37) continue;
      const uint64_t ticks = std::strtoull(f[11].c_str(), nullptr, 10) + std::strtoull(f[12].c_str(), nullptr, 10);
      const int cpu = std::atoi(f[36].c_str());
      now_ticks[tid] = ticks;
      const auto it = last_ticks.find(tid);
      const uint64_t delta = it == last_ticks.end() || ticks < it->second ? 0 : ticks - it->second;
      int g = kOther;
      if (tid == main_tid) g = 0;
      else if (comm.rfind("GPU Commands", 0) == 0) g = 1;
      else if (comm.rfind("vfx-qix-effect", 0) == 0) g = 2;
      else if (comm.rfind("CDevCommon Task", 0) == 0) g = 3;
      else if (comm.rfind("XMA Decoder", 0) == 0 || comm.rfind("Audio Worker", 0) == 0 ||
               comm.rfind("CDevSd", 0) == 0 || comm.rfind("SDLAudio", 0) == 0) g = 4;
      else if (comm.rfind("Vulkan ", 0) == 0) g = 5;
      Group& gr = groups[g];
      ++gr.threads;
      gr.ticks += delta;
      if (cpu >= 0 && cpu < 16) gr.by_cpu[cpu] += delta;
      if (!delta) continue;
      const std::string mask_list = LrMaskList(ThreadMask(tid));
      gr.masks.insert(mask_list);
      if (g == kOther) others.push_back({delta, comm, cpu, mask_list});
    }
    closedir(dir);
  }
  last_ticks = std::move(now_ticks);
  // % of one core over the window.
  auto pct = [&](uint64_t ticks) { return int(100.0 * double(ticks) / double(clk_tck) / window_s + 0.5); };
  for (const Group& gr : groups) {
    if (!gr.ticks) continue;
    std::string where;
    for (int cpu = 0; cpu < 16; ++cpu) {
      if (!gr.by_cpu[cpu]) continue;
      where += (where.empty() ? "" : " ") + std::to_string(cpu) + ":" + std::to_string(100 * gr.by_cpu[cpu] / gr.ticks);
    }
    std::string masks;
    for (const std::string& m : gr.masks) masks += (masks.empty() ? "" : "|") + m;
    out += "; " + std::string(gr.label) + " " + std::to_string(gr.threads) + "x " + std::to_string(pct(gr.ticks)) +
           "% of a core [last cpu %: " + where + "] mask " + masks;
  }
  std::sort(others.begin(), others.end(), [](const Other& a, const Other& b) { return a.ticks > b.ticks; });
  for (size_t i = 0; i < others.size() && i < 4; ++i) {
    out += "; '" + others[i].name + "' " + std::to_string(pct(others[i].ticks)) + "% cpu" +
           std::to_string(others[i].cpu) + " mask " + others[i].mask;
  }
  return out;
}

void KeepAffinity(uint64_t startup, LrCpuClusters clusters, bool keep, bool diag) {
  pthread_setname_np(pthread_self(), "LR affinity");
  setpriority(PRIO_PROCESS, id_t(gettid()), 10);
  std::string last_cpuset;
  std::unordered_set<int> seen;  // threads seen with a mask inside one cluster: a later spanning mask is a reset
  uint64_t total_reset = 0;
  int last_failed = 0;
  bool first_pass = true;
  uint32_t sync_ticks = 0;
  auto next_report = std::chrono::steady_clock::now() + std::chrono::seconds(10);
  if (diag) PlacementReport();  // the baseline for the first line's CPU times
  for (;;) {
    std::this_thread::sleep_for(std::chrono::seconds(1));
    const std::string cpuset = ReadLine("/proc/self/cpuset");
    if (first_pass) {
      REXLOG_INFO("LR affinity: keeper {} (startup mask cpus {}, efficiency cores {}, faster cores {}); cpuset {}",
                  keep ? "on: thread masks a cpuset change resets are pinned again once a second"
                       : "off (LR_AFFINITY_KEEPER=0, or the game runs on all cores): cpuset changes logged only",
                  LrMaskList(startup), LrMaskList(clusters.little), LrMaskList(clusters.fast), cpuset);
    } else if (cpuset != last_cpuset) {
      const std::string cpus = cpuset.empty() ? std::string("?") : ReadLine("/dev/cpuset" + cpuset + "/cpus");
      REXLOG_INFO("LR affinity: cpuset {} -> {} (cpus {})", last_cpuset, cpuset, cpus);
      __android_log_print(ANDROID_LOG_INFO, "lrrecomp", "affinity: cpuset %s -> %s (cpus %s)", last_cpuset.c_str(),
                          cpuset.c_str(), cpus.c_str());
    }
    last_cpuset = cpuset;
    if (keep) {
      std::unordered_map<int, Note> notes;
      {
        std::lock_guard<std::mutex> lock(g_notes_mutex);
        notes = g_notes;
      }
      int reset = 0, outside = 0, failed = 0;
      std::string reset_names, outside_names;
      std::unordered_set<int> present;
      if (DIR* dir = opendir("/proc/self/task")) {
        while (dirent* e = readdir(dir)) {
          if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
          const int tid = std::atoi(e->d_name);
          present.insert(tid);
          const uint64_t now = ThreadMask(tid);
          if (!now) continue;
          if (!LrMaskSpansClusters(now, clusters)) {
            seen.insert(tid);
            continue;
          }
          const std::string comm = ThreadComm(tid);
          if (IsAndroidThread(tid, comm)) continue;
          uint64_t want = startup;
          if (const auto it = notes.find(tid); it != notes.end() && it->second.comm == comm) want = it->second.mask;
          if (!LrKeeperShouldPin(now, want, clusters)) continue;
          // A cpuset without the wanted cores (e.g. LR_MAIN_PRIME=1's prime core outside /foreground):
          // the startup mask instead until the next reset.
          const bool ok = SetThreadMask(tid, want) || (want != startup && SetThreadMask(tid, startup));
          if (!ok) {
            ++failed;
            continue;
          }
          // Seen inside one cluster before: its mask was reset. Never seen so: a thread created outside
          // the startup pin (the Java side's own threads, or one created just before the reset).
          const bool was_reset = seen.count(tid) != 0;
          seen.insert(tid);
          std::string& names = was_reset ? reset_names : outside_names;
          if (names.size() < 160) names += (names.empty() ? "" : ", ") + comm;
          ++(was_reset ? reset : outside);
        }
        closedir(dir);
      }
      for (auto it = seen.begin(); it != seen.end();) it = present.count(*it) ? std::next(it) : seen.erase(it);
      {
        std::lock_guard<std::mutex> lock(g_notes_mutex);
        for (auto it = g_notes.begin(); it != g_notes.end();) {
          // Not listed in this pass: gone, unless it was created after the listing.
          const bool gone = !present.count(it->first) &&
                            access(("/proc/self/task/" + std::to_string(it->first)).c_str(), F_OK) != 0;
          it = gone ? g_notes.erase(it) : std::next(it);
        }
      }
      if (reset) {
        total_reset += uint64_t(reset);
        REXLOG_INFO("LR affinity: {} thread masks reset by a cpuset change pinned again ({} so far): {}", reset,
                    total_reset, reset_names);
      }
      if (outside && (first_pass || diag)) {
        REXLOG_INFO("LR affinity: {} threads created outside the startup pin pinned to cpus {}: {}", outside,
                    LrMaskList(startup), outside_names);
      }
      if (failed != last_failed) {
        REXLOG_WARN("LR affinity: {} reset thread masks could not be pinned (cpuset {} without their cores?)", failed,
                    cpuset);
        last_failed = failed;
      }
    }
    first_pass = false;
    if (diag && std::chrono::steady_clock::now() >= next_report) {
      next_report += std::chrono::seconds(10);
      REXLOG_INFO("LR placement: {}", PlacementReport());
    }
    // With debug logging, the log is committed to storage every 2 s (as in DS2: a phone that rebooted lost the
    // last ~12 s of a log that was written but never committed). Bug-report logs end close to a sudden reboot.
    if (diag && ++sync_ticks % 2 == 0) rex::SyncLoggingToStorage();
  }
}

}  // namespace

void NoteThreadMask(int tid, uint64_t mask) {
  if (tid <= 0 || mask == 0) return;
  std::string comm = ThreadComm(tid);
  std::lock_guard<std::mutex> lock(g_notes_mutex);
  g_notes[tid] = Note{mask, std::move(comm)};
}

void NoteGameMainThread(int tid) { g_main_tid.store(tid, std::memory_order_relaxed); }

void StartAffinityKeeper() {
  static std::atomic<bool> started{false};
  if (started.exchange(true)) return;
  const char* sw = std::getenv("LR_AFFINITY_KEEPER");
  const bool wanted = !(sw && sw[0] == '0');
  const char* d = std::getenv("LR_DIAG");
  const bool diag = !(d && d[0] == '0');  // "Debug logging", same rule as lr_hooks.cpp lr_diag_enabled
  const uint64_t startup = PinnedCoresMask();
  const LrCpuClusters clusters = ReadClusters();
  // Nothing to keep when the game runs on all cores (LR_AFFINITY=all, fewer than 4 fast cores, one
  // cluster) or on a startup mask across both clusters (LR_AFFINITY=ff).
  const bool keep = wanted && startup != 0 && clusters.fast != 0 && !LrMaskSpansClusters(startup, clusters);
  if (!wanted && !diag) return;
  std::thread(KeepAffinity, startup, clusters, keep, diag).detach();
}

}  // namespace lr::android

#endif  // __ANDROID__
