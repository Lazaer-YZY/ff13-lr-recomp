// lr - Android native glue.
//
// - JNI exports for com.lrrecomp.android.RuntimeActivity (touch pad
//   snapshot, controller presence), see android/README.md.
// - Custom Vulkan driver loading (Turnip/PanVK packages via libadrenotools or
//   as a direct ICD), handed to ReXGlue through
//   rex::ui::vulkan::SetInstanceProcAddrOverride before the GPU plugin creates
//   its Vulkan instance.
//
// Adapted from Lost Odyssey Recomp (GPL-3.0): hid/android_touch.cpp and
// gpu/video.cpp LoadCustomVulkanDriver.

#if defined(__ANDROID__)

#include "android/android_glue.h"

#include <atomic>
#include <algorithm>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <functional>
#include <string>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <vector>
#include <cstdio>
#include <cerrno>
#include <dirent.h>
#include <sched.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <fcntl.h>
#include <chrono>
#include <thread>

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>

#include <SDL3/SDL_gamepad.h>

#include <rex/logging/macros.h>

#if defined(LR_HAS_ADRENOTOOLS)
#include <adrenotools/driver.h>
#endif

#if __has_include(<rex/ui/vulkan/loader_override.h>)
#include <rex/ui/vulkan/loader_override.h>
#define LR_HAS_LOADER_OVERRIDE 1
#else
#define LR_HAS_LOADER_OVERRIDE 0
#endif

#define LR_ALOG(prio, ...) __android_log_print(prio, "lr", __VA_ARGS__)

namespace lr::android {

namespace {

std::mutex g_touch_mutex;
TouchSnapshot g_touch;

const char* EnvOr(const char* name, const char* fallback) {
  const char* v = std::getenv(name);
  return (v && *v) ? v : fallback;
}

bool InstallOverride(void* gipa, const char* driver, const char* how) {
#if LR_HAS_LOADER_OVERRIDE
  rex::ui::vulkan::SetInstanceProcAddrOverride(gipa);
  LR_ALOG(ANDROID_LOG_INFO, "vulkan: active driver = custom '%s' (%s)", driver, how);
  REXLOG_INFO("vulkan: active driver = custom '{}' ({})", driver, how);
  return true;
#else
  (void)gipa;
  LR_ALOG(ANDROID_LOG_WARN,
          "vulkan: custom driver '%s' loaded (%s) but this ReXGlue SDK has no "
          "rex/ui/vulkan/loader_override.h; active driver = system",
          driver, how);
  REXLOG_WARN("vulkan: SDK lacks loader_override.h, custom driver '{}' ignored; active driver = system",
              driver);
  return false;
#endif
}

#if defined(LR_HAS_ADRENOTOOLS)
// Direct ICD loading: the package's library in the app's own linker namespace,
// entered through vk_icdGetInstanceProcAddr. Null when it cannot be opened that
// way, so the caller falls back to libadrenotools.
void* OpenCustomVulkanIcd(const std::string& path, const char* driver) {
  void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!handle) {
    const char* err = dlerror();
    LR_ALOG(ANDROID_LOG_WARN,
            "vulkan: custom driver '%s' could not be opened as a Vulkan ICD (%s); trying libadrenotools",
            driver, err ? err : "unknown error");
    return nullptr;
  }
  void* gipa = dlsym(handle, "vk_icdGetInstanceProcAddr");
  if (!gipa) {
    LR_ALOG(ANDROID_LOG_WARN,
            "vulkan: custom driver '%s' exports no vk_icdGetInstanceProcAddr; trying libadrenotools", driver);
    dlclose(handle);
    return nullptr;
  }
  // The Khronos loader negotiates before any other call; Mesa only records it.
  using NegotiateFn = int (*)(uint32_t*);
  if (auto negotiate =
          reinterpret_cast<NegotiateFn>(dlsym(handle, "vk_icdNegotiateLoaderICDInterfaceVersion"))) {
    uint32_t version = 7;
    negotiate(&version);
  }
  return gipa;
}
#endif

}  // namespace

TouchSnapshot GetTouchSnapshot() {
  std::lock_guard lock(g_touch_mutex);
  return g_touch;
}

void ClearTouchSnapshot() {
  std::lock_guard lock(g_touch_mutex);
  g_touch = {};
}

namespace {
std::string g_pinned_cores_note;
}

const std::string& PinnedCoresNote() { return g_pinned_cores_note; }

