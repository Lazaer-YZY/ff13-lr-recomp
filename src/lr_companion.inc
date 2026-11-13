// Second-screen companion (dual-screen devices such as the AYN Thor's 1240x1080 bottom screen).
// Included into lr_hooks.cpp. The Java side (CompanionPresentation) polls LrCompanionState about
// ten times a second and draws the bottom screen from it: the schemata in battle, the area map in
// the field, the game's fan art in cutscenes and on the title. Touching a schema presses LB/RB
// through LrCompanionPress (merged into controller 1 in lr_touch_merge).
//
// Game state known so far (see DECISIONS.md "Second screen"):
//  - mode: the frame-rate mode at app+788 (sub_822F0FC0): 0 = title / system screens,
//    1 = field, battle and menus, 2+ = event scenes (cutscenes). -1 = not known yet.
//
// Finding the rest (battle flag, schemata, ATB, HP, area, position) is done with guest-memory
// snapshots: with debug logging on, `adb shell setprop debug.lr.dump LABEL` writes
// every committed guest region to <app dir>/dumps/LABEL.gmem (records of u32 BE address, u32 BE
// size, bytes); tools/companion_dump.sh pulls them and tools/gmem.py compares them.
#if defined(__ANDROID__)
#include <sys/uio.h>
#include <sys/system_properties.h>
#include <cmath>
namespace {

std::string lr_companion_app_dir() {
  const char* dir = std::getenv("REX_ANDROID_APP_DIR");
  return dir ? std::string(dir) : std::string();
}

// Writes every committed, readable guest region. The game keeps running meanwhile, so values that
// change every frame can be torn; the snapshots are for finding where things live.
void lr_companion_dump(const std::string& label) {
  uint8_t* base = g_lr_guest_base.load(std::memory_order_relaxed);
  auto* kernel_state = REX_KERNEL_STATE();
  auto* memory = kernel_state ? kernel_state->memory() : nullptr;
  if (!base || !memory) {
    REXLOG_WARN("LR companion dump: guest memory not ready");
    return;
  }
  std::string dir = lr_companion_app_dir() + "dumps";
  std::filesystem::create_directories(dir);
  std::string path = dir + "/" + label + ".gmem";
  FILE* f = std::fopen(path.c_str(), "wb");
  if (!f) {
    REXLOG_WARN("LR companion dump: cannot write {}", path);
    return;
  }
  const uint64_t t0 = lr_now_us();
  uint64_t total = 0;
  uint32_t regions = 0;
  uint64_t addr = 0x10000;
  while (addr < 0xFFFF0000ull) {
    auto* heap = memory->LookupHeap(uint32_t(addr));
    rex::memory::HeapAllocationInfo info{};
    if (!heap || !heap->QueryRegionInfo(uint32_t(addr), &info) || !info.region_size) {
      addr = (addr + 0x10000) & ~0xFFFFull;
      continue;
    }
    const uint64_t start = std::max<uint64_t>(info.base_address, addr);
    const uint64_t end = uint64_t(info.base_address) + info.region_size;
    if ((info.state & rex::memory::kMemoryAllocationCommit) &&
        (info.protect & rex::memory::kMemoryProtectRead) && end > start) {
      uint32_t hdr[2] = {__builtin_bswap32(uint32_t(start)), __builtin_bswap32(uint32_t(end - start))};
      std::fwrite(hdr, 4, 2, f);
      std::fwrite(base + start, 1, size_t(end - start), f);
      total += end - start;
      ++regions;
    }
    addr = end > addr ? end : addr + 0x1000;
  }
  std::fclose(f);
  REXLOG_INFO("LR companion dump '{}': {} regions, {} MB in {} ms", label, regions, total >> 20,
              (lr_now_us() - t0) / 1000);
}

// Debug builds of the companion: a file in the app folder asks for a snapshot.
void lr_companion_dump_watch() {
  static std::once_flag once;
  std::call_once(once, [] {
    if (!lr_diag_enabled()) return;
    std::thread([] {
      // The request is the system property debug.lr.dump (adb shell setprop debug.lr.dump LABEL):
      // a file written by adb into the app's storage folder stopped being readable by the app
      // (2026-11-12). Each new value is one snapshot.
      std::string last;
      {
        char init[PROP_VALUE_MAX] = {};
        __system_property_get("debug.lr.dump", init);
        last = init;
      }
      for (;;) {
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
        char label[PROP_VALUE_MAX] = {};
        const int n = __system_property_get("debug.lr.dump", label);
        if (n <= 0 || last == label) continue;
        last = label;
        std::string name;
        for (int i = 0; i < n; ++i) {
          char c = label[i];
          if (std::isalnum(uint8_t(c)) || c == '_' || c == '-') name += c;
        }
        if (name.empty()) name = "snap";
        lr_companion_dump(name);
      }
    }).detach();
  });
}

// ---------------------------------------------------------------------------------------
// Area maps ("navimaps", docs/dual_screen_map_re.md). The map art is static: scene00100's
// navimap.geb lists 29 navimaps (world origin X/Z, rows of 512x512 tiles at x = value*128 px),
// the tiles are scene/lay/scene00100/bin/scene00100_<nn>_split_<ss>.x360.imgb (DXT1, tiled),
// 2 px per world unit (GuiNaviMap's scale constant at 0x820F7BB8). The game loads a navimap with
// a loader command (sub_82389CE8 case 25 -> sub_8237C5B0(loader, type, id, block size, texture
// size, f1 = origin x, f2 = origin z, ...), log "[Loader] load Navimap id = ...") and unloads it
// with sub_8236CBA8(loader, ?, id). Recording both tells the bottom screen which maps are
// loaded; the origin identifies the navimap.geb record (ids are packed resource ids).
// ---------------------------------------------------------------------------------------
struct LrNavimapLoad {
  uint32_t id = 0;
  float x = 0, z = 0;
  uint32_t block = 0, texture = 0;
  uint64_t order = 0;  // load order, 0 = slot free
};
// Written by game threads (serialised by g_lr_navi_mutex, which only game threads take); read by
// the companion thread without any lock through the sequence counter (odd = being written), so a
// game thread never waits on the background-priority companion thread.
std::mutex g_lr_navi_mutex;
std::atomic<uint32_t> g_lr_navi_seq{0};
std::array<LrNavimapLoad, 32> g_lr_navi{};
uint64_t g_lr_navi_order = 0;

struct LrNaviWrite {
  LrNaviWrite() { g_lr_navi_seq.fetch_add(1, std::memory_order_acq_rel); }
  ~LrNaviWrite() { g_lr_navi_seq.fetch_add(1, std::memory_order_acq_rel); }
};

// A consistent copy for the companion thread; false when writers kept it busy (skip this poll).
bool lr_navi_snapshot(std::array<LrNavimapLoad, 32>& out) {
  for (int attempt = 0; attempt < 4; ++attempt) {
    const uint32_t s1 = g_lr_navi_seq.load(std::memory_order_acquire);
    if (s1 & 1) continue;
    std::memcpy(out.data(), g_lr_navi.data(), sizeof(g_lr_navi));
    std::atomic_thread_fence(std::memory_order_acquire);
    if (g_lr_navi_seq.load(std::memory_order_relaxed) == s1) return true;
  }
  return false;
}

void lr_navi_loaded(uint32_t id, float x, float z, uint32_t block, uint32_t texture) {
  std::lock_guard<std::mutex> lock(g_lr_navi_mutex);
  LrNaviWrite writing;
  LrNavimapLoad* slot = nullptr;
  for (auto& n : g_lr_navi)
    if (n.order && n.id == id) slot = &n;
  if (!slot)
    for (auto& n : g_lr_navi)
      if (!n.order) { slot = &n; break; }
  if (!slot) {  // full: reuse the oldest
    slot = &g_lr_navi[0];
    for (auto& n : g_lr_navi)
      if (n.order < slot->order) slot = &n;
  }
  *slot = {id, x, z, block, texture, ++g_lr_navi_order};
}

void lr_navi_unloaded(uint32_t id) {
  std::lock_guard<std::mutex> lock(g_lr_navi_mutex);
  LrNaviWrite writing;
  for (auto& n : g_lr_navi)
    if (n.order && n.id == id) n.order = 0;
}

}  // namespace

