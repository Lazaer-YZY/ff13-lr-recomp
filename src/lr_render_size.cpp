// lr - the game's 3D render resolution (CTRL dialog, Image: "Resolution": 540p / 720p / 1080p), after the Dark Souls
// II port's ds2_render_size.cpp (same SDK; DECISIONS 2026-11-24).
//
// Like DS2, the game takes its screen size from its own renderer setup, not from the video mode it is told about, so
// the console's 1280x720 is rewritten where the engine sets it up (found with lr_surface_probe: every distinct surface
// the D3D library sets up, with its guest call chain). Three sources (Galaxy S10 runs, 2026-11-24):
//  - the render device +3060 / +3064 and the static config's first pair +24 / +28: the 3D scene and its passes. These
//    follow lr_render_height.
//  - the config's second pair +36 / +40 (singleton 0x83147A54 +32 / +36): the final frame (scene + UI) and the UI's
//    layout, which draws 1:1 in that size's pixels. It stays 1280x720: resized, the HUD sat in the top-left 1280x720 of
//    a 1080p screen and was cropped at 540p. So 540p is upscaled into a 720p frame (the UI stays sharp) and 1080p is
//    supersampled into it (smoother edges). A native 1080p frame would work the way the wide frame does below (the UI's
//    readers keep 1280x720, its camera scales the canvas), but its post chain ran at 12 fps against 27 on the S10.
// Wide screens (lr_render_width wider than 16:9; CTRL, Image: "Wide screen"): the 3D widens (Hor+, the perspective
// hooks) and the final frame becomes 720 lines at the screen's aspect, while the UI keeps its 1280x720 canvas in the
// frame's middle: see "Wide screens" at the end (DECISIONS 2026-11-24).
//
// The D3D library functions match DS2's (same XDK code, other addresses): sub_829C17D8 = DS2 sub_829DB778 (surface
// setup: r3 width, r4 height, r5 format, r6 multisample, r7 parameters with the EDRAM base at +0, r8 surface with its
// RB_COLOR_INFO / RB_DEPTH_INFO word at +28), sub_829DBF00 = DS2 sub_829FC1D0 (Clear: device r3, render target index
// r5, the device's render targets at +(3204 + i) * 4, its command write pointer at +48). Both fixes below matter only
// with the SDK's gpu_edram_tiles above 4096 (1080p): the guest keeps 12 bits of EDRAM base.

#include <rex/cvar.h>
#include <rex/hook.h>
#include <rex/logging.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <tuple>
#include <utility>

REXCVAR_DEFINE_INT32(lr_render_height, 0, "LR",
                     "The game's 3D render height (0 = the console's 720; 540 = 960x540, 1080 = 1920x1080 with "
                     "gpu_edram_tiles 4096). Takes effect on restart");
REXCVAR_DEFINE_INT32(lr_render_width, 0, "LR",
                     "The game's screen width (0 = 16:9 of lr_render_height; wider = a wide screen: the 3D fills it, "
                     "the UI keeps its 1280x720 canvas in the middle). Takes effect on restart");
REXCVAR_DEFINE_BOOL(lr_surface_probe, false, "LR",
                    "Diagnostic: log each distinct render target / depth surface the game sets up (size, format, "
                    "multisample, EDRAM base, guest call chain) once");
REXCVAR_DEFINE_INT32(lr_resize_mask, 3, "LR",
                     "Diagnostic: which 1280x720 sources follow the render size (1 render device, 2 config first pair, "
                     "4 config second pair = the final frame and the UI's layout size, 16:9 screens only: the HUD is "
                     "then drawn 1:1 in 1280x720 pixels)");
REXCVAR_DEFINE_INT32(lr_ui_canvas_mask, 63, "LR",
                     "Diagnostic, wide screens: which of the UI's readers keep the 1280x720 canvas (1 the size "
                     "getters, 2 the UI layers' copy, 4 the world-to-screen projectors, 8 scissor rectangles, 16 "
                     "the direct readers, 32 the canvas viewport of widgets with their own camera)");
REXCVAR_DEFINE_INT32(lr_gpu_pool_extra_mb, -1, "LR",
                     "MB added to the game's 247 MB graphics memory pool (-1 = by the screen's pixels). Takes effect on "
                     "restart");