// On the Thor (Snapdragon 8 Gen 2: cpus 0-2 Cortex-A510, 3-6 A715/A710, 7 X3) the game's
// worker threads were often scheduled on the little cores, which stretched the jobs the main
// thread waits for: 28% of displayed frames were held 1 or 3 refreshes instead of 2 at the title
// menu. On cpus 3-7: 16%, an exact 30.0 fps average, and less CPU time overall (113% vs 143%).
std::string PinThreadsToFastCores() {
  const char* env = std::getenv("LR_AFFINITY");
  const std::string want = env ? env : "";
  std::string& note = g_pinned_cores_note;
  if (want == "all" || want == "0") {
    note = "all cores (LR_AFFINITY=" + want + ")";
    return note;
  }
  cpu_set_t set;
  CPU_ZERO(&set);
  if (!want.empty()) {
    const unsigned long long mask = std::strtoull(want.c_str(), nullptr, 16);
    for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
      if ((mask >> cpu) & 1) CPU_SET(cpu, &set);
    }
  } else {
    std::vector<long> max_khz;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
      char path[96];
      std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
      FILE* f = std::fopen(path, "r");
      if (!f) break;
      long khz = 0;
      if (std::fscanf(f, "%ld", &khz) != 1) khz = 0;
      std::fclose(f);
      max_khz.push_back(khz);
    }
    if (max_khz.empty()) {
      note = "all cores (no cpufreq info)";
      return note;
    }
    // The middle cluster: neither the efficiency cores nor a lone prime core.
    // On the Thor (SD8G2: 3x A510 2.0 GHz, 4x A715/A710 2.8 GHz, 1x X3 3.2 GHz)
    // that is cpus 3-6. Measured 2026-11-08 at the title menu: with the X3
    // allowed, the game's threads straddled two core speeds, 65 frames per
    // 10 s ran over 45 ms and the X3 averaged 2.8 GHz; on cpus 3-6 every frame
    // was paced (125/125 shown for two refreshes), process CPU fell from 76% to
    // 54% of a core and the peak temperature by ~4 C. A prime tier is skipped
    // only when it is small (at most 2 CPUs) and the middle tier keeps >= 4.
    const long slowest = *std::min_element(max_khz.begin(), max_khz.end());
    const long fastest = *std::max_element(max_khz.begin(), max_khz.end());
    int fastest_count = 0, middle_count = 0;
    for (long khz : max_khz) {
      fastest_count += khz == fastest;
      middle_count += khz > slowest && khz < fastest;
    }
    const bool skip_prime = fastest > slowest && fastest_count <= 2 && middle_count >= 4;
    for (size_t cpu = 0; cpu < max_khz.size(); ++cpu) {
      if (max_khz[cpu] > slowest && !(skip_prime && max_khz[cpu] == fastest)) {
        CPU_SET(int(cpu), &set);
      }
    }
  }
  if (CPU_COUNT(&set) == 0) {
    note = "all cores (one cluster or empty mask)";
    return note;
  }
  std::string cpus;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &set)) cpus += (cpus.empty() ? "" : ",") + std::to_string(cpu);
  }
  if (sched_setaffinity(0, sizeof(set), &set) != 0) {
    note = "all cores (sched_setaffinity failed: " + std::string(std::strerror(errno)) + ")";
  } else {
    note = "cpus " + cpus + (want.empty() ? " (middle cluster, not the prime core)" : " (LR_AFFINITY)");
  }
  __android_log_print(ANDROID_LOG_INFO, "lrrecomp", "game threads on %s", note.c_str());
  return note;
}

// CPU clocks as the game sees them: every 100 ms this thread reads each cluster's
// scaling_cur_freq through descriptors opened once (no process spawns, which made adb-shell
// sampling read high), and every 10 s logs the averages ("LR CPU clocks").
#if defined(LR_PGO_GENERATE)
extern "C" int __llvm_profile_write_file(void);
extern "C" void __llvm_profile_set_filename(const char*);
// Instrumented build (LR_PGO=generate): Android kills the app instead of letting it exit, so the
// profile is written every 30 s to <app files dir>/pgo/lr.profraw (counters accumulate in
// memory; the last write holds the whole session).
void StartPgoWriter() {
  const char* dir = std::getenv("REX_ANDROID_APP_DIR");
  if (!dir) return;
  static std::string path;
  std::string pgo = std::string(dir) + "pgo";
  mkdir(pgo.c_str(), 0770);
  path = pgo + "/lr.profraw";
  __llvm_profile_set_filename(path.c_str());
  std::thread([] {
    for (;;) {
      std::this_thread::sleep_for(std::chrono::seconds(30));
      const int rc = __llvm_profile_write_file();
      REXLOG_INFO("LR PGO: profile written to {} (rc {})", path, rc);
    }
  }).detach();
}
#endif

void StartCpuClockSampler() {
  static std::atomic<bool> started{false};
  if (started.exchange(true)) return;
#if defined(LR_PGO_GENERATE)
  StartPgoWriter();
#endif
  std::thread([] {
    std::vector<std::pair<int, int>> policies;  // (first cpu, fd)
    for (int cpu = 0; cpu < 16; ++cpu) {
      char path[96];
      std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpufreq/policy%d/scaling_cur_freq", cpu);
      int fd = open(path, O_RDONLY | O_CLOEXEC);
      if (fd >= 0) policies.emplace_back(cpu, fd);
    }
    if (policies.empty()) return;
    std::vector<uint64_t> sum(policies.size()), peak(policies.size());
    uint32_t samples = 0;
    auto window_start = std::chrono::steady_clock::now();
    for (;;) {
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
      for (size_t i = 0; i < policies.size(); ++i) {
        char buf[32] = {};
        ssize_t n = pread(policies[i].second, buf, sizeof(buf) - 1, 0);
        uint64_t khz = n > 0 ? std::strtoull(buf, nullptr, 10) : 0;
        sum[i] += khz;
        peak[i] = std::max(peak[i], khz);
      }
      ++samples;
      if (std::chrono::steady_clock::now() - window_start >= std::chrono::seconds(10)) {
        std::string line;
        for (size_t i = 0; i < policies.size(); ++i) {
          line += (i ? ", " : "") + std::string("cpu") + std::to_string(policies[i].first) + "+ avg " +
                  std::to_string(sum[i] / samples / 1000) + " max " + std::to_string(peak[i] / 1000) + " MHz";
          sum[i] = peak[i] = 0;
        }
        REXLOG_INFO("LR CPU clocks (10 s, in-process, {} samples): {}", samples, line);
        samples = 0;
        window_start = std::chrono::steady_clock::now();
      }
    }
  }).detach();
}