REX_EXTERN(__imp__sub_8237C5B0);
REX_HOOK_RAW(sub_8237C5B0) {
  const uint32_t type = ctx.r4.u32, id = ctx.r5.u32, block = ctx.r6.u32, texture = ctx.r7.u32;
  const float x = float(ctx.f1.f64), z = float(ctx.f2.f64);
  __imp__sub_8237C5B0(ctx, base);
  lr_navi_loaded(id, x, z, block, texture);
  static std::atomic<int> logged{0};
  if (logged.fetch_add(1, std::memory_order_relaxed) < 64)
    REXLOG_INFO("LR companion: navimap {:08X} loaded (type {}, block {}, texture {}, origin {:.3f}, {:.3f})",
                id, type, block, texture, x, z);
}

REX_EXTERN(__imp__sub_8236CBA8);
REX_HOOK_RAW(sub_8236CBA8) {
  const uint32_t id = ctx.r5.u32;
  __imp__sub_8236CBA8(ctx, base);
  lr_navi_unloaded(id);
}

namespace {
// True when [addr, addr+len) lies in one committed, readable guest region (read from a Java
// thread while the game runs: a stale pointer must not fault the host).
// No guest-memory locks (2026-11-12): the first version asked QueryRegionInfo, which takes the
// guest memory's global lock that every game allocation also takes; the companion thread runs at
// background priority, so holding it while descheduled stalled the game's threads (10-14 ms lock
// holds in the owner's 60 fps glitch sessions, which only happened with this branch). Reads now go
// through process_vm_readv on our own process: the kernel copies what is mapped and fails with
// EFAULT on anything that is not, so a stale pointer can never fault this thread and no runtime
// lock is involved.
bool lr_companion_copy(const void* src, void* dst, size_t len) {
  struct iovec local {dst, len};
  struct iovec remote {const_cast<void*>(src), len};
  return process_vm_readv(getpid(), &local, 1, &remote, 1, 0) == ssize_t(len);
}

// True when [addr, addr+len) is mapped and readable right now (probes each page; lock-free).
bool lr_companion_readable(uint32_t addr, uint32_t len) {
  if (addr < 0x10000 || addr + len < addr || !len) return false;
  uint8_t* base = g_lr_guest_base.load(std::memory_order_relaxed);
  if (!base) return false;
  uint8_t probe;
  for (uint64_t p = addr & ~0xFFFull; p < uint64_t(addr) + len; p += 0x1000) {
    const uint64_t at = std::max<uint64_t>(p, addr);
    if (!lr_companion_copy(base + at, &probe, 1)) return false;
  }
  return lr_companion_copy(base + addr + len - 1, &probe, 1);
}

bool lr_companion_u32(uint8_t* base, uint32_t addr, uint32_t* v) {
  if (addr < 0x10000 || addr > 0xFFFFFFFCu) return false;
  uint32_t raw;
  if (!lr_companion_copy(base + addr, &raw, 4)) return false;
  *v = __builtin_bswap32(raw);
  return true;
}

// Lightning in the field (docs/dual_screen_map_re.md "Player position"): the game's "-2" actor
// handle (the field leader, resolved by sub_825515C8) is [[0x830DE958] + 68]; actor handles index
// the table [[0x830E75D8] + 904] by their top 16 bits, the entry is valid when its +64 equals the
// handle; the actor's world matrix is at +128 (its parent is the identity root; rows: right, up,
// forward, position). The minimap reads the same actor (GuiNaviMap+192 holds its position).
bool lr_companion_actor(uint8_t* base, uint32_t global, uint32_t field, float* x, float* z, float* fx, float* fz) {
  uint32_t g = 0, h = 0, tbl = 0, arr = 0, obj = 0, check = 0;
  if (!lr_companion_u32(base, global, &g) || !lr_companion_u32(base, g + field, &h)) return false;
  const int idx = int16_t(h >> 16);
  if (h == 0xFFFFFFFFu || idx < 0 || idx >= 256) return false;
  if (!lr_companion_u32(base, 0x830E75D8, &tbl) || !lr_companion_u32(base, tbl + 904, &arr)) return false;
  if (!lr_companion_u32(base, arr + uint32_t(idx) * 4, &obj) || !lr_companion_u32(base, obj + 64, &check)) return false;
  if (check != h || !lr_companion_readable(obj + 128, 64)) return false;
  auto f = [&](uint32_t o) {
    uint32_t v = lr_load_be32(base, obj + 128 + o);
    float r;
    std::memcpy(&r, &v, 4);
    return r;
  };
  *fx = f(32);
  *fz = f(40);
  *x = f(48);
  *z = f(56);
  return std::isfinite(*x) && std::isfinite(*z) && std::fabs(*x) < 1e5f && std::fabs(*z) < 1e5f;
}

bool lr_companion_leader(uint8_t* base, float* x, float* z, float* fx, float* fz) {
  return lr_companion_actor(base, 0x830DE958, 68, x, z, fx, fz);
}

// The field camera, the actor whose handle is [[0x830E5120] + 64] (the game's minimap turns with
// it: GuiNaviMap+288 = its yaw). Its forward row points where it looks (checked in the plaza
// snapshots: from the camera towards Lightning, 4.7 units behind her).
bool lr_companion_camera(uint8_t* base, float* fx, float* fz) {
  float x, z;
  return lr_companion_actor(base, 0x830E5120, 64, &x, &z, fx, fz) && std::isfinite(*fx) && std::isfinite(*fz) &&
         (std::fabs(*fx) + std::fabs(*fz)) > 1e-3f;
}

// ---- Battle (docs/dual_screen_battle_re.md) ----------------------------------------------------
// The battle HUD's own objects carry exactly what it shows. Their constructors / destructors are
// hooked below so the live instances are known without a static pointer chain:
//  - GuiBattleTargetInfoWindow (vtable 0x8210BCB0, ctor sub_82755768, dtor sub_827546C8):
//    +0x10 -> chain window (vt 0x82106438; +0xA8 -> enemy name), +0x14 -> HP/ATB window
//    (vt 0x82107EC8; +0x90 -> member, vt 0x82107E40: +0x08 max HP, +0x0C/+0x10 current HP (SetHP
//    sub_82746840 writes both and takes the gauge maximum from +0x08), +0x8C/+0x90/+0x94 -> ATB
//    gauge of schema 0/1/2, +0x98../+0xA4.. its animated copies (SetATB sub_82747DF0)), +0xB8 ->
//    enemy HP gauge.
//  - active schema: u8 at [0x830D8398] + 0x745 (sub_823E6B78, which SetATB compares against).
//  - gauges (vt 0x821393E0): +0xE0 current (float), +0xE4 shown (float), +0xE8 max (int); ATB is
//    stored x100 (10000 = 100.0).
//  - command window (vt 0x82130CA0, ctor sub_827F96E8, dtor sub_827F8AE0): the active schema's
//    ability names, char* at +0x214 (Y), +0x274 (X), +0x154 (B), +0x1B4 (A).
//  - schema records: [0x8315817C] + 0xA0 + i*0x130 (i = 0..2): +0x00 garb id (-1 = empty),
//    +0x40 schema name (empty = the garb's), +0x70 garb name.
//  - battle flag: the target info window is alive and the battle global [0x830D8398] holds the
//    battle scene path at +0x100 ("scene/bt...", empty in the field).
std::atomic<uint32_t> g_lr_btl_target{0};
char g_lr_stagger_dbg[96] = {};  // battle-only debug for the 5-s state line (stagger candidates)
std::atomic<uint32_t> g_lr_btl_command{0};

bool lr_companion_vt(uint8_t* base, uint32_t obj, uint32_t vt) {
  uint32_t v = 0;
  return obj && lr_companion_u32(base, obj, &v) && v == vt;
}

bool lr_companion_ptr(uint8_t* base, uint32_t obj, uint32_t off, uint32_t vt, uint32_t* out) {
  uint32_t p = 0;
  if (!lr_companion_u32(base, obj + off, &p) || !lr_companion_vt(base, p, vt)) return false;
  *out = p;
  return true;
}

float lr_companion_f32(uint8_t* base, uint32_t addr) {
  uint32_t v = lr_load_be32(base, addr);
  float r;
  std::memcpy(&r, &v, 4);
  return r;
}

// A gauge's current value and maximum.
bool lr_companion_gauge(uint8_t* base, uint32_t g, float* cur, int* max) {
  if (!lr_companion_vt(base, g, 0x821393E0u) || !lr_companion_readable(g + 0xE0, 12)) return false;
  *cur = lr_companion_f32(base, g + 0xE0);
  *max = int32_t(lr_load_be32(base, g + 0xE8));
  return std::isfinite(*cur) && *max > 0 && *max < 10000000;
}

// The HUD's HP / ATB window (vt 0x82107EC8) through the GUI manager, as the game finds it
// (sub_82710528 / sub_8270E0E0): manager [0x831203AC], window id at +6888, looked up in the list at
// +2032 (next +16, id +84), else the list at +3804 (next +0, id +8, flag +12, object +16). Works in
// every battle type and in the field (the battle target window's +0x14 only held it in the prologue
// battle; the owner's field battles had no HP/ATB). Verified: prologue 2500/2500, ATB 6000/10000/5683;
// Luxerion field 2600/2600.
bool lr_companion_hp_window(uint8_t* base, uint32_t* out) {
  uint32_t mgr = 0, id = 0, n = 0;
  if (!lr_companion_u32(base, 0x831203AC, &mgr) || !mgr || !lr_companion_u32(base, mgr + 6888, &id)) return false;
  if (id == 0xFFFFFFFFu) return false;
  if (lr_companion_u32(base, mgr + 2032, &n)) {
    for (int k = 0; n && k < 256; ++k) {
      uint32_t wid = 0;
      if (!lr_companion_u32(base, n + 84, &wid)) break;
      if (wid == id) {
        if (!lr_companion_vt(base, n, 0x82107EC8u)) return false;
        *out = n;
        return true;
      }
      if (!lr_companion_u32(base, n + 16, &n)) break;
    }
  }
  if (lr_companion_u32(base, mgr + 3804, &n)) {
    for (int k = 0; n && k < 256; ++k) {
      uint32_t wid = 0, flag = 1, obj = 0;
      if (lr_companion_u32(base, n + 12, &flag) && flag == 0 && lr_companion_u32(base, n + 8, &wid) && wid == id &&
          lr_companion_u32(base, n + 16, &obj) && lr_companion_vt(base, obj, 0x82107EC8u)) {
        *out = obj;
        return true;
      }
      if (!lr_companion_u32(base, n, &n)) break;
    }
  }
  return false;
}

// Copies a guest C string for the state line: printable ASCII only, without ';' and '='.
std::string lr_companion_text(uint8_t* base, uint32_t addr, size_t max = 40) {
  std::string s;
  if (!addr || !lr_companion_readable(addr, 1)) return s;
  for (size_t i = 0; i < max && lr_companion_readable(addr + uint32_t(i), 1); ++i) {
    char c = char(base[addr + i]);
    if (!c) break;
    if (c < 32 || c > 126 || c == ';' || c == '=' || c == ',' || c == ':') c = ' ';
    s += c;
  }
  while (!s.empty() && s.back() == ' ') s.pop_back();
  return s;
}

bool lr_companion_in_battle(uint8_t* base, uint32_t* target) {
  // The battle HUD's target window exists only during battles (field: pointer 0, verified on the
  // Thor 2026-11-12). The battle scene path at [0x830D8398]+0x100 is set only for scripted battles
  // (the prologue), not for field encounters, so it is not required.
  const uint32_t t = g_lr_btl_target.load(std::memory_order_acquire);
  if (!lr_companion_vt(base, t, 0x8210BCB0u)) return false;
  *target = t;
  return true;
}

// The active schema (0..2) while a battle runs; -1 when unknown.
int lr_companion_active_schema(uint8_t* base) {
  uint32_t g = 0;
  if (!lr_companion_u32(base, 0x830D8398, &g) || !lr_companion_readable(g + 0x745, 1)) return -1;
  const int v = base[g + 0x745];
  return v <= 2 ? v : -1;
}

// Abilities per schema (Y, X, B, A), remembered from the command window as each schema becomes
// active, keyed by the schema's name and garb (a change of either forgets that schema's entry).
// Saved in the app folder so the rows are filled from the first battle of the next session.
// Only the bottom screen's drawing thread calls LrCompanionState, so no lock.
struct LrAbilityCache {
  std::string sig;
  std::string ab[4];
};
LrAbilityCache g_lr_ability_cache[3];
bool g_lr_ability_cache_loaded = false;

std::string lr_ability_cache_path() { return lr_companion_app_dir() + "companion_abilities.txt"; }

void lr_ability_cache_load() {
  if (g_lr_ability_cache_loaded) return;
  g_lr_ability_cache_loaded = true;
  std::ifstream in(lr_ability_cache_path());
  std::string line;
  while (std::getline(in, line)) {
    // i \t sig \t Y \t X \t B \t A
    std::vector<std::string> f;
    size_t start = 0;
    for (size_t pos; (pos = line.find('\t', start)) != std::string::npos; start = pos + 1)
      f.push_back(line.substr(start, pos - start));
    f.push_back(line.substr(start));
    if (f.size() != 6 || f[0].size() != 1 || f[0][0] < '0' || f[0][0] > '2') continue;
    LrAbilityCache& c = g_lr_ability_cache[f[0][0] - '0'];
    c.sig = f[1];
    for (int k = 0; k < 4; ++k) c.ab[k] = f[2 + k];
  }
}

void lr_ability_cache_save() {
  std::ofstream out(lr_ability_cache_path(), std::ios::trunc);
  for (int i = 0; i < 3; ++i) {
    const LrAbilityCache& c = g_lr_ability_cache[i];
    if (c.sig.empty()) continue;
    out << i << '\t' << c.sig;
    for (int k = 0; k < 4; ++k) out << '\t' << c.ab[k];
    out << '\n';
  }
}

// The schema's identity this poll: a different name or garb drops its remembered abilities.
void lr_ability_cache_schema(int i, const std::string& sig) {
  lr_ability_cache_load();
  LrAbilityCache& c = g_lr_ability_cache[i];
  if (c.sig == sig) return;
  const bool had = !c.sig.empty();
  c.sig = sig;
  for (auto& a : c.ab) a.clear();
  if (had) lr_ability_cache_save();
}

void lr_ability_cache_store(int i, const std::string (&shown)[4]) {
  bool any = false;
  for (int k = 0; k < 4; ++k) any |= !shown[k].empty();
  if (!any) return;
  LrAbilityCache& c = g_lr_ability_cache[i];
  bool changed = false;
  for (int k = 0; k < 4; ++k) {
    if (c.ab[k] != shown[k]) {
      c.ab[k] = shown[k];
      changed = true;
    }
  }
  if (changed && !c.sig.empty()) lr_ability_cache_save();
}

// Appends the battle keys; returns the new length.
int lr_companion_battle(uint8_t* base, char* out, size_t size, int n) {
  uint32_t tgt = 0;
  if (!lr_companion_in_battle(base, &tgt)) return n;
  auto put = [&](const char* fmt, auto... args) {
    if (size_t(n) + 8 >= size) return;
    int k = std::snprintf(out + n, size - n, fmt, args...);
    if (k > 0) n = std::min<int>(n + k, int(size) - 1);
  };
  put("%s", ";battle=1");
  const int active = lr_companion_active_schema(base);
  if (active >= 0) put(";active=%d", active);
  uint32_t win = 0, mem = 0;
  if (lr_companion_hp_window(base, &win) && lr_companion_ptr(base, win, 0x90, 0x82107E40u, &mem) &&
      lr_companion_readable(mem + 8, 12)) {
    // HP: the HUD's HP gauge at member+0x1C (vt 0x821393E0: +0xE0 current float, +0xE8 max int);
    // +0x20 is the red damage trail, +0x24 the heal preview. The member's ints are +0x08 current and
    // +0x0C/+0x10 max (they only looked swapped in the prologue, where all three read 2500).
    // Verified: field battle fan3 416/2967, fan4 0/2967 (KO; HUD 0), prologue btl1 2500/2500,
    // field plaza2 2600/2600.
    uint32_t hg = 0;
    float hcur;
    int hmax;
    if (lr_companion_u32(base, mem + 0x1C, &hg) && lr_companion_gauge(base, hg, &hcur, &hmax))
      put(";hp=%d;hpmax=%d", int(hcur + 0.5f), hmax);
    else
      put(";hp=%d;hpmax=%d", int32_t(lr_load_be32(base, mem + 8)), int32_t(lr_load_be32(base, mem + 0x10)));
    // EP is not exported: member+0x60 (read as EP until 2026-11-13) is the HP number's digit count
    // (ctor sub_82748830 stores 4, setter sub_827468D0 indexes a per-digit x-offset table at
    // 0x82107E84); it only matched the orb because every HP seen had four digits. No ep/epmax key
    // until the EP source is found (snapshot pair around an EP ability, docs/dual_screen_battle_re.md).
    for (int i = 0; i < 3; ++i) {
      uint32_t g = 0;
      float cur;
      int max;
      if (lr_companion_u32(base, mem + 0x8C + 4 * i, &g) && lr_companion_gauge(base, g, &cur, &max))
        put(";s%datb=%d;s%datbmax=%d", i, int(std::floor(cur / 100.f + 0.5f)), i, max / 100);
    }
  }
  uint32_t recs = 0;
  if (lr_companion_u32(base, 0x8315817C, &recs)) {
    // EP ("Gp" in the game: getPartyGp / getPartyMaxGp), u32 at +0x2850 and its maximum at +0x2854,
    // in 1/2000 EP (iGpUnit): 2000 = 1.0, max 10000 = 5.0 (docs/dual_screen_battle_re.md "EP").
    uint32_t gp = 0, gp_max = 0;
    if (lr_companion_u32(base, recs + 0x2850, &gp) && lr_companion_u32(base, recs + 0x2854, &gp_max) &&
        gp_max > 0 && gp_max <= 100000 && gp <= gp_max)
      put(";ep=%.2f;epmax=%.1f", gp / 2000.0, gp_max / 2000.0);
    for (int i = 0; i < 3; ++i) {
      const uint32_t r = recs + 0xA0 + uint32_t(i) * 0x130;
      uint32_t garb_id = 0;
      if (!lr_companion_u32(base, r, &garb_id) || garb_id == 0xFFFFFFFFu) continue;
      std::string garb = lr_companion_text(base, r + 0x70, 32);
      std::string name = lr_companion_text(base, r + 0x40, 32);
      if (name.empty()) name = garb;
      put(";s%dname=%s;s%dgarb=%s", i, name.c_str(), i, garb.c_str());
      lr_ability_cache_schema(i, name + "|" + garb);
    }
  }
  const uint32_t cmd = g_lr_btl_command.load(std::memory_order_acquire);
  if (lr_companion_vt(base, cmd, 0x82130CA0u)) {
    static const uint32_t kSlots[4] = {0x214, 0x274, 0x154, 0x1B4};  // Y, X, B, A
    std::string shown[4];
    for (int k = 0; k < 4; ++k) {
      uint32_t p = 0;
      if (!lr_companion_u32(base, cmd + kSlots[k], &p)) continue;
      shown[k] = lr_companion_text(base, p, 32);
      if (!shown[k].empty()) put(";ab%d=%s", k, shown[k].c_str());
    }
    // The command window shows the active schema's abilities: remember them for that schema, so
    // all three rows fill in once the player has switched through them (kept across sessions).
    if (active >= 0) lr_ability_cache_store(active, shown);
  }
  for (int i = 0; i < 3; ++i) {
    for (int k = 0; k < 4; ++k) {
      const std::string& ab = g_lr_ability_cache[i].ab[k];
      if (!ab.empty()) put(";s%dab%d=%s", i, k, ab.c_str());
    }
  }
  uint32_t chain = 0, name_ptr = 0;
  if (lr_companion_ptr(base, tgt, 0x10, 0x82106438u, &chain) && lr_companion_u32(base, chain + 0xA8, &name_ptr)) {
    std::string en = lr_companion_text(base, name_ptr, 40);
    if (!en.empty()) put(";ename=%s", en.c_str());
  }
  uint32_t eg = 0;
  float ecur;
  int emax;
  // The target's HP gauge: +0xB8 in the prologue battle; the neighbouring gauge slots (+0xB0..+0xC4)
  // as fallback for other battle types (the first live gauge with a maximum).
  for (uint32_t off : {0xB8u, 0xBCu, 0xB4u, 0xB0u, 0xC0u, 0xC4u}) {
    if (lr_companion_u32(base, tgt + off, &eg) && lr_companion_gauge(base, eg, &ecur, &emax)) {
      put(";ehp=%d;ehpmax=%d", int(ecur + 0.5f), emax);
      break;
    }
  }
  // Stagger: not exported yet. Bit 0x200 of the target window's +0x64 turned out to follow the
  // status-icon row under the enemy's name (set for minutes with no stagger in the 2026-11-13 video),
  // so the candidates are only logged with the state line for comparison against video:
  // the state word, the chain window's clip id (+0x2F8: 0x47 seen while staggered) and the enemy's
  // motion name (actor+0x33A8: "..._break1" seen while staggered).
  uint32_t tstate = 0, chain_w = 0, clip = 0;
  lr_companion_u32(base, tgt + 0x64, &tstate);
  if (lr_companion_u32(base, tgt + 0x10, &chain_w) && chain_w) lr_companion_u32(base, chain_w + 0x2F8, &clip);
  std::snprintf(g_lr_stagger_dbg, sizeof(g_lr_stagger_dbg), "st %X clip %X", tstate, clip);
  // Vulnerabilities as the Libra screen shows them (docs/dual_screen_battle_re.md "Libra"): the target
  // window's actor handle +0xE0 -> actor [[[0x830D839C]+0x154] + 4*(handle>>16)] (sub_823F4568);
  // s32[7] percent damage at actor+0x1314 (physical, magic, fire, ice, thunder, -, wind); revealed
  // when bit 10+e of u32 [actor+0x38C]+0x3C is set (the live record; the saved notes lag a battle).
  uint32_t handle = 0, g1 = 0, tbl = 0, actor = 0, rec = 0, seen = 0;
  if (lr_companion_u32(base, tgt + 0xE0, &handle) && lr_companion_u32(base, 0x830D839C, &g1) && g1 &&
      lr_companion_u32(base, g1 + 0x154, &tbl) && tbl &&
      lr_companion_u32(base, tbl + 4 * (handle >> 16), &actor) && actor &&
      lr_companion_u32(base, actor + 0x38C, &rec) && rec && lr_companion_u32(base, rec + 0x3C, &seen)) {
    static const struct { int idx; const char* key; } kVuln[6] = {
        {2, "fire"}, {3, "ice"}, {4, "thunder"}, {6, "wind"}, {0, "phys"}, {1, "magic"}};
    char buf[160];
    int m = 0;
    for (int k = 0; k < 6 && m < int(sizeof(buf)) - 24; ++k) {
      uint32_t raw = 0;
      if (!lr_companion_u32(base, actor + 0x1314 + 4 * kVuln[k].idx, &raw)) break;
      const int v = int32_t(raw);
      const char* label = "?";
      if (seen & (1u << (10 + kVuln[k].idx)))
        label = v >= 120 ? "weak" : (v >= 30 && v <= 70) ? "halved" : (v >= 1 && v <= 29) ? "resist"
              : v == 0 ? "immune" : v < 0 ? "absorb" : "normal";
      m += std::snprintf(buf + m, sizeof(buf) - m, "%s%s:%s", m ? "," : "", kVuln[k].key, label);
    }
    if (m > 0) put(";vuln=%s", buf);
    const std::string motion = lr_companion_text(base, actor + 0x33A8, 24);
    const size_t used = std::strlen(g_lr_stagger_dbg);
    std::snprintf(g_lr_stagger_dbg + used, sizeof(g_lr_stagger_dbg) - used, " motion %s", motion.c_str());
  }
  return n;
}

// ---- Map markers (docs/dual_screen_map_re.md "Markers") -----------------------------------------
// The minimap's icons live in GuiNaviMap (vtable 0x821279C8): 160 slots of icon objects at
// +9196 (+ 4 * id), count at +10020 (sub_827D3E18 releases one). An icon object (vtable 0x8213FDB8)
// holds its world X/Z at +0x70/+0x74 and its type at +0xE0 (set by sub_82840188; -1 = unused).
// Types come from the field objects' data (u5DefaultNaviIconType), not from a table in the code;
// the tokens below are named from what the minimap draws at each marker's position (Luxerion plaza).
// The GuiNaviMap instance is taken from the icon creator sub_827D6958 (r3) and re-checked by vtable.
std::atomic<uint32_t> g_lr_navimap{0};

const char* lr_marker_token(uint32_t type) {
  switch (type) {
    case 3: return "quest";   // cyan "!" circle (quest giver / objective)
    case 4: return "enemy";   // red dot: roaming enemies (moves; next to the Gremlins and Fanatics)
    case 8: return "warp";    // exits / stations (two in Luxerion; icon not yet confirmed)
    case 13: return "inn";    // gold bed
    case 17: return "npc";    // green dot
    case 19: return "shop";   // red and white shield
    case 22: return "door";   // roof (building entrance)
    default: return nullptr;
  }
}

int lr_companion_markers(uint8_t* base, char* out, size_t size, int n) {
  const uint32_t nm = g_lr_navimap.load(std::memory_order_acquire);
  if (!lr_companion_vt(base, nm, 0x821279C8u) || !lr_companion_readable(nm + 9196, 160 * 4)) return n;
  // Places and quests first, then NPC dots, so a full line drops NPCs rather than objectives.
  int written = 0;
  for (int pass = 0; pass < 2; ++pass) {
    for (uint32_t i = 0; i < 160 && written < 64; ++i) {
      uint32_t icon = lr_load_be32(base, nm + 9196 + i * 4);
      if (!icon || !lr_companion_readable(icon + 0x70, 0x74)) continue;
      const uint32_t type = lr_load_be32(base, icon + 0xE0);
      if (type == 0 || type == 0xFFFFFFFFu || type > 63) continue;  // 0 = Lightning herself
      if ((type == 17) != (pass == 1)) continue;
      // DeadCharaManager (sub_82634230) leaves a type-4 icon with state 2 (u32 +0xE4, set by
      // sub_82840748) at the corpse; live enemies read 0. docs/dual_screen_map_re.md "Dead enemy icons".
      if (type == 4 && lr_load_be32(base, icon + 0xE4) == 2u) continue;
      const float x = lr_companion_f32(base, icon + 0x70), z = lr_companion_f32(base, icon + 0x74);
      if (!std::isfinite(x) || !std::isfinite(z) || std::fabs(x) > 1e5f || std::fabs(z) > 1e5f) continue;
      if (x == 0.f && z == 0.f) continue;  // icons not placed yet (a type-16 one sat at the origin)
      if (size_t(n) + 40 >= size) return n;
      const char* tok = lr_marker_token(type);
      char t[8];
      if (!tok) {
        std::snprintf(t, sizeof(t), "t%u", type);
        tok = t;
      }
      int k = std::snprintf(out + n, size - n, "%s%s:%.1f:%.1f", written ? "," : ";mk=", tok, x, z);
      if (k > 0) n += k;
      ++written;
    }
  }
  return n;
}
}  // namespace

