# Second screen: field panel data (clock, day, EP, place) — research notes

Offline, from the plaza snapshots (`out/dual/dumps/plaza2_p0a..p2b.gmem`, Luxerion, HUD "06 Days
Left", 21:06 → 21:49) and the prologue battle (`btl1_*`). Implemented in
`src/lr_companion_field.inc`.

## Game clock — exported (`clock=HH:MM;day=N`)
- Owner: `[0x830DDE10]` (the game state; `sub_824A0BCC` loads it and calls `sub_824B9AC8(owner, …)`).
- Clock object: `[owner + 0x18E74]`; `sub_824D01F8(clock)` is its per-frame update (the Freeze
  Timer cheat's function).
- Time = u64 `clock+0x50` + u64 `clock+0xA0` (all readers add both before `sub_824CF178`), unit
  1/12500 s of game time (`… * 300000 / 24` in `sub_824D01F8`, `sub_824B4B…`), from day 0 00:00.
- `sub_824CF178(&time)` = `time / 1.08e9` (days, 00:00 based). Day boundaries for the game are at
  06:00 (`sub_824B4BB8` region: `(day*86400 + 21600) * 300000 / 24`), so the game day is
  `floor((t_s − 21600) / 86400)`.
- Verified: p0a 21:07 day 1, p1a 21:20, p2b 21:43 (HUD 21:06 just before p0a, 21:49 just after
  p2b); prologue battle 01:00 day 0. The HUD's "06 Days Left" on day 1 fits a final day of 7 (LR
  starts with 7 days; more are added later).

## Days left — NOT exported
- `daysleft = lastDay − day` needs the current last day (7 now, up to 13 later). Candidates in the
  owner holding 7 in the plaza: `+0x574` and `+0x1200` (`+0x1200` is −1 in the prologue, plausible
  for "not set before Day 1"), but no code path reading either through `[0x830DDE10]` was found, so
  it is not exported. Device check: after a day extension one of them should become 8+.
- The clock object has a u64 at `+0x1D0` = 2.16e9 (day 2 00:00) and an array of 0x38-byte timers
  after it; not the deadline.

## EP — NOT found
- No EP identifiers in the executable's strings; value searches (×1/×10/×100/×1000, float) for a
  current/max pair near (4.x, 5) in the field are swamped by look-alikes. Leads: the battle HUD
  member (vt 0x82107E40, HP at +0x08/+0x10, see dual_screen_battle_re.md) may hold EP next to HP;
  the field HUD's orb widget. Needs a device snapshot pair with EP changing (cast an EP ability).

## Place / region names — NOT exported
- The current location's display name is not resident as text in the field (only "Luxerion" inside
  an unrelated sentence, ASCII and UTF-16 searched); sub-area names (`$zm_maplx_p010` …) are text
  ids loaded with the map menu.
- The current mapset name is resident: `"mset_lxaa_def"` at 0x6D823000 / 0x6DAB50D0 / 0x6E315B28
  (0x6E315B28 sits next to `"chset_only_pc"`); no static pointer chain to it found yet. Region codes:
  lx = Luxerion, yu = Yusnaan, wl = Wildlands, dd = Dead Dunes (mapset.wdb `mset_*_def`).
- The region is already known on the Java side from the streamed navimap (map 1 = Luxerion, seen
  on the device); navimap names for ids 2-29 were not derived (maps 2-4 by shape only).

## Place, region and the main quest's destination (2026-11-12)

Exported as `place=`, `region=`, `quest=` (src/lr_companion_field.inc), decoded from the game's own
text resources (no build-time extraction needed; works for every language the game loads).