bool LoadCustomVulkanDriver() {
  const char* driver = std::getenv("LR_VK_CUSTOM_DRIVER");
  if (!driver || !*driver) {
    LR_ALOG(ANDROID_LOG_INFO, "vulkan: active driver = system (no custom driver selected)");
    REXLOG_INFO("vulkan: active driver = system (no custom driver selected)");
    return false;
  }
#if !defined(LR_HAS_ADRENOTOOLS)
  LR_ALOG(ANDROID_LOG_WARN, "vulkan: built without libadrenotools, ignoring '%s'; active driver = system",
          driver);
  return false;
#else
  // Adreno 650 / Turnip draw-ordering workaround; keep an explicit TU_DEBUG.
  const char* sync_draw = std::getenv("LR_VK_ADRENO650_SYNC_DRAW");
  if (sync_draw && std::strcmp(sync_draw, "1") == 0 && !std::getenv("TU_DEBUG")) {
    setenv("TU_DEBUG", "syncdraw", 0);
    LR_ALOG(ANDROID_LOG_INFO, "vulkan: Adreno 650 Turnip default TU_DEBUG=syncdraw applied");
  }
  // Turnip reserves one of the Adreno 6xx bindless sets for dynamic offsets
  // unless told not to; Mesa reads driconf options from the environment.
  setenv("tu_dont_reserve_descriptor_set", "true", 0);

  const char* hook_dir = EnvOr("LR_NATIVE_LIB_DIR", "");
  const char* driver_dir = EnvOr("LR_CUSTOM_DRIVER_DIR", "");
  const char* mode = EnvOr("LR_VK_CUSTOM_DRIVER_MODE", "adrenotools");

  if (std::strcmp(mode, "icd") == 0) {
    if (void* gipa = OpenCustomVulkanIcd(std::string(driver_dir) + driver, driver)) {
      return InstallOverride(gipa, driver, "direct Vulkan ICD");
    }
  } else if (std::strcmp(mode, "adrenotools") != 0) {
    LR_ALOG(ANDROID_LOG_WARN, "vulkan: unknown LR_VK_CUSTOM_DRIVER_MODE '%s', using adrenotools", mode);
  }

  void* handle = adrenotools_open_libvulkan(RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, nullptr, hook_dir,
                                            driver_dir, driver, nullptr, nullptr);
  if (!handle) {
    const char* err = dlerror();
    LR_ALOG(ANDROID_LOG_ERROR,
            "vulkan: custom driver '%s' failed to load (hooks '%s', dir '%s': %s); active driver = system",
            driver, hook_dir, driver_dir, err ? err : "no dlerror");
    REXLOG_ERROR("vulkan: custom driver '{}' failed to load; active driver = system", driver);
    return false;
  }
  void* gipa = dlsym(handle, "vkGetInstanceProcAddr");
  if (!gipa) {
    LR_ALOG(ANDROID_LOG_ERROR,
            "vulkan: custom driver '%s' has no vkGetInstanceProcAddr; active driver = system", driver);
    REXLOG_ERROR("vulkan: custom driver '{}' has no vkGetInstanceProcAddr; active driver = system", driver);
    return false;
  }
  // libadrenotools resolves the package lazily: an unloadable .so still ends up
  // on the system driver, which the GPU plugin's device log line reveals.
  return InstallOverride(gipa, driver, "libadrenotools");
#endif
}

}  // namespace lr::android

// ---------------------------------------------------------------------------
// JNI: com.lrrecomp.android.RuntimeActivity
// ---------------------------------------------------------------------------

extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeSetTouchInput(
    JNIEnv*, jclass, jint buttons, jint leftTrigger, jint rightTrigger, jint leftX, jint leftY,
    jint rightX, jint rightY) {
  const lr::android::TouchSnapshot next{
      static_cast<uint16_t>(buttons & 0xF3FF),
      static_cast<uint8_t>(std::clamp<jint>(leftTrigger, 0, 255)),
      static_cast<uint8_t>(std::clamp<jint>(rightTrigger, 0, 255)),
      static_cast<int16_t>(std::clamp<jint>(leftX, -32768, 32767)),
      static_cast<int16_t>(std::clamp<jint>(leftY, -32768, 32767)),
      static_cast<int16_t>(std::clamp<jint>(rightX, -32768, 32767)),
      static_cast<int16_t>(std::clamp<jint>(rightY, -32768, 32767)),
  };
  {
    std::lock_guard lock(lr::android::g_touch_mutex);
    lr::android::g_touch = next;
  }
  // Merge into controller 1 through the ported hooks' touch layer (lr_hooks.cpp).
  // TODO: RuntimeActivity sends no explicit "overlay visible" flag; treat any input as active.
  const bool active = next.buttons || next.left_trigger || next.right_trigger || next.left_x ||
                      next.left_y || next.right_x || next.right_y;
  LrTouchSetPad(active, next.buttons, next.left_trigger, next.right_trigger, next.left_x,
                next.left_y, next.right_x, next.right_y);
}

extern "C" __attribute__((visibility("default"))) JNIEXPORT jboolean JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeHasConnectedController(JNIEnv*, jclass) {
  return SDL_HasGamepad() ? JNI_TRUE : JNI_FALSE;
}

// lr_hooks.cpp (the game's Settings items on PC; on Android driven from the CTRL dialog).
bool LrConfigSetFps60(int choice);
void LrConfigSetAa(int choice);
// lr_app.h: the frame rate / frame time overlay (F2 on PC), toggled on the UI thread.
extern std::function<void(bool)> g_lr_frame_overlay_ui;

// Live display options from the CTRL dialog: "fps" = "30" | "60g" (60 in gameplay, cutscenes
// 30) | "60"; "aa" = "off" | "fxaa" | "extreme". Called on the Java UI thread; the setters
// only store atomics the game threads read.
extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeSetOption(JNIEnv* env, jclass,
                                                                     jstring jname,
                                                                     jstring jvalue) {
  if (!jname || !jvalue) return;
  const char* name_chars = env->GetStringUTFChars(jname, nullptr);
  const char* value_chars = env->GetStringUTFChars(jvalue, nullptr);
  std::string name = name_chars ? name_chars : "";
  std::string value = value_chars ? value_chars : "";
  if (name_chars) env->ReleaseStringUTFChars(jname, name_chars);
  if (value_chars) env->ReleaseStringUTFChars(jvalue, value_chars);
  if (name == "fps") {
    LrConfigSetFps60(value == "40" ? 4 : value == "60g" ? 1 : value == "60" ? 2 : 0);
  } else if (name == "aa") {
    LrConfigSetAa(value == "fxaa" ? 1 : value == "extreme" ? 2 : 0);
  } else if (name == "overlay") {
    if (g_lr_frame_overlay_ui) g_lr_frame_overlay_ui(value == "1");
  } else {
    __android_log_print(ANDROID_LOG_WARN, "lr", "nativeSetOption: unknown option '%s'",
                        name.c_str());
  }
}