REX_EXTERN(__imp__sub_827D6958);
REX_HOOK_RAW(sub_827D6958) {  // GuiNaviMap: create a minimap icon (r3 = the navimap)
  g_lr_navimap.store(ctx.r3.u32, std::memory_order_release);
  __imp__sub_827D6958(ctx, base);
}

REX_EXTERN(__imp__sub_82755768);
REX_HOOK_RAW(sub_82755768) {  // GuiBattleTargetInfoWindow constructor
  const uint32_t self = ctx.r3.u32;
  __imp__sub_82755768(ctx, base);
  g_lr_btl_target.store(self, std::memory_order_release);
}

REX_EXTERN(__imp__sub_827546C8);
REX_HOOK_RAW(sub_827546C8) {  // GuiBattleTargetInfoWindow destructor
  uint32_t self = ctx.r3.u32;
  g_lr_btl_target.compare_exchange_strong(self, 0u, std::memory_order_acq_rel);
  __imp__sub_827546C8(ctx, base);
}

REX_EXTERN(__imp__sub_827F96E8);
REX_HOOK_RAW(sub_827F96E8) {  // battle command (ability) window constructor
  const uint32_t self = ctx.r3.u32;
  __imp__sub_827F96E8(ctx, base);
  g_lr_btl_command.store(self, std::memory_order_release);
}