**Text resources (ZTR, resident).** `[0x831203AC]` = the message manager (0x6DE66040 in every
snapshot). `+0xDC` = head of a linked list of text resources (24 in the field, 7 in the prologue):
`system`, `mission`, `quest_resident_us`, `auto_res_us`, `z0100`, `auto_c50_us`, `en_npc_1xxx_us`...
Resource layout: +0x00 next, +0x04 prev, +0x08 name[16], +0x18 dictionary chunk count, +0x1C chunk
offset table (u32, relative to +0x30), +0x20 line info (per line: u8 chunk, u8 characters to skip,
u16 offset into the chunk's compressed strings), +0x24 line count, +0x28 id blob, +0x2C id pointer
array (sorted bytewise: binary search), +0x30 base of the dictionary chunks. The `system` resource
is the whole ZTR file kept in memory (header magic 1, 11257 lines, 0xAC chunks at 0x6DE857D0).
Chunk: u32 dictionary size, 3-byte pages (index, item, item), then compressed strings; a byte equal
to a page index expands recursively to the page's items. Lines end at `00 00`; bytes >= 0x80 start
two-byte control codes (`F9 41`..`F9 40` colour), `01` precedes a speaker name, `02` voice parts.

**Ids that matter (all in `system`).**
- `$zm_map<rr>_<nnn>` place labels (`$zm_maplx_011` = "North Station Plaza", `$zm_maplx_p010` =
  "North Station", `$zm_maplx_p020` = "Centrum", `$zm_mapyu_p501` = "The Ballroom"),
  `$zm_map<rr>_all` = region name ("Luxerion").
- `$dest_<rr>_<code>` main-quest destinations ("Talk to potential witnesses.", "Find the bandits'
  settlement."), `$fl_nt<nnnn>_<nnn>` field objective notes ("Defeat all hostiles"),
  `$fl_qst_ttl/odr/exp/fin<nnn>` side quest title / giver / description / result (74 quests).

**Current values.**
- Place: field manager `[0x830DE958]` + 0x10 = current place label id (`$zm_maplx_011`), +0x20 =
  region label id (`$zm_maplx_all`). Empty in the prologue (no field yet).
- Destination: message manager + 0x11E0 = current main-quest destination id (`$dest_lx_itah`), empty
  in the prologue. The buffer is the only `$dest_` occurrence that differs between the field and
  prologue snapshots; the destination tables (WDB) are static.

| snapshot | place | region | quest |
|---|---|---|---|
| plaza2_p0a / p1b / p2b | North Station Plaza | Luxerion | Talk to potential witnesses. |
| btl1_s0 / b2 (prologue) | (none) | (none) | (none) |

(Python mirror of the native decoder: out/dual/ztr_mirror.py.)

Confidence: text decoding high (many lines checked); the place id is the field manager's current
label in all field snapshots, but Lightning stayed inside North Station Plaza, so its update on
moving to another place is unverified; the destination id is medium-high (only one quest state seen).
`questname=` (the main quest's title) not found: titles of main quests weren't located.

**Device checks:** walk from the plaza into another district (place should change, e.g. to
"Centrum"); advance the main quest one step (quest should change); a save in Yusnaan (region =
"Yusnaan"); check the strings are exported in other languages if the game runs in one.

## Loading screen (`loading=1`)
- The NOW LOADING screen is shown by `sub_8270A068` (logs "showNowLoading() type:%d bHighLayer:%d", string 0x820F9028) and hidden by `sub_8271A330` (logs "hideNowLoading()", 0x820FA84C). Both are methods of the GUI manager (r3; +3892 holds the window handle, -1 when hidden).
- Show ORs 0x4 into the word at `[[0x831203AC]] + 0x1B1C` (the message manager also used for the quest text); hide clears it (`& ~4`). So `loading = ([[0x831203AC]+0x1B1C] & 4) != 0`, no hook needed.
- Snapshots: the word is 0 in plaza2_p0a/p2b (field) and btl1_s0/b2 (prologue battle).
- To check on the device: the bit is set during the train-ride "story so far" + NOW LOADING screen (Luxerion -> Yusnaan) and on save/area loads, and clears when the field shows. Other load-like screens (the `showHideFakeLoadingWindow` / `showHideNextZoneJumpFakeLoading` script calls, `GuiModelLoadingMsg`) may not set it.