// Second-screen companion (CompanionPresentation.java, src/lr_companion.inc). Polled on a Java
// worker thread; only reads atomics and guest words.
extern "C" __attribute__((visibility("default"))) JNIEXPORT jstring JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeCompanionState(JNIEnv* env, jclass) {
  char buf[4096];
  LrCompanionState(buf, sizeof(buf));
  return env->NewStringUTF(buf);
}

// The follow map's pose at the display rate: fills {x, z, heading, camheading}; false = not in
// the field. Called on the bottom screen's drawing thread.
extern "C" __attribute__((visibility("default"))) JNIEXPORT jboolean JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeCompanionPose(JNIEnv* env, jclass,
                                                                        jfloatArray jout) {
  float pose[4];
  if (!jout || env->GetArrayLength(jout) < 4 || !LrCompanionPose(pose)) return JNI_FALSE;
  env->SetFloatArrayRegion(jout, 0, 4, pose);
  return JNI_TRUE;
}

// Presses XInput buttons (LB 0x0100, RB 0x0200, ...) on controller 1 for ms milliseconds.
extern "C" __attribute__((visibility("default"))) JNIEXPORT void JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeCompanionPress(JNIEnv*, jclass,
                                                                         jint buttons, jint ms) {
  LrCompanionPress(static_cast<uint16_t>(buttons & 0xF3FF),
                   static_cast<uint32_t>(std::clamp<jint>(ms, 16, 2000)));
}

// Pins the calling thread (the bottom screen's drawing thread) to the little cores: the CPUs with
// the lowest maximum clock (the Thor: cpu0-2, Cortex-A510), away from the game's threads on the
// middle and prime cores. Returns a line for the log.
extern "C" __attribute__((visibility("default"))) JNIEXPORT jstring JNICALL
Java_com_lrrecomp_android_RuntimeActivity_nativeCompanionPinLittleCores(JNIEnv* env,
                                                                                  jclass) {
  long lowest = -1;
  long freq[64];
  int n = 0;
  for (; n < 64; ++n) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", n);
    FILE* f = std::fopen(path, "r");
    if (!f) break;
    long v = 0;
    if (std::fscanf(f, "%ld", &v) != 1) v = 0;
    std::fclose(f);
    freq[n] = v;
    if (v > 0 && (lowest < 0 || v < lowest)) lowest = v;
  }
  cpu_set_t set;
  CPU_ZERO(&set);
  std::string cpus;
  int picked = 0;
  for (int i = 0; i < n; ++i) {
    if (freq[i] != lowest) continue;
    CPU_SET(i, &set);
    cpus += (cpus.empty() ? "" : ",") + std::to_string(i);
    ++picked;
  }
  std::string result;
  if (picked == 0 || picked == n) {
    result = "not pinned (no separate little cores)";
  } else if (sched_setaffinity(0, sizeof(set), &set) != 0) {
    result = "not pinned (sched_setaffinity: " + std::string(std::strerror(errno)) + ")";
  } else {
    result = "pinned to cpu " + cpus + " (" + std::to_string(lowest / 1000) + " MHz cores)";
  }
  return env->NewStringUTF(result.c_str());
}

// ---------------------------------------------------------------------------
// Declared in lr_android.h, called from the ported lr_hooks.cpp.
// ---------------------------------------------------------------------------

// TODO: lr-ffxiii-port relaunches through a Java method (LrActivity.lrRestartApp). Our
// RuntimeActivity has no equivalent yet; returning false makes the caller keep running.
bool LrAndroidRestartProcess(int generation) {
  __android_log_print(ANDROID_LOG_WARN, "lr", "LrAndroidRestartProcess(%d): not implemented",
                      generation);
  return false;
}

