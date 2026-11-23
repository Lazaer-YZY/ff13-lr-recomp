// lr - Android native glue (JNI exports, custom Vulkan driver loading).
// Compiled only on Android (see CMakeLists.txt if(ANDROID) branch).

#pragma once

#include <cstdint>
#include <string>

namespace lr::android {

// On-screen virtual pad state published by RuntimeActivity.nativeSetTouchInput.
// Same layout/semantics as Lost Odyssey Recomp's hid::android_touch::State:
// XInput button bits (0xF3FF mask), triggers 0..255, sticks -32768..32767.
struct TouchSnapshot {
  uint16_t buttons = 0;
  uint8_t left_trigger = 0;
  uint8_t right_trigger = 0;
  int16_t left_x = 0;
  int16_t left_y = 0;
  int16_t right_x = 0;
  int16_t right_y = 0;
};

// Latest complete snapshot (consistent: written and read under one lock).
// TODO: merge into the guest pad state. ReXGlue's SDL input driver has no
// virtual-pad merge hook yet; wire this in once it does.
TouchSnapshot GetTouchSnapshot();
void ClearTouchSnapshot();

// Loads the GPU driver package chosen in the app (LR_VK_CUSTOM_DRIVER*, see
// android/README.md) and hands its vkGetInstanceProcAddr to ReXGlue. Must run
// before the GPU plugin creates its Vulkan instance (LrApp::OnPreSetup).
// Never aborts: any failure logs and leaves the system driver in place.
// Returns true when a custom driver is active.
bool LoadCustomVulkanDriver();

// Setup failed: when the Vulkan code noted why ("<GPU name>: <reason>", e.g. a missing feature), hands
// it to the activity (RuntimeActivity.reportGraphicsFailure), which opens the GPU driver page with it
// once the game closes (LrApp::OnSetupFailed).
void ReportGraphicsFailure();
// A renderer that fails on the GPU thread aborts the process before any activity callback: this
// SIGABRT handler saves the noted reason to <app files>/graphics_failure.txt for the next start's
// GPU driver page (GpuDriverStore.takeGraphicsFailure). Installed before the GPU plugin loads.
void InstallGraphicsFailureSaver();

// Pins the calling thread to the faster CPU clusters (every core whose top clock is above the
// slowest cluster's; LR_AFFINITY=<hex mask> overrides, LR_AFFINITY=all turns it off). Called on
// SDL's main thread before the runtime creates any thread, so every game, GPU and audio thread
// inherits the mask. Returns a description for the log.
std::string PinThreadsToFastCores();
// Logs the CPU clusters' average/peak clocks every 10 s, sampled in-process.
void StartCpuClockSampler();
// The description from the last PinThreadsToFastCores call ("" if never called).
const std::string& PinnedCoresNote();
// The mask PinThreadsToFastCores set (bit n = cpu n); 0 when the game runs on all cores.
uint64_t PinnedCoresMask();

// The affinity keeper (lr_affinity.cpp): once a second, threads whose CPU mask a cpuset change reset
// (Android moved the app from /top-app to /foreground) are pinned again, to their NoteThreadMask mask
// or the startup mask; cpuset changes are logged, and with "Debug logging" (LR_DIAG) a placement line
// every 10 s. LR_AFFINITY_KEEPER=0 turns the pinning off. Call right after PinThreadsToFastCores.
void StartAffinityKeeper();
// A mask LR gave one thread on purpose (bit n = cpu n): the keeper restores it after a reset instead of
// the startup mask. Call again whenever that placement changes.
void NoteThreadMask(int tid, uint64_t mask);
// The game's main thread (the one reporting frames), for the placement line.
void NoteGameMainThread(int tid);

}  // namespace lr::android
