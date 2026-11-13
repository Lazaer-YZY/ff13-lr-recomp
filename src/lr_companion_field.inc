// Second screen, field panel data (included from lr_companion.inc): the game clock and the day, the
// current place and region, and the main quest's current destination. Appends ";key=value" pairs to
// the state line. Every read is guarded (lr_companion_readable / lr_companion_u32). Findings and
// open items: docs/dual_screen_field_re.md.
//
// The game clock: the owner object [0x830DDE10] (the game state passed to sub_824B9AC8 from
// 0x824A0BE0) holds the clock object at +0x18E74 (sub_824D01F8 is its per-frame update). The time is
// the u64 at clock+0x50 plus the u64 at clock+0xA0 (every reader adds both before sub_824CF178), in
// 1/12500 s of game time (300000 / 24) counted from day 0, 00:00. A game day runs 06:00 -> 06:00
// (the readers add 21600 s to the day start). Checked against the plaza snapshots: 2030641800 ->
// 21:07 of day 1 (HUD 21:06-21:07, "06 Days Left"), the prologue battle: 1125000000 -> 01:00 of
// day 0 (before Day 1 begins).
//
// Game text: the ZTR text resources the game keeps resident.
// [0x831203AC] -> the message manager (same address in every snapshot):
//   +0xDC   head of the text resource list. Each resource: +0x00 next, +0x04 prev, +0x08 name[16]
//           ("system", "mission", "quest_resident_us", "z0100", ...), +0x18 dictionary chunk count,
//           +0x1C chunk offset table (u32 each, relative to +0x30), +0x20 line info (4 bytes per line:
//           u8 chunk, u8 characters to skip, u16 offset into the chunk's strings), +0x24 line count,
//           +0x28 id blob, +0x2C id pointer array (sorted by byte value), +0x30 base of the chunks.
//           A chunk: u32 dictionary size, the dictionary as 3-byte pages (index, item, item), then the
//           compressed strings; a byte equal to a page index expands to the page's two items
//           (recursively), any other byte is itself. A line ends at 00 00; bytes >= 0x80 start
//           two-byte control codes (colour, icons), 01 / 02 mark speaker / voice parts.
//   +0x11E0 the current main-quest destination's text id ("$dest_lx_itah" = "Talk to potential
//           witnesses."), empty before Day 1.
// The field manager [0x830DE958] (also the leader-position chain's start): +0x10 the current place's
// map label id ("$zm_maplx_011" = "North Station Plaza"), +0x20 the region's ("$zm_maplx_all" =
// "Luxerion"). Labels and destinations are lines of the "system" resource.
namespace {
uint64_t lr_companion_u64(uint8_t* base, uint32_t addr) {
  return (uint64_t(lr_load_be32(base, addr)) << 32) | lr_load_be32(base, addr + 4);
}

struct LrZtr {
  uint32_t nch = 0, offt = 0, linfo = 0, n = 0, idp = 0, data = 0;
};

bool lr_ztr_open(uint8_t* base, const char* name, LrZtr* z) {
  uint32_t mgr = 0, p = 0;
  if (!lr_companion_u32(base, 0x831203ACu, &mgr) || !lr_companion_u32(base, mgr + 0xDC, &p)) return false;
  for (int guard = 0; p && guard < 256; ++guard) {
    if (!lr_companion_readable(p, 0x34)) return false;
    if (std::strncmp(reinterpret_cast<const char*>(base + p + 8), name, 16) == 0) {
      z->nch = lr_load_be32(base, p + 0x18);
      z->offt = lr_load_be32(base, p + 0x1C);
      z->linfo = lr_load_be32(base, p + 0x20);
      z->n = lr_load_be32(base, p + 0x24);
      z->idp = lr_load_be32(base, p + 0x2C);
      z->data = lr_load_be32(base, p + 0x30);
      return z->n > 0 && z->n < 200000 && z->nch > 0 && z->nch < 4096;
    }
    p = lr_load_be32(base, p);
  }
  return false;
}

// Line index of a text id (binary search over the sorted id pointers), -1 = none.
int lr_ztr_find(uint8_t* base, const LrZtr& z, const std::string& key) {
  int lo = 0, hi = int(z.n) - 1;
  while (lo <= hi) {
    const int mid = lo + (hi - lo) / 2;
    uint32_t s = 0;
    if (!lr_companion_u32(base, z.idp + 4 * uint32_t(mid), &s) ||
        !lr_companion_readable(s, uint32_t(key.size()) + 1))
      return -1;
    const int c = std::strncmp(reinterpret_cast<const char*>(base + s), key.c_str(), key.size() + 1);
    if (c == 0) return mid;
    if (c < 0) lo = mid + 1;
    else hi = mid - 1;
  }
  return -1;
}

// The first line of a text as plain ASCII for the state line (control codes dropped; ';' '=' ','
// ':' replaced). Empty on any inconsistency.
std::string lr_ztr_line(uint8_t* base, const LrZtr& z, int i) {
  if (i < 0 || uint32_t(i) >= z.n || !lr_companion_readable(z.linfo + 4 * uint32_t(i), 4)) return {};
  const uint32_t e = z.linfo + 4 * uint32_t(i);
  const uint8_t k = base[e], skip = base[e + 1];
  const uint32_t off = (uint32_t(base[e + 2]) << 8) | base[e + 3];
  uint32_t rel = 0;
  if (k >= z.nch || !lr_companion_u32(base, z.offt + 4 * uint32_t(k), &rel)) return {};
  const uint32_t a = z.data + rel;
  uint32_t dsize = 0;
  if (!lr_companion_u32(base, a, &dsize) || dsize > 256 * 3 || !lr_companion_readable(a + 4, dsize)) return {};
  uint8_t px[256], py[256];
  bool page[256] = {};
  for (uint32_t j = 0; j + 3 <= dsize; j += 3) {
    const uint8_t id = base[a + 4 + j];
    px[id] = base[a + 5 + j];
    py[id] = base[a + 6 + j];
    page[id] = true;
  }
  page[0] = false;
  const uint32_t body = a + 4 + dsize;
  std::string raw;
  unsigned skipped = 0;
  bool done = false;
  for (uint32_t pos = off; !done && pos < off + 2048; ++pos) {
    if (!lr_companion_readable(body + pos, 1)) break;
    uint8_t stack[96];
    int sp = 0;
    stack[sp++] = base[body + pos];
    while (sp > 0 && !done) {
      const uint8_t b = stack[--sp];
      if (page[b] && sp + 2 <= int(sizeof(stack))) {
        stack[sp++] = py[b];
        stack[sp++] = px[b];
        continue;
      }
      if (skipped < skip) {
        ++skipped;
        continue;
      }
      raw += char(b);
      const size_t m = raw.size();
      if ((m >= 2 && raw[m - 1] == 0 && raw[m - 2] == 0) || m > 600) done = true;
    }
  }
  std::string s;
  for (size_t j = 0; j < raw.size(); ++j) {
    const uint8_t c = uint8_t(raw[j]);
    if (c == 0) break;  // end of the line (or of its first part, before a speaker name)
    if (c >= 0x80) {
      ++j;  // two-byte control code
      continue;
    }
    if (c < 32) continue;
    s += (c == ';' || c == '=' || c == ',' || c == ':') ? ' ' : char(c);
  }
  while (!s.empty() && s.front() == ' ') s.erase(s.begin());
  while (!s.empty() && s.back() == ' ') s.pop_back();
  return s;
}

// A text id held in a guest buffer ("$" then [a-z0-9_]), empty if there is none.
std::string lr_companion_text_id(uint8_t* base, uint32_t addr) {
  std::string id;
  if (!lr_companion_readable(addr, 32) || base[addr] != '$') return id;
  for (uint32_t i = 0; i < 32; ++i) {
    const char c = char(base[addr + i]);
    if (!c) return id;
    if (!(c == '$' || c == '_' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return {};
    id += c;
  }
  return {};
}

// Decodes a "system" text by id; the last result per slot is cached (these ids change rarely).
struct LrTextCache {
  std::string id, text;
};

std::string lr_companion_game_text(uint8_t* base, const std::string& id, LrTextCache* cache) {
  if (id.empty()) return {};
  if (id == cache->id) return cache->text;
  LrZtr z;
  std::string text;
  if (lr_ztr_open(base, "system", &z)) text = lr_ztr_line(base, z, lr_ztr_find(base, z, id));
  cache->id = id;
  cache->text = text;
  return text;
}

int lr_companion_field(uint8_t* base, char* out, size_t size, int n) {
  // loading=1 while the game's NOW LOADING screen shows (docs/dual_screen_field_re.md "Loading
  // screen"): showNowLoading (sub_8270A068) sets bit 0x4 of the word at [[0x831203AC]] + 0x1B1C,
  // hideNowLoading (sub_8271A330) clears it. 0 in every field and battle snapshot.
  {
    uint32_t msg = 0, flags = 0;
    if (lr_companion_u32(base, 0x831203AC, &msg) && lr_companion_u32(base, msg + 0x1B1C, &flags) && (flags & 4) &&
        size_t(n) + 12 < size) {
      int k = std::snprintf(out + n, size - n, ";loading=1");
      if (k > 0) n += k;
    }
  }
  uint32_t owner = 0, clk = 0;
  if (lr_companion_u32(base, 0x830DDE10, &owner) && lr_companion_u32(base, owner + 0x18E74, &clk) &&
      lr_companion_readable(clk + 0x50, 8) && lr_companion_readable(clk + 0xA0, 8)) {
    const uint64_t ticks = lr_companion_u64(base, clk + 0x50) + lr_companion_u64(base, clk + 0xA0);
    const uint64_t secs = ticks / 12500;
    if (secs <= 40ull * 86400 && size_t(n) + 32 < size) {  // else not a game clock (yet)
      const unsigned sod = unsigned(secs % 86400);
      const int day = secs < 21600 ? 0 : int((secs - 21600) / 86400);
      int k = std::snprintf(out + n, size - n, ";clock=%02u:%02u;day=%d", sod / 3600, (sod / 60) % 60, day);
      if (k > 0) n += k;
    }
  }
  // Place, region and the main quest's current destination, worded as the game shows them.
  thread_local LrTextCache t_place, t_region, t_dest;
  uint32_t field = 0, mgr = 0;
  std::string place, region, quest;
  if (lr_companion_u32(base, 0x830DE958, &field)) {
    place = lr_companion_game_text(base, lr_companion_text_id(base, field + 0x10), &t_place);
    region = lr_companion_game_text(base, lr_companion_text_id(base, field + 0x20), &t_region);
  }
  if (lr_companion_u32(base, 0x831203ACu, &mgr))
    quest = lr_companion_game_text(base, lr_companion_text_id(base, mgr + 0x11E0), &t_dest);
  auto put = [&](const char* key, const std::string& v) {
    if (v.empty() || size_t(n) + v.size() + 16 >= size) return;
    int k = std::snprintf(out + n, size - n, ";%s=%s", key, v.c_str());
    if (k > 0) n += k;
  };
  put("place", place);
  put("region", region);
  put("quest", quest);
  return n;
}
}  // namespace