// ADPF (Android performance hint API, API 33+, loaded with dlsym because minSdk is 26): every
// frame reports the main thread's work time (frame minus the pacing sleep) against the frame
// budget, so the CPU governor runs the cores just fast enough for 30 fps instead of boosting the
// big core (the Thor reached 96 C in the first battle). The session covers the main thread, the
// GPU command thread and the effect workers, the threads on the per-frame critical path.
// LR_ADPF=0 disables it (A/B for heat and frame rate).
namespace {
struct LrAdpf {
  using GetManager = void* (*)();
  using CreateSession = void* (*)(void*, const int32_t*, size_t, int64_t);
  using UpdateTarget = int (*)(void*, int64_t);
  using ReportActual = int (*)(void*, int64_t);
  GetManager get_manager = nullptr;
  CreateSession create_session = nullptr;
  UpdateTarget update_target = nullptr;
  ReportActual report_actual = nullptr;
  void* session = nullptr;
  int64_t target_ns = 0;
  bool disabled = false;
};
LrAdpf g_lr_adpf;
std::atomic<uint64_t> g_lr_game_frames{0};

std::vector<int32_t> LrAdpfThreads() {
  std::vector<int32_t> tids = {int32_t(gettid())};
  if (DIR* dir = opendir("/proc/self/task")) {
    while (dirent* e = readdir(dir)) {
      if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
      char path[64], comm[32] = {};
      snprintf(path, sizeof(path), "/proc/self/task/%s/comm", e->d_name);
      if (FILE* f = fopen(path, "r")) {
        size_t n = fread(comm, 1, sizeof(comm) - 1, f);
        fclose(f);
        comm[n] = 0;
      }
      int32_t tid = int32_t(atoi(e->d_name));
      if (tid != tids[0] && (strncmp(comm, "GPU Commands", 12) == 0 || strncmp(comm, "vfx-qix-effect", 14) == 0) &&
          tids.size() < 8) {
        tids.push_back(tid);
      }
    }
    closedir(dir);
  }
  return tids;
}

void LrAdpfStart(int64_t target_ns) {
  auto& a = g_lr_adpf;
  const char* env = getenv("LR_ADPF");
  if (env && env[0] == '0') {
    a.disabled = true;
    __android_log_print(ANDROID_LOG_INFO, "lr", "ADPF: disabled (LR_ADPF=0)");
    return;
  }
  void* lib = dlopen("libandroid.so", RTLD_NOW);
  if (lib) {
    a.get_manager = reinterpret_cast<LrAdpf::GetManager>(dlsym(lib, "APerformanceHint_getManager"));
    a.create_session = reinterpret_cast<LrAdpf::CreateSession>(dlsym(lib, "APerformanceHint_createSession"));
    a.update_target =
        reinterpret_cast<LrAdpf::UpdateTarget>(dlsym(lib, "APerformanceHint_updateTargetWorkDuration"));
    a.report_actual =
        reinterpret_cast<LrAdpf::ReportActual>(dlsym(lib, "APerformanceHint_reportActualWorkDuration"));
  }
  void* manager = a.get_manager ? a.get_manager() : nullptr;
  std::vector<int32_t> tids = LrAdpfThreads();
  if (manager && a.create_session && a.report_actual) {
    a.session = a.create_session(manager, tids.data(), tids.size(), target_ns);
  }
  a.target_ns = target_ns;
  if (!a.session) a.disabled = true;
  __android_log_print(ANDROID_LOG_INFO, "lr", "ADPF: %s (%zu threads, target %.1f ms)",
                      a.session ? "session created" : "unavailable", tids.size(), target_ns / 1e6);
  REXLOG_INFO("LR ADPF: {} ({} threads: main, GPU commands, effect workers; target {:.1f} ms)",
              a.session ? "session created" : "unavailable", tids.size(), target_ns / 1e6);
}
}  // namespace

namespace {
// LR_UCLAMP_MAX=<1..1023>: every ~10 s, cap the utilization clamp of every thread of the
// process (sched_setattr with SCHED_FLAG_UTIL_CLAMP_MAX), so WALT cannot clock their cores
// up for short bursts. Experiment for heat (2026-11-09): the middle cluster averaged ~2.5 GHz
// while each core was ~25% busy and the main thread waited ~18 ms per frame.
struct LrSchedAttr {
  uint32_t size;
  uint32_t sched_policy;
  uint64_t sched_flags;
  int32_t sched_nice;
  uint32_t sched_priority;
  uint64_t sched_runtime, sched_deadline, sched_period;
  uint32_t sched_util_min, sched_util_max;
};
void LrApplyUclampMax() {
  static const int cap = [] {
    const char* v = std::getenv("LR_UCLAMP_MAX");
    return v ? atoi(v) : 0;
  }();
  if (cap <= 0 || cap > 1024) return;
  DIR* dir = opendir("/proc/self/task");
  if (!dir) return;
  int ok = 0, failed = 0, err = 0;
  while (dirent* e = readdir(dir)) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    LrSchedAttr attr{};
    attr.size = sizeof(attr);
    attr.sched_flags = 0x08 | 0x10 | 0x40;  // KEEP_POLICY | KEEP_PARAMS | UTIL_CLAMP_MAX
    attr.sched_util_max = uint32_t(cap);
    if (syscall(SYS_sched_setattr, atoi(e->d_name), &attr, 0) == 0) {
      ++ok;
    } else {
      ++failed;
      err = errno;
    }
  }
  closedir(dir);
  static int last_ok = -1;
  if (ok != last_ok) {
    last_ok = ok;
    REXLOG_INFO("LR uclamp.max {}: {} threads set, {} failed{}", cap, ok, failed,
                failed ? std::string(" (") + std::strerror(err) + ")" : std::string());
  }
}

// LR_AUX_LITTLE=1: every ~10 s, threads off the frame's critical path (audio decoding and
// mixing, the sound streaming tasks, SDL audio; not "CDevCommon Task": those are the job
// workers on the frame's critical path) are moved to the
// efficiency cores. Tried 2026-11-09: the middle cluster's clock did not move (~2.5 GHz), so it
// stays off.
void LrPlaceAuxThreads() {
  LrApplyUclampMax();
  static const bool enabled = [] {
    const char* v = std::getenv("LR_AUX_LITTLE");
    return v && v[0] == '1';
  }();
  if (!enabled) return;
  static cpu_set_t little;
  static bool little_ready = false;
  if (!little_ready) {
    CPU_ZERO(&little);
    long slowest = 0;
    std::vector<long> max_khz;
    for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
      char path[96];
      std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
      FILE* f = std::fopen(path, "r");
      if (!f) break;
      long khz = 0;
      if (std::fscanf(f, "%ld", &khz) != 1) khz = 0;
      std::fclose(f);
      max_khz.push_back(khz);
    }
    if (max_khz.empty()) return;
    slowest = *std::min_element(max_khz.begin(), max_khz.end());
    for (size_t cpu = 0; cpu < max_khz.size(); ++cpu) {
      if (max_khz[cpu] == slowest) CPU_SET(int(cpu), &little);
    }
    little_ready = true;
  }
  static const char* const kAux[] = {"XMA Decoder", "Audio Worker", "CDevSd",
                                     "SDLAudio"};
  static std::vector<int> moved;
  DIR* dir = opendir("/proc/self/task");
  if (!dir) return;
  int count = 0;
  std::string names;
  while (dirent* e = readdir(dir)) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    const int tid = atoi(e->d_name);
    if (std::find(moved.begin(), moved.end(), tid) != moved.end()) continue;
    char path[64], comm[32] = {};
    snprintf(path, sizeof(path), "/proc/self/task/%s/comm", e->d_name);
    if (FILE* f = fopen(path, "r")) {
      size_t n = fread(comm, 1, sizeof(comm) - 1, f);
      fclose(f);
      comm[n] = 0;
      if (n && comm[n - 1] == 10) comm[n - 1] = 0;  // trailing newline
    }
    bool aux = false;
    for (const char* prefix : kAux) aux |= strncmp(comm, prefix, strlen(prefix)) == 0;
    if (!aux) continue;
    if (sched_setaffinity(tid, sizeof(little), &little) == 0) {
      moved.push_back(tid);
      ++count;
      names += std::string(names.empty() ? "" : ", ") + comm;
    }
  }
  closedir(dir);
  if (count) REXLOG_INFO("LR threads: {} moved to the efficiency cores ({})", count, names);
}

