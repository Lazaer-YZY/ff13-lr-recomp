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

// Pins the calling thread to the faster CPU clusters (every core whose top clock is above the
// slowest cluster's; LR_AFFINITY=<hex mask> overrides, LR_AFFINITY=all turns it off). Called on
// SDL's main thread before the runtime creates any thread, so every game, GPU and audio thread
// inherits the mask. Returns a description for the log.
std::string PinThreadsToFastCores();
// Logs the CPU clusters' average/peak clocks every 10 s, sampled in-process.
void StartCpuClockSampler();
// The description from the last PinThreadsToFastCores call ("" if never called).
const std::string& PinnedCoresNote();

}  // namespace lr::android