extern "C" void __imp__sub_829C17D8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_829DBF00(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82ABE828(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82A894F0(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82731488(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82ABEB90(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_827303F8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82730408(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82323090(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82454AF0(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82552B80(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82666570(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82730418(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82730DC0(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82E0E4A0(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_827DADC8(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void __imp__sub_82730F08(PPCContext& __restrict ctx, uint8_t* base);
// Guest functions the hooks call: the UI renderer's flush and the engine's viewport.
extern "C" void sub_82322940(PPCContext& __restrict ctx, uint8_t* base);
extern "C" void sub_82A25128(PPCContext& __restrict ctx, uint8_t* base);

namespace {
constexpr uint32_t kConsoleWidth = 1280;
constexpr uint32_t kConsoleHeight = 720;

inline uint32_t Load32(const uint8_t* base, uint32_t addr) {
  uint32_t v;
  std::memcpy(&v, base + addr, 4);
  return __builtin_bswap32(v);
}

inline void Store32(uint8_t* base, uint32_t addr, uint32_t value) {
  value = __builtin_bswap32(value);
  std::memcpy(base + addr, &value, 4);
}

bool PlausibleStack(uint32_t sp) { return sp >= 0x10000u && sp < 0xC0000000u && (sp & 3) == 0; }
bool PlausibleCode(uint32_t pc) { return pc >= 0x82000000u && pc < 0x84000000u; }

// The guest call chain: the return address, then each frame's saved LR (at its caller's stack pointer - 8, as the
// __savegprlr prologues leave it).
std::string CallChain(PPCContext& ctx, const uint8_t* base, int levels = 6) {
  std::string chain = fmt::format("{:08X}", uint32_t(ctx.lr));
  uint32_t sp = ctx.r1.u32;
  for (int level = 0; level < levels && PlausibleStack(sp); ++level) {
    const uint32_t caller_sp = Load32(base, sp);
    if (!PlausibleStack(caller_sp) || caller_sp <= sp) break;
    const uint32_t ret = Load32(base, caller_sp - 8);
    if (!PlausibleCode(ret)) break;
    chain += fmt::format(" < {:08X}", ret);
    sp = caller_sp;
  }
  return chain;
}

// The emulated EDRAM's tile count (the SDK's gpu_edram_tiles; 2048 = the console's 10 MB).
uint32_t EdramTiles() {
  static const uint32_t tiles = [] {
    const uint32_t t = uint32_t(std::strtoul(rex::cvar::GetFlagByName("gpu_edram_tiles").c_str(), nullptr, 10));
    return t == 4096 || t == 8192 ? t : 2048u;
  }();
  return tiles;
}

// EDRAM tiles (80x16 samples of 32 bits) of one 1x screen-sized target.
uint32_t ScreenTiles(uint32_t width, uint32_t height) { return ((width + 79) / 80) * ((height + 15) / 16); }

// The wanted screen size (width a multiple of 16, height of 4), or 0x0 for the console's.
void WantedScreen(uint32_t& width, uint32_t& height) {
  static const std::pair<uint32_t, uint32_t> wanted = [] {
    const int32_t h = REXCVAR_GET(lr_render_height), w = REXCVAR_GET(lr_render_width);
    uint32_t height = h > 0 ? uint32_t(h) & ~3u : kConsoleHeight;
    uint32_t width = w > 0 ? (uint32_t(w) + 15) & ~15u : ((height * 16 / 9) + 15) & ~15u;
    if (width == kConsoleWidth && height == kConsoleHeight) return std::pair<uint32_t, uint32_t>(0, 0);
    // The colour and depth of the screen side by side must fit the emulated EDRAM (1920x1080 needs
    // gpu_edram_tiles 4096).
    if (height < 360 || height > 1440 || width < 640 || width > 2560 || 2 * ScreenTiles(width, height) > EdramTiles()) {
      REXLOG_WARN("lr render size: {}x{} is not supported with {} EDRAM tiles; staying 1280x720", width, height,
                  EdramTiles());
      return std::pair<uint32_t, uint32_t>(0, 0);
    }
    REXLOG_INFO("lr render size: the game's screen is {}x{} ({} EDRAM tiles)", width, height, EdramTiles());
    return std::pair<uint32_t, uint32_t>(width, height);
  }();
  width = wanted.first;
  height = wanted.second;
}

// Whether the screen is wider than 16:9.
bool WideScreen() {
  uint32_t width, height;
  WantedScreen(width, height);
  return width && uint64_t(width) * 9 > uint64_t(height) * 16 + 16 * 9;
}


// The final frame (scene + UI) of a wide screen: the console's 720 lines at the screen's aspect (width a multiple of
// 16), or 0x0 when the screen is not wide (the frame then stays 1280x720). The UI keeps its 1280x720 canvas, centred.
void WideFrame(uint32_t& width, uint32_t& height) {
  static const std::pair<uint32_t, uint32_t> frame = [] {
    if (!WideScreen()) return std::pair<uint32_t, uint32_t>(0, 0);
    uint32_t w, h;
    WantedScreen(w, h);
    const uint32_t frame_width = (uint32_t(uint64_t(kConsoleHeight) * w / h) + 8) & ~15u;
    REXLOG_INFO("lr render size: wide screen, the final frame is {}x{}, the UI's 1280x720 canvas {} pixels in",
                frame_width, kConsoleHeight, (frame_width - kConsoleWidth) / 2);
    // Movies (Bink: the opening, cutscenes) are one full-screen quad with this pixel shader (three planes, Y / Cb /
    // Cr, and a colour matrix): the SDK keeps it 16:9, centred, instead of stretching it across the wide frame (from
    // the DS2 port).
    if (rex::cvar::GetFlagByName("gpu_keep_aspect_pixel_shaders").empty()) {
      rex::cvar::SetFlagByName("gpu_keep_aspect_pixel_shaders", "E77131FAC1E0DAC3");
      rex::cvar::SetFlagByName("gpu_keep_aspect_pillarbox", "true");
    }
    return std::pair<uint32_t, uint32_t>(frame_width, kConsoleHeight);
  }();
  width = frame.first;
  height = frame.second;
}

// The UI canvas's left edge in the wide frame (0 = not a wide screen).
uint32_t CanvasOffset() {
  uint32_t width, height;
  WideFrame(width, height);
  return width ? (width - kConsoleWidth) / 2 : 0;
}

// The aspect of what is shown (width / height): the wide frame's (the 3D scene is scaled into it, and its width is a
// multiple of 16, so it can differ a little from the render size's), else 16:9.
double ScreenAspect() {
  uint32_t width, height;
  WideFrame(width, height);
  return width ? double(width) / double(height) : 16.0 / 9.0;
}

bool CanvasBit(int32_t bit) {
  static const int32_t mask = REXCVAR_GET(lr_ui_canvas_mask);
  return CanvasOffset() && (mask & bit);
}

inline float LoadFloat(const uint8_t* base, uint32_t addr) {
  const uint32_t bits = Load32(base, addr);
  float value;
  std::memcpy(&value, &bits, 4);
  return value;
}

inline void StoreFloat(uint8_t* base, uint32_t addr, float value) {
  uint32_t bits;
  std::memcpy(&bits, &value, 4);
  Store32(base, addr, bits);
}

// A {width, height} pair at addr that is the console's 1280x720 becomes the wanted size; the engine's float next to
// some of them (height / width, 0.5625) follows.
void Resize(uint8_t* base, uint32_t addr, const char* what, uint32_t ratio_addr = 0) {
  uint32_t width, height;
  WantedScreen(width, height);
  if (!width || addr < 0x10000u) return;
  if (Load32(base, addr) != kConsoleWidth || Load32(base, addr + 4) != kConsoleHeight) {
    if (what) REXLOG_INFO("lr render size: {} at {:08X} is {}x{}, left alone", what, addr, Load32(base, addr),
                Load32(base, addr + 4));
    return;
  }
  Store32(base, addr, width);
  Store32(base, addr + 4, height);
  if (ratio_addr && Load32(base, ratio_addr) == 0x3F100000u) {
    const float ratio = float(height) / float(width);
    uint32_t bits;
    std::memcpy(&bits, &ratio, 4);
    Store32(base, ratio_addr, bits);
  }
  if (what) REXLOG_INFO("lr render size: {} at {:08X} 1280x720 -> {}x{}", what, addr, width, height);
}

std::mutex g_mutex;
std::set<std::tuple<uint32_t, uint32_t, uint32_t, uint32_t, std::string>> g_seen;

}  // namespace

// sub_829C17D8(width, height, format, multisample, parameters, surface, ...): the D3D surface setup (see the top).
// With more than 4096 EDRAM tiles a target based at tile 4096 or above keeps bit 12 of its base (the register's spare
// bit, read by the SDK's base_tiles()), which the library masks off.
REX_HOOK_RAW(sub_829C17D8) {
  const uint32_t parameters = ctx.r7.u32, surface = ctx.r8.u32;
  const uint32_t wanted_base = (EdramTiles() > 4096 && parameters >= 0x10000u) ? Load32(base, parameters) : 0;
  static const bool probe = REXCVAR_GET(lr_surface_probe);
  if (probe) {
    const uint32_t width = ctx.r3.u32, height = ctx.r4.u32, format = ctx.r5.u32, msaa = ctx.r6.u32;
    const std::string chain = CallChain(ctx, base);
    bool fresh;
    {
      std::lock_guard<std::mutex> lock(g_mutex);
      fresh = g_seen.size() < 400 && g_seen.emplace(width, height, format, msaa, chain).second;
    }
    if (fresh) {
      REXLOG_INFO("lr surface probe: {}x{} format {:#x} msaa {} base {} from {}", width, height, format, msaa,
                  parameters >= 0x10000u ? Load32(base, parameters) : 0u, chain);
    }
  }
  __imp__sub_829C17D8(ctx, base);
  if ((wanted_base & 0x1000u) && wanted_base < 8192u && surface >= 0x10000u) {
    Store32(base, surface + 28, Load32(base, surface + 28) | 0x1000u);
  }
}

// sub_829DBF00(device, ..., render target index r5, ...): the D3D Clear. It clears a colour target through a depth
// alias, writing RB_DEPTH_INFO (register 0x2002, a type-0 packet) as the target's info word & 0xFFF, so a target based
// at tile 4096 or above would be cleared 4096 tiles too low (DS2's "lavender" scene at 1080p). The register write gets
// its bit 12 back after the call: the commands written end at the device's write pointer (+48).
REX_HOOK_RAW(sub_829DBF00) {
  const uint32_t device = ctx.r3.u32;
  const int32_t target = int32_t(ctx.r5.u32);
  uint32_t info = 0, start = 0;
  if (EdramTiles() > 4096 && device >= 0x10000u && target >= 0 && target < 4) {
    const uint32_t surface = Load32(base, device + (uint32_t(target) + 3204) * 4);
    if (surface >= 0x10000u) info = Load32(base, surface + 28);
    start = Load32(base, device + 48);
  }
  __imp__sub_829DBF00(ctx, base);
  if (!(info & 0x1000u)) return;
  const uint32_t end = Load32(base, device + 48);
  // Search back from the end (at most 1 KB) for a type-0 packet that writes RB_DEPTH_INFO (alone, or within a run of
  // registers: base register in bits 0-14, count - 1 in bits 16-29, bit 15 = all values to one register) with this
  // target's low 12 bits.
  static std::atomic<uint32_t> patched{0}, missed{0};
  const uint32_t span = (end > start && end - start <= 1024u) ? end - start : 1024u;
  bool done = false;
  for (uint32_t at = end - 4; !done && at + 4 > end - span && at >= 0x10000u; at -= 4) {
    const uint32_t header = Load32(base, at);
    if (header >> 30) continue;
    const uint32_t first = header & 0x7FFFu, count = ((header >> 16) & 0x3FFFu) + 1;
    const bool one_register = header & 0x8000u;
    if (count > 32 || first > 0x2002u || 0x2002u >= first + (one_register ? 1u : count)) continue;
    const uint32_t slot = at + 4 + (one_register ? 0u : (0x2002u - first) * 4);
    if (slot >= end + 4) continue;
    const uint32_t value = Load32(base, slot);
    if ((value & 0x1FFFu) != (info & 0xFFFu)) continue;
    Store32(base, slot, value | 0x1000u);
    done = true;
  }
  const uint32_t n = done ? patched.fetch_add(1) + 1 : 0;
  if (done && (n == 1 || !(n % 5000))) {
    REXLOG_INFO("lr render size: clear of the target at EDRAM {} given its bit 12 back ({} so far, {} missed)",
                info & 0x1FFFu, n, missed.load());
  }
  if (!done) {
    const uint32_t m = missed.fetch_add(1) + 1;
    if (m <= 6 || !(m % 1000)) {
      REXLOG_WARN("lr render size: clear of the target at EDRAM {} (info {:08X}) NOT patched ({} missed, {} patched)",
                  info & 0x1FFFu, info, m, patched.load());
    }
  }
}

// The game's two sources of its screen size (found from lr_surface_probe's call chains, 2026-11-24): every screen-sized
// target inherits its size from one of them, and the half-size ones are computed from them ((n + 1) / 2).
// sub_82A894F0(device): the render device's constructor (7072 bytes, allocated by sub_82A844E8, global at 0x8313F72C)
// stores 1280 at +3060, 720 at +3064 and 9/16 at +3068; sub_82A84908 builds the main per-pass targets from them, and
// the getters sub_82A81B80 / sub_82A81B90 hand them out. The constructor reads none of them back after storing them, so
// they are rewritten after the call.
REX_HOOK_RAW(sub_82A894F0) {
  const uint32_t device = ctx.r3.u32;
  __imp__sub_82A894F0(ctx, base);
  if (REXCVAR_GET(lr_resize_mask) & 1) Resize(base, device + 3060, "render device screen", device + 3068);
}

// sub_82ABE828(config): the static config object at 0x82F61F84 (pointer at 0x83147A58): 1280x720 at +24 / +28 and again
// at +36 / +40, each followed by 9/16. sub_82ABD790 copies them into the singleton at 0x83147A54, whose +32 / +36 size
// sub_82AF3C80's full- and half-size targets.
REX_HOOK_RAW(sub_82ABE828) {
  const uint32_t config = ctx.r3.u32;
  __imp__sub_82ABE828(ctx, base);
  if (REXCVAR_GET(lr_resize_mask) & 2) Resize(base, config + 24, "config screen", config + 32);
  uint32_t frame_width, frame_height;
  WideFrame(frame_width, frame_height);
  if (frame_width && Load32(base, config + 36) == kConsoleWidth && Load32(base, config + 40) == kConsoleHeight) {
    // A wide screen: the final frame takes the screen's aspect (the UI's readers at the end keep 1280x720).
    Store32(base, config + 36, frame_width);
    Store32(base, config + 40, frame_height);
    if (Load32(base, config + 44) == 0x3F100000u) {
      StoreFloat(base, config + 44, float(frame_height) / float(frame_width));
    }
    REXLOG_INFO("lr render size: config screen (second) at {:08X} 1280x720 -> {}x{}", config + 36, frame_width,
                frame_height);
  } else if (REXCVAR_GET(lr_resize_mask) & 4) {
    Resize(base, config + 36, "config screen (second)", config + 44);
  }
}

// sub_82ABEB90(): creates the engine's memory pools from the size table at 0x83140000 + 10408.. (12-byte entries
// {heap, size, base}; filled by sub_822F9FA0 from the config sub_822FB468 sets up). The write-combined physical pool
// (flags 0x20000404) sums the entries at +10448 (config +140: 0x0F700000 = 247 MB, the graphics pool: render targets
// and textures), +10460, +10476 and +10496. A larger screen's targets left the field's textures too little room: at
// 1920x1080 the game stayed on NOW LOADING (S10, 2026-11-24). The entry grows by 72 MB per extra console screen of
// pixels, in 16 MB steps, up to the 96 MB of 1920x1080 (lr_gpu_pool_extra_mb overrides).
REX_HOOK_RAW(sub_82ABEB90) {
  constexpr uint32_t kGraphicsPoolSize = 0x83140000u + 10448u;
  uint32_t width, height;
  WantedScreen(width, height);
  const uint64_t pixels = uint64_t(width) * height, console = uint64_t(kConsoleWidth) * kConsoleHeight;
  const int32_t forced = REXCVAR_GET(lr_gpu_pool_extra_mb);
  uint32_t extra_mb = 0;
  if (forced >= 0) {
    extra_mb = uint32_t(forced);
  } else if (width && pixels > console) {
    extra_mb = uint32_t((((pixels - console) * 72 + console - 1) / console + 15) & ~uint64_t(15));
    // 343 MB (1920x1080) is the largest pool tried that the guest's physical heap gives: 375 MB for 2288x1080 was
    // refused ("BaseHeap::Alloc failed to find contiguous range") and the game never started, while 2288x1080 runs in
    // 343 MB (S10, 2026-11-24).
    extra_mb = std::min(extra_mb, 96u);
  }
  const uint32_t size = Load32(base, kGraphicsPoolSize);
  if (extra_mb && size == 0x0F700000u) {
    Store32(base, kGraphicsPoolSize, size + (extra_mb << 20));
    REXLOG_INFO("lr render size: graphics pool 247 MB -> {} MB for {}x{}", (size >> 20) + extra_mb, width, height);
  } else if (extra_mb) {
    REXLOG_WARN("lr render size: graphics pool entry is {:#x}, not 247 MB; left alone", size);
  }
  __imp__sub_82ABEB90(ctx, base);
  if (extra_mb) REXLOG_INFO("lr render size: graphics pool at {:08X}", Load32(base, kGraphicsPoolSize + 4));
}

// sub_82731488(out, f1 field of view, f2 aspect, f3 / f4 the clip planes): the perspective matrix (its only caller is
// sub_827315B0). The cameras keep the 16:9 their constructors set (sub_82E0D3E0 at +324, sub_82E0E350 at +176, from the
// image's only 16:9 float at 0x82096A5C); on a wider screen a 16:9 projection becomes the screen's, so the view widens
// (Hor+) instead of stretching. Other aspects (shadow maps, 1:1) are left alone.
namespace {
// Set while a UI widget with its own camera is drawn in a viewport of the canvas's size (CanvasView below): its
// projection stays 16:9.
thread_local bool t_canvas_view = false;
}  // namespace

REX_HOOK_RAW(sub_82731488) {
  static const bool wide = WideScreen();
  if (wide && !t_canvas_view && std::fabs(ctx.f2.f64 - 16.0 / 9.0) < 1e-3) ctx.f2.f64 = ScreenAspect();
  __imp__sub_82731488(ctx, base);
}

// Wide screens: the UI keeps its 1280x720 canvas, centred in the wide final frame (Galaxy S10, 2026-11-24).
//
// The UI is drawn in 3D. Its camera (sub_82730FB0, each frame) is a perspective projection (sub_82730D38 -> the
// builder above, 35 degrees, aspect 1 / the frame's height-to-width ratio) and a view (sub_82730DC0) with the canvas's
// centre (half the UI's size) on its axis at the distance where one canvas unit is one pixel of a 720-line frame. With
// the screen's aspect in the projection a 1280-wide canvas therefore lands in the middle of the wide frame, pixel for
// pixel (draw-state trace: vertex constants c192 = 2 d / frame width, c195.x = -d 1280 / frame width). So the frame is
// made wide (WideFrame, the config's second pair) and everything that is the UI's goes on reading 1280x720:
//  - the size getters sub_827303F8 / sub_82730408 (singleton 0x83147A54 +32 / +36, ~65 layout call sites) and the
//    ratio's sub_82730418 (+40);
//  - each UI layer's copy of the size (+256 / +260): sub_82323090(layer, singleton), and the layer classes that carry
//    their own inlined copy of it (patched in the generated source, tools/patch_generated.py);
//  - UI code that reads the singleton itself (the same patch): markers' screen bounds and clamps, 3D item views;
//  - the view's distance (sub_82730DC0), which used the frame's ratio.
// What the 3D camera projects for the UI (name tags, markers) is stretched about the canvas's centre, the UI's
// scissor rectangles move with the canvas, the effects camera gets the same Hor+ as the others, and widgets that work
// in clip space (the minimap) are drawn in a viewport that is the canvas. Movies keep 16:9 through the SDK's
// gpu_keep_aspect_pixel_shaders (set in WideFrame). The frame's own readers (its targets sub_82AF3C80 /
// sub_82AF9DF0, the scene's composite sub_82AC1AF8, the resolve) read the singleton and stay wide.
// lr_ui_canvas_mask switches the pieces off one by one (diagnostic).

REX_HOOK_RAW(sub_827303F8) {
  __imp__sub_827303F8(ctx, base);
  if (CanvasBit(1)) ctx.r3.u64 = kConsoleWidth;
}

REX_HOOK_RAW(sub_82730408) {
  __imp__sub_82730408(ctx, base);
  if (CanvasBit(1)) ctx.r3.u64 = kConsoleHeight;
}

// sub_82730418(): the frame's height / width (singleton +40) for the UI: the minimap (sub_827DADC8) builds its own
// camera from it.
REX_HOOK_RAW(sub_82730418) {
  __imp__sub_82730418(ctx, base);
  if (CanvasBit(1)) ctx.f1.f64 = double(kConsoleHeight) / double(kConsoleWidth);
}

REX_HOOK_RAW(sub_82323090) {
  const uint32_t layer = ctx.r3.u32;
  __imp__sub_82323090(ctx, base);
  if (!CanvasBit(2) || layer < 0x10000u) return;
  uint32_t width, height;
  WideFrame(width, height);
  if (Load32(base, layer + 256) == width && Load32(base, layer + 260) == height) {
    Store32(base, layer + 256, kConsoleWidth);
    Store32(base, layer + 260, kConsoleHeight);
  }
}

// The UI code that reads the frame's size from the singleton itself (tools/patch_generated.py, CANVAS_READERS):
// width / height (+32 / +36) and the ratio (+40) of the canvas while the frame is wide.
extern "C" uint32_t lr_ui_canvas_size(uint32_t value, uint32_t offset) {
  if (!CanvasBit(16)) return value;
  uint32_t width, height;
  WideFrame(width, height);
  if (offset == 32) return value == width ? kConsoleWidth : value;
  return value == height ? kConsoleHeight : value;
}

extern "C" double lr_ui_canvas_ratio(double value) {
  return CanvasBit(16) ? double(kConsoleHeight) / double(kConsoleWidth) : value;
}

// sub_82730DC0(out, f1 height / width, f2 field of view): the UI camera's view matrix (sub_82730FB0 sets it and the
// projection sub_82730D38 each frame; sub_82730F08 hands their product to widgets that change the camera and put it
// back). The canvas's centre (half the getters' size) goes on the axis at the distance half width * f1 / tan(fov / 2),
// with f1 the frame's ratio from the singleton +40: 360 / tan on the console, where a canvas unit is then one pixel.
// On a wide frame the ratio is smaller and the canvas came 1.19 times too close (zoomed in, top and bottom cut); the
// distance needs the canvas's own 720 / 1280. The projection keeps the frame's ratio.
REX_HOOK_RAW(sub_82730DC0) {
  if (CanvasBit(1)) ctx.f1.f64 = double(kConsoleHeight) / double(kConsoleWidth);
  __imp__sub_82730DC0(ctx, base);
}

// sub_82454AF0(camera, out, position) / sub_82552B80(?, out, position, matrix): world position to screen pixels for
// the UI (name tags, markers), x = (x / w + 1) * width / 2. The 3D camera sees the whole wide frame and the canvas is
// its middle: with the canvas's width in that formula (their reads of the singleton are patched) the result is
// stretched about the canvas's centre by frame width / 1280; with the frame's width it is a frame pixel, which is
// CanvasOffset() right of the canvas's.
namespace {
void ProjectedToCanvas(PPCContext& ctx, uint8_t* base, uint32_t out) {
  if (!CanvasBit(4) || ctx.r3.u32 != 1 || out < 0x10000u) return;
  const float x = LoadFloat(base, out);
  if (CanvasBit(16)) {
    uint32_t width, height;
    WideFrame(width, height);
    const float centre = float(kConsoleWidth) * 0.5f;
    StoreFloat(base, out, centre + (x - centre) * float(width) / float(kConsoleWidth));
  } else {
    StoreFloat(base, out, x - float(CanvasOffset()));
  }
}
}  // namespace

REX_HOOK_RAW(sub_82454AF0) {
  const uint32_t out = ctx.r4.u32;
  __imp__sub_82454AF0(ctx, base);
  ProjectedToCanvas(ctx, base, out);
}

REX_HOOK_RAW(sub_82552B80) {
  const uint32_t out = ctx.r4.u32;
  __imp__sub_82552B80(ctx, base);
  ProjectedToCanvas(ctx, base, out);
}

// sub_82666570(x, y, width, height): the engine's scissor rectangle (D3D SetScissorRect sub_829BD368). The UI's
// callers (0x8270.. - 0x8286..) give canvas pixels; the frame's are CanvasOffset() further right, and an edge on the
// canvas's border (a full-screen rectangle) goes to the frame's border.
REX_HOOK_RAW(sub_82666570) {
  const uint32_t from = uint32_t(ctx.lr);
  if (from >= 0x82700000u && from < 0x82860000u && CanvasBit(8)) {
    uint32_t frame_width, frame_height;
    WideFrame(frame_width, frame_height);
    const int64_t left = int32_t(ctx.r3.u32), right = left + int64_t(ctx.r5.u32);
    const int64_t new_left = left <= 0 ? 0 : left + CanvasOffset();
    const int64_t new_right = right >= int64_t(kConsoleWidth) ? int64_t(frame_width) : right + CanvasOffset();
    ctx.r3.s64 = new_left;
    ctx.r5.u64 = uint64_t(new_right > new_left ? new_right - new_left : 0);
  }
  __imp__sub_82666570(ctx, base);
}

// sub_82E0E4A0(camera): the projection of the second camera class (constructor sub_82E0E350, 16:9 at +176): cot(fov /
// 2) for x and that times the aspect for y, rebuilt into +80 when its flags (+210 & 369) say so, without the builder
// above. The title's logo and other screen-space effects use it: on a wide frame they were stretched sideways. Its x
// terms shrink by 16:9 over the screen's aspect, the same Hor+ as the other cameras (the canvas's 1280 then land on
// the frame's middle).
REX_HOOK_RAW(sub_82E0E4A0) {
  static const bool wide = WideScreen();
  const uint32_t camera = ctx.r3.u32;
  const bool rebuilt = wide && camera >= 0x10000u && ((Load32(base, camera + 208) & 0xFFFFu) & 369u);
  __imp__sub_82E0E4A0(ctx, base);
  if (!rebuilt || std::fabs(LoadFloat(base, camera + 176) - 16.0f / 9.0f) > 1e-3f) return;
  const float shrink = float((16.0 / 9.0) / ScreenAspect());
  StoreFloat(base, camera + 80, LoadFloat(base, camera + 80) * shrink);
  StoreFloat(base, camera + 112, LoadFloat(base, camera + 112) * shrink);
}

// Widgets with a camera of their own that work in clip space are drawn as on the console: in a viewport that is the
// canvas (its 1280 pixels in the middle of the frame) with the 16:9 projection. The minimap (sub_827DADC8) is one: a
// tilted view placed by a fraction of the screen, x / (width / 2) - 1, which it adds to its projection, and a round
// mask whose pixel shader tests the clip-space position against a centre and radius worked out for a 1280-wide frame
// (constants c254 / c255; widened, the map sat 79 pixels right of its place with its mask an ellipse elsewhere). The
// UI renderer draws at once: its pending sprites are flushed (sub_82322940) before the viewport changes, and the
// widget flushes its own before it puts the UI's camera back with sub_82730F08, where the frame's viewport returns.
namespace {
constexpr uint32_t kUiRendererOwner = 0x831203ACu;  // the UI renderer is this object's +240
constexpr uint32_t kRenderState = 0x8313FB40u;      // the engine's state cache: the viewport at +184 (x, y, w, h)
thread_local uint32_t t_canvas_view_saved[4];

// The engine's viewport (sub_82A25128: x, y, width, height, the cache's +200 / +204 passed through, depth 0 .. 1;
// D3D SetViewport sub_829BDB98).
void SetViewport(PPCContext& ctx, uint8_t* base, uint32_t x, uint32_t y, uint32_t width, uint32_t height) {
  ctx.r3.u64 = x;
  ctx.r4.u64 = y;
  ctx.r5.u64 = width;
  ctx.r6.u64 = height;
  ctx.r9.u64 = Load32(base, kRenderState + 200);
  ctx.r10.u64 = Load32(base, kRenderState + 204);
  ctx.f1.f64 = 0.0;
  ctx.f2.f64 = 1.0;
  sub_82A25128(ctx, base);
}

void CanvasViewBegin(PPCContext& ctx, uint8_t* base) {
  if (!CanvasBit(32) || t_canvas_view) return;
  const uint32_t owner = Load32(base, kUiRendererOwner);
  if (owner < 0x10000u) return;
  uint32_t frame_width, frame_height;
  WideFrame(frame_width, frame_height);
  // Only on the frame itself (not while something draws into another target).
  if (Load32(base, kRenderState + 192) != frame_width || Load32(base, kRenderState + 196) != frame_height) return;
  const PPCContext saved = ctx;
  ctx.r3.u64 = owner + 240;
  sub_82322940(ctx, base);
  for (int i = 0; i < 4; ++i) t_canvas_view_saved[i] = Load32(base, kRenderState + 184 + 4 * i);
  SetViewport(ctx, base, CanvasOffset(), 0, kConsoleWidth, kConsoleHeight);
  ctx = saved;
  t_canvas_view = true;
}

void CanvasViewEnd(PPCContext& ctx, uint8_t* base) {
  if (!t_canvas_view) return;
  t_canvas_view = false;
  const PPCContext saved = ctx;
  SetViewport(ctx, base, t_canvas_view_saved[0], t_canvas_view_saved[1], t_canvas_view_saved[2],
              t_canvas_view_saved[3]);
  ctx = saved;
}
}  // namespace

REX_HOOK_RAW(sub_827DADC8) {
  CanvasViewBegin(ctx, base);
  __imp__sub_827DADC8(ctx, base);
  CanvasViewEnd(ctx, base);  // the paths that leave without drawing
}

REX_HOOK_RAW(sub_82730F08) {
  CanvasViewEnd(ctx, base);
  __imp__sub_82730F08(ctx, base);
}