// LR_MAIN_PRIME=1: the guest main thread alone on the fastest cluster (the prime core on the
// Thor, 3.2 GHz vs the middle cluster's 2.7-2.8); every other thread stays where
// PinThreadsToFastCores put it. For the CPU-bound town scenes (Yusnaan: game code ~15-16 ms per
// frame on the middle cluster: 11.3-13.5 on the prime, +7 C average). Default: on when the game
// starts in the 60 fps mode (LR_FPS=60...), off at 30/40 (heat); LR_MAIN_PRIME=1/0 overrides.
// Several threads are named "Main Thread (F8..."; the busiest one (most CPU time) is the game's.
// LR_MAIN_PRIME: 1 = the guest main thread only on the prime core(s); 2 (default with LR_FPS=60...)
// = prime and middle cores (everything but the slowest cluster); 0 = no affinity. Prime only was
// faster on average (game code -20% vs none) but caused bursts of 50-65 ms frames whenever the
// game got light for a moment: Qualcomm core_ctl parks the prime cluster (min_cpus 0), and a main
// thread allowed only there cannot run until it comes back (2026-11-10 warp bench, timeline
// recorder: woken 5 ms late with every thread idle). With the middle cores allowed the scheduler
// still keeps the busy main thread on the prime core while it is up.
void LrPlaceMainOnPrime() {
  static const int mode = [] {
    const char* v = std::getenv("LR_MAIN_PRIME");
    if (v && v[0]) return v[0] - '0';
    const char* fps = std::getenv("LR_FPS");
    return fps && std::strncmp(fps, "60", 2) == 0 ? 2 : 0;
  }();
  static bool done = false;
  if ((mode != 1 && mode != 2) || done) return;
  std::vector<long> max_khz;
  for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
    char path[96];
    std::snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE* f = std::fopen(path, "r");
    if (!f) break;
    long khz = 0;
    if (std::fscanf(f, "%ld", &khz) != 1) khz = 0;
    std::fclose(f);
    max_khz.push_back(khz);
  }
  if (max_khz.empty()) return;
  const long fastest = *std::max_element(max_khz.begin(), max_khz.end());
  const long slowest = *std::min_element(max_khz.begin(), max_khz.end());
  cpu_set_t prime;
  CPU_ZERO(&prime);
  std::string cpus;
  for (size_t cpu = 0; cpu < max_khz.size(); ++cpu) {
    if (mode == 1 ? max_khz[cpu] == fastest : (max_khz[cpu] > slowest || slowest == fastest)) {
      CPU_SET(int(cpu), &prime);
      cpus += (cpus.empty() ? "" : ",") + std::to_string(cpu);
    }
  }
  DIR* dir = opendir("/proc/self/task");
  if (!dir) return;
  int best_tid = 0;
  unsigned long long best_ticks = 0;
  while (dirent* e = readdir(dir)) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    char path[64], stat[512] = {};
    snprintf(path, sizeof(path), "/proc/self/task/%s/stat", e->d_name);
    FILE* f = fopen(path, "r");
    if (!f) continue;
    size_t n = fread(stat, 1, sizeof(stat) - 1, f);
    fclose(f);
    stat[n] = 0;
    // "tid (comm) state ..." - comm may contain spaces; fields resume after the last ')'.
    const char* open = strchr(stat, '(');
    const char* close = strrchr(stat, ')');
    if (!open || !close || strncmp(open + 1, "Main Thread", 11) != 0) continue;
    unsigned long long utime = 0, stime = 0;
    // After ") ": state(3) ppid pgrp session tty_nr tpgid flags minflt cminflt majflt cmajflt
    // utime(14) stime(15).
    if (sscanf(close + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &utime,
               &stime) != 2) {
      continue;
    }
    if (utime + stime > best_ticks) {
      best_ticks = utime + stime;
      best_tid = atoi(e->d_name);
    }
  }
  closedir(dir);
  if (best_tid && sched_setaffinity(best_tid, sizeof(prime), &prime) == 0) {
    done = true;
    REXLOG_INFO("LR threads: guest main thread {} moved to cpus {} (LR_MAIN_PRIME={})", best_tid,
                cpus, mode);
  }
}