REX_EXTERN(__imp__sub_827F8AE0);
REX_HOOK_RAW(sub_827F8AE0) {  // battle command window destructor
  uint32_t self = ctx.r3.u32;
  g_lr_btl_command.compare_exchange_strong(self, 0u, std::memory_order_acq_rel);
  __imp__sub_827F8AE0(ctx, base);
}


#include "lr_companion_field.inc"

size_t LrCompanionState(char* out, size_t size) {
  lr_companion_dump_watch();
  if (!size) return 0;
  uint8_t* base = g_lr_guest_base.load(std::memory_order_relaxed);
  const uint32_t app = g_lr_fps_app.load(std::memory_order_relaxed);
  int mode = -1;
  if (base && app) mode = int(lr_load_be32(base, app + 788));
  int n = std::snprintf(out, size, "v=1;frames=%llu;mode=%d",
                        (unsigned long long)g_lr_frames.load(std::memory_order_relaxed), mode);
  if (n < 0) n = 0;
  // navi=ID:X:Z,... loaded navimaps, newest first (origins match navimap.geb records).
  static thread_local std::array<LrNavimapLoad, 32> navi{};
  if (lr_navi_snapshot(navi)) {
    std::array<const LrNavimapLoad*, 32> live{};
    size_t count = 0;
    for (auto& m : navi)
      if (m.order) live[count++] = &m;
    std::sort(live.begin(), live.begin() + count,
              [](const LrNavimapLoad* a, const LrNavimapLoad* b) { return a->order > b->order; });
    for (size_t i = 0; i < count && size_t(n) + 48 < size; ++i) {
      int k = std::snprintf(out + n, size - n, "%s%08X:%.3f:%.3f", i ? "," : ";navi=", live[i]->id,
                            live[i]->x, live[i]->z);
      if (k > 0) n += k;
    }
  }
  // worldx/worldz: Lightning's world position; heading: radians, 0 = facing map up (-Z),
  // positive clockwise on the map (+X right, +Z down), i.e. atan2(forward.x, -forward.z).
  float x, z, fx, fz;
  if (base && size_t(n) + 64 < size && lr_companion_leader(base, &x, &z, &fx, &fz)) {
    int k = std::snprintf(out + n, size - n, ";worldx=%.2f;worldz=%.2f;heading=%.3f", x, z,
                          std::atan2(fx, -fz));
    if (k > 0) n += k;
    // camheading: where the camera looks, same convention (the follow map turns with it, like the
    // game's minimap; Lightning's arrow shows heading - camheading).
    float cx, cz;
    if (size_t(n) + 24 < size && lr_companion_camera(base, &cx, &cz)) {
      k = std::snprintf(out + n, size - n, ";camheading=%.3f", std::atan2(cx, -cz));
      if (k > 0) n += k;
    }
  }
  if (base) n = lr_companion_battle(base, out, size, n);
  if (base) n = lr_companion_markers(base, out, size, n);
  if (base) n = lr_companion_field(base, out, size, n);
  n = int(std::min<size_t>(size_t(n), size - 1));
  // Debug logging: the state and the battle detection's raw inputs every 5 s (finding battles the
  // detection misses, e.g. field encounters).
  static uint64_t last_log = 0;
  const uint64_t now = lr_now_us();
  if (base && lr_diag_enabled() && now - last_log > 5000000) {
    last_log = now;
    const uint32_t t = g_lr_btl_target.load(std::memory_order_relaxed);
    uint32_t vt = 0, b = 0;
    lr_companion_u32(base, t, &vt);
    char scene[25] = {};
    if (lr_companion_u32(base, 0x830D8398, &b) && lr_companion_readable(b + 0x100, 24)) {
      for (int i = 0; i < 24; ++i) {
        char ch = char(base[b + 0x100 + i]);
        scene[i] = (ch >= 32 && ch < 127) ? ch : (ch ? '?' : 0);
        if (!ch) break;
      }
    }
    REXLOG_INFO("LR companion state: {} | target {:08X} vt {:08X} cmd {:08X} scene '{}' | {}", std::string(out, size_t(n)), t, vt,
                g_lr_btl_command.load(std::memory_order_relaxed), scene, g_lr_stagger_dbg);
    g_lr_stagger_dbg[0] = 0;
  }
  return size_t(n);
}

// The map pose only (the follow map animates at the display rate): Lightning's world X/Z, her
// heading and the camera's (radians, as in LrCompanionState). A handful of guarded reads; false when
// she is not in the field.
bool LrCompanionPose(float out[4]) {
  uint8_t* base = g_lr_guest_base.load(std::memory_order_relaxed);
  float x, z, fx, fz, cx, cz;
  if (!base || !lr_companion_leader(base, &x, &z, &fx, &fz)) return false;
  out[0] = x;
  out[1] = z;
  out[2] = std::atan2(fx, -fz);
  out[3] = lr_companion_camera(base, &cx, &cz) ? std::atan2(cx, -cz) : out[2];
  return true;
}
#endif