// LR_VFX_MASK=<hex>: CPU mask for the four 'vfx-qix-effect' workers. They run each effect phase
// as a shared lock-free list with 4-thread spin barriers between sub-stages (sub_82C45F58), so
// one worker preempted on the middle cluster (shared with the GPU command thread) stalls the
// phase while the main thread waits in sub_82E0C480 - and with LR_MAIN_PRIME the prime core is
// idle exactly then (the main thread is waiting for these workers). f8 = cpus 3-7.
void LrPlaceEffectWorkers() {
  static const unsigned long long mask = [] {
    const char* v = std::getenv("LR_VFX_MASK");
    return v && v[0] ? std::strtoull(v, nullptr, 16) : 0ull;
  }();
  static int placed = 0;
  if (!mask || placed >= 4) return;
  cpu_set_t set;
  CPU_ZERO(&set);
  for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
    if ((mask >> cpu) & 1) CPU_SET(cpu, &set);
  }
  DIR* dir = opendir("/proc/self/task");
  if (!dir) return;
  int count = 0;
  while (dirent* e = readdir(dir)) {
    if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
    char path[64], comm[32] = {};
    snprintf(path, sizeof(path), "/proc/self/task/%s/comm", e->d_name);
    if (FILE* f = fopen(path, "r")) {
      size_t n = fread(comm, 1, sizeof(comm) - 1, f);
      fclose(f);
      comm[n] = 0;
    }
    if (strncmp(comm, "vfx-qix-effect", 14) != 0) continue;
    if (sched_setaffinity(atoi(e->d_name), sizeof(set), &set) == 0) ++count;
  }
  closedir(dir);
  if (count) {
    placed = count;
    REXLOG_INFO("LR threads: {} effect workers on CPU mask {:x} (LR_VFX_MASK)", count, mask);
  }
}

// Adreno hardware counters through the kernel driver (LR_GPU_COUNTERS=1; works with Turnip and
// Qualcomm, no root): IOCTL_KGSL_PERFCOUNTER_GET reserves a counter for a countable (the kernel
// programs the select register), IOCTL_KGSL_PERFCOUNTER_READ reads the running 64-bit values.
// The counters are global (whole GPU), so read them on a steady view. Every LR_GPU_COUNTERS_S
// seconds (default 10) logs "LR GPU counters" as values per game frame plus derived ratios.
// Countables: a7xx (Adreno 7xx) numbering from Mesa's a7xx_perfcntrs.xml.
struct KgslPerfcounterGet {
  uint32_t groupid, countable, offset, offset_hi, pad;
};
struct KgslPerfcounterReadGroup {
  uint32_t groupid, countable;
  uint64_t value;
};
struct KgslPerfcounterRead {
  KgslPerfcounterReadGroup* reads;
  uint32_t count;
  uint32_t pad[2];
};
#define LR_KGSL_IOC_PERFCOUNTER_GET _IOWR(0x09, 0x38, KgslPerfcounterGet)
#define LR_KGSL_IOC_PERFCOUNTER_READ _IOWR(0x09, 0x3B, KgslPerfcounterRead)

struct GpuCountable {
  uint32_t group, countable;
  const char* name;
};
// Groups: 0 CP, 8 UCHE, 9 TP, 0xA SP, 0xB RB (the GBIF/VBIF ones are kept by the kernel: EBUSY).
constexpr GpuCountable kGpuCountables[] = {
    {0x0, 0, "cp_always"},          {0x0, 2, "cp_busy"},
    {0xA, 0, "sp_busy"},            {0xA, 1, "sp_alu_working"},     {0xA, 2, "sp_efu_working"},
    {0xA, 4, "sp_stall_tp"},        {0xA, 5, "sp_stall_uche"},      {0xA, 6, "sp_stall_rb"},
    {0xA, 7, "sp_non_exec"},        {0xA, 9, "sp_wave_ctx_cycles"}, {0xA, 8, "sp_wave_contexts"},
    {0xA, 37, "sp_fs_tex_instr"},   {0xA, 40, "sp_fs_full_alu"},    {0xA, 41, "sp_fs_half_alu"},
    {0xA, 39, "sp_fs_efu"},         {0xA, 52, "sp_icl1_miss"},      {0xA, 68, "sp_starved_by_tp"},
    {0xA, 74, "sp_any_eu_fs"},      {0xA, 76, "sp_any_eu_vs"},      {0xA, 35, "sp_vs_full_alu"},
    {0xA, 43, "sp_vs_instr"},       {0xA, 44, "sp_fs_instr"},       {0xA, 14, "sp_fs_duration"},
    {0xA, 15, "sp_vs_duration"},
    {0x9, 0, "tp_busy"},            {0x9, 1, "tp_stall_uche"},      {0x9, 6, "tp_l1_req"},
    {0x9, 7, "tp_l1_miss"},         {0x9, 10, "tp_out_px"},         {0x9, 26, "tp_out_bilinear"},
    {0x9, 27, "tp_out_mip"},        {0x9, 28, "tp_out_aniso"},      {0x9, 12, "tp_filter_32bit"},
    {0x9, 44, "tp_l1_bank_conflict"},
    {0x8, 0, "uche_busy"},          {0x8, 1, "uche_stall_arb"},     {0x8, 4, "uche_rd_beats_tp"},
    {0x8, 2, "uche_vbif_lat_cyc"},  {0x8, 3, "uche_vbif_lat_n"},
    {0xB, 0, "rb_busy"},            {0xB, 44, "rb_stall_ccu"},      {0xB, 5, "rb_starve_sp"},
    {0xB, 14, "rb_c_read"},         {0xB, 15, "rb_c_write"},        {0xB, 12, "rb_z_read"},
    {0xB, 13, "rb_z_write"},        {0xB, 22, "rb_ps_invocations"},
};

void StartGpuCounterSampler() {
  const char* env = std::getenv("LR_GPU_COUNTERS");
  if (!env || std::strcmp(env, "1") != 0) return;
  const char* period_env = std::getenv("LR_GPU_COUNTERS_S");
  const int period_s = std::max(1, period_env ? std::atoi(period_env) : 10);
  std::thread([period_s] {
    int fd = open("/dev/kgsl-3d0", O_RDWR | O_CLOEXEC);
    if (fd < 0) {
      REXLOG_WARN("LR GPU counters: open /dev/kgsl-3d0 failed (errno {})", errno);
      return;
    }
    std::vector<KgslPerfcounterReadGroup> reads;
    std::vector<const char*> names;
    std::string failed;
    for (const GpuCountable& c : kGpuCountables) {
      KgslPerfcounterGet get = {c.group, c.countable, 0, 0, 0};
      if (ioctl(fd, LR_KGSL_IOC_PERFCOUNTER_GET, &get) != 0) {
        failed += std::string(failed.empty() ? "" : ", ") + c.name + " (errno " + std::to_string(errno) + ")";
        continue;
      }
      reads.push_back({c.group, c.countable, 0});
      names.push_back(c.name);
    }
    REXLOG_INFO("LR GPU counters: {} reserved, failed: {}", reads.size(), failed.empty() ? "none" : failed);
    if (reads.empty()) return;
    auto read_all = [&](std::vector<uint64_t>& out) {
      KgslPerfcounterRead rd = {reads.data(), uint32_t(reads.size()), {0, 0}};
      if (ioctl(fd, LR_KGSL_IOC_PERFCOUNTER_READ, &rd) != 0) return false;
      out.resize(reads.size());
      for (size_t i = 0; i < reads.size(); ++i) out[i] = reads[i].value;
      return true;
    };
    std::vector<uint64_t> prev, cur;
    if (!read_all(prev)) {
      REXLOG_WARN("LR GPU counters: PERFCOUNTER_READ failed (errno {})", errno);
      return;
    }
    uint64_t prev_frames = g_lr_game_frames.load(std::memory_order_relaxed);
    for (;;) {
      std::this_thread::sleep_for(std::chrono::seconds(period_s));
      if (!read_all(cur)) continue;
      const uint64_t frames_now = g_lr_game_frames.load(std::memory_order_relaxed);
      const double frames = double(std::max<uint64_t>(1, frames_now - prev_frames));
      auto delta = [&](const char* name) -> double {
        for (size_t i = 0; i < names.size(); ++i)
          if (std::strcmp(names[i], name) == 0) return double(cur[i] - prev[i]);
        return 0.0;
      };
      std::string line;
      for (size_t i = 0; i < names.size(); ++i) {
        char buf[64];
        std::snprintf(buf, sizeof(buf), "%s%s %.3fM", i ? ", " : "", names[i], double(cur[i] - prev[i]) / frames / 1e6);
        line += buf;
      }
      auto pct = [](double a, double b) { return b > 0 ? 100.0 * a / b : 0.0; };
      const double sp_busy = delta("sp_busy"), tp_busy = delta("tp_busy"), cp_busy = delta("cp_busy");
      char derived[512];
      std::snprintf(derived, sizeof(derived),
                    "gpu busy %.1f%%, sp busy %.1f%% of gpu busy, alu %.1f%% / efu %.1f%% / stall tp %.1f%% / stall uche "
                    "%.1f%% of sp busy, tp busy %.1f%% (stall uche %.1f%%), tp l1 miss %.1f%%, uche vbif latency %.0f "
                    "cyc, fs tex/alu instr %.3f, vs share: eu-busy %.1f%% / instr %.1f%%",
                    pct(cp_busy, delta("cp_always")), pct(sp_busy, cp_busy), pct(delta("sp_alu_working"), sp_busy),
                    pct(delta("sp_efu_working"), sp_busy), pct(delta("sp_stall_tp"), sp_busy),
                    pct(delta("sp_stall_uche"), sp_busy), pct(tp_busy, cp_busy), pct(delta("tp_stall_uche"), tp_busy),
                    pct(delta("tp_l1_miss"), delta("tp_l1_req")),
                    delta("uche_vbif_lat_n") > 0 ? delta("uche_vbif_lat_cyc") / delta("uche_vbif_lat_n") : 0.0,
                    delta("sp_fs_full_alu") + delta("sp_fs_half_alu") > 0
                        ? delta("sp_fs_tex_instr") / (delta("sp_fs_full_alu") + delta("sp_fs_half_alu"))
                        : 0.0,
                    pct(delta("sp_any_eu_vs"), delta("sp_any_eu_vs") + delta("sp_any_eu_fs")),
                    pct(delta("sp_vs_instr"), delta("sp_vs_instr") + delta("sp_fs_instr")));
      REXLOG_INFO("LR GPU counters ({} s, {:.0f} frames, per frame): {}", period_s, frames, line);
      REXLOG_INFO("LR GPU counters derived: {}", derived);
      prev.swap(cur);
      prev_frames = frames_now;
    }
  }).detach();
}
}  // namespace

void LrAndroidFrameDone(uint64_t work_us, double frame_rate) {
  uint64_t frames = g_lr_game_frames.fetch_add(1, std::memory_order_relaxed) + 1;
  if (frames == 120) StartGpuCounterSampler();
  if (frames % 300 == 0) {
    LrPlaceAuxThreads();
    LrPlaceMainOnPrime();
    LrPlaceEffectWorkers();
  }
  auto& a = g_lr_adpf;
  if (a.disabled) return;
  int64_t target_ns = int64_t(1e9 / (frame_rate > 1.0 ? frame_rate : 29.97));
  if (!a.session) {
    // Start after ~10 s so the GPU command and effect threads exist.
    if (frames >= 300) LrAdpfStart(target_ns);
    return;
  }
  if (target_ns != a.target_ns && a.update_target) {
    a.update_target(a.session, target_ns);
    a.target_ns = target_ns;
  }
  if (work_us) a.report_actual(a.session, int64_t(work_us) * 1000);
}

#endif  // __ANDROID__
