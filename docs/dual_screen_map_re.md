# Bottom-screen area map: what the game uses and how we capture it

Status 2026-11-11 (offline analysis; device checks listed at the end).

## The map art is static: 29 navimaps in scene00100
- `scene/lay/scene00100/bin/scene00100.x360.xwp` (system archive white_img2u, WPD pack) holds
  `navimap.geb`, a GENP tree (tagged binary: `0x87`+u32 name hash = key, `0xD0|n` map,
  `0xC0|n` / `0xA7 n` array, `0x90` f32, `0x8A` u16, `0x50/0x74/0x83/0x84/0xBA/0xDF` 1-byte values,
  bytes < 0x50 = small ints, `0xB7 0xCn` + n bytes = byte list). Root `e688ed50` = 29 records:
  - `bf396750` id 1..29, `590acad0` [origin X, origin Z] (world units), `f7c0246a` 512 (tile px),
  - `0a8bf4a1` rows: `{8da7fd74: index of the row's first tile, accec0b7: [x positions]}`.
    Row r is 512 px tall at y = r*512; a tile's x = value*128 px; tiles are numbered in order.
- Tiles: `scene/lay/scene00100/bin/scene00100_<id %02x>_split_<tile %02x>.x360.imgb` in the zone
  z0100 archive (117 files), 512x512 DXT1, Xenos-tiled, 16-bit swapped (tools/lr_gui_tex.py
  decode(24, 512, 512, data, tiled=True)). Land is the green channel (fill ~150, outlines 255),
  coloured pixels are gates/crossings. Seams verified: assembled maps are continuous.
- Big maps: 1 (2176x2048, streets; Yusnaan?), 2 (2560x3584, the Wildlands), 3 (2304x3584,
  Dead Dunes?), 4 (2048x2048, round cathedral + station line; Luxerion?); 5-29 are interiors
  (1-3 tiles; 13, 14, 27 have none).
- Scale: GuiNaviMap multiplies world deltas by 2.0 (constant at 0x820F7BB8): 2 px per world unit.
- `tools/lr_navimap.py GAME_ROOT OUT` builds `navimap_NN.png` (recoloured RGBA) + `navimaps.json`.
  The runtime needs the same at first use on the device (Java: Inflater + DXT1 + untile, or
  native), from the player's own game files; nothing derived from the game goes in the repo.
- Not used: `gui/zone/z%04d.grs` / `gui/scene/s%05d.*` (named in the executable, not on disc 1),
  `shop02 wmap` (the world map illustration, 1280x720; good for a world overview).

## Runtime: which navimap is loaded
- Loader command queue `sub_82389CE8`: case 25 calls `sub_8237C5B0(r3 loader, r4 type, r5 id,
  r6 block size, r7 texture size, f1 origin X, f2 origin Z, r10 flag)` which logs
  `[Loader] load Navimap id = %08x blocksize = %d texturesize = %d (%.3f,%.3f)` and queues the
  resource (packed id: navimap index in bits 22-27, tile in bits 15-21, scene in bits 0-14 for the
  split textures, see the path formatter in `sub_82D07508`). Unload: `sub_8236CBA8(r3, ?, r5 id)`
  (`[Loader] unload Navimap id = %08x`).
- **Implemented** (src/lr_companion.inc): hooks on both record the loaded navimaps; the state line
  gains `navi=ID:X:Z,...` (newest first). The origin matches a navimap.geb record exactly, which
  identifies the map. The first 64 loads are logged (`LR companion: navimap ... loaded`).

## GuiNaviMap (minimap / whole map) internals, for reference
- `GuiNaviMap` object: vtable 0x821279C8 (+144: 0x821279B8), ctor `sub_827DBDA0`, create
  `sub_827D7E18` ("memory size"). Prim pointer array at +4520 (1024 entries, MAX_MAP_PRIMS),
  per-prim names via the array at +292 (name at entry+48); prim vec4 at prim+288 (w = layer).
- `sub_827DB180(this, r4 name[16], f1 x, f2 z, r7 ...)` ("ALBA_WHOLE_MAP : prim[%d] mapInfo[%d]")
  adds named map-info prims around a centre, culled with the scale 2.0 above; called through the
  GUI API wrapper `sub_82710A38`. The prims are ALBA layout sprites over the tile textures plus
  the reveal masks ("map unmask! mapInfoBoxIndex"), so capturing prims is not needed for the art.
- Map-info boxes (reveal areas, place names `$zm_map{lx,yu,wl,dd,wd}_*`) are in `s00100m.lyb`
  (`bx_mai_NNNN` objects); a later step could grey out unvisited areas like the game does.

## Still needed for a live marker (device work)
1. Lightning's world position (X, Z) and heading, and the current zone: not found statically.
   Plan: memory snapshots (tools/companion_dump.sh) standing still / after walking a known
   direction, `gmem.py track f32` with ranges inside the loaded navimap's world rectangle
   (origin .. origin + size/2), then a hook or a pointer chain from a global.
2. Axis check: does image y grow with +Z or -Z, and is the origin the top-left of tile row 0?
   Verify by walking to a landmark (e.g. a station end) and comparing (pos - origin) * 2 to the
   landmark's pixel. The marker math is then `px = (x - ox) * 2`, `py = (z - oz) * 2` (or mirrored).
3. Confirm which navimap is "current" when several are loaded (interiors next to the big map):
   pick the newest whose rectangle contains the player.
4. Name the big maps (1-4) from a session in each region.

## Player position and heading (2026-11-11, from paired snapshots plaza2_p{0,1,2}{a,b})
- Chain (exported by `lr_companion_leader` in src/lr_companion.inc as worldx/worldz/heading):
  `g = [0x830DE958]; h = [g + 68]` (the actor handle the lookup `sub_825515C8` substitutes for
  the special handle -2 = field leader); `arr = [[0x830E75D8] + 904]`; `obj = [arr + 4 * (int16)(h >> 16)]`,
  valid when `[obj + 64] == h`; world matrix at `obj + 128` (rows right, up, forward, position; the
  parent `obj + 24` is the identity root). Position = floats at +176 (X), +184 (Z); forward = +160 / +168.
- Verified in the six dumps: handle 0x00230000, obj 0x6E0665A0 in every dump; position
  (-259.51, 0.15, 448.29) in p0a/p0b/p1a/p1b (her first walk was swallowed: every field is unchanged
  p0 -> p1, the tutorial hint was up) and (-264.22, 0.25, 441.96) in p2a/p2b; y at ground level,
  forward level (y = 0). GuiNaviMap+192 (the minimap's tracked position) holds the same point
  (-264, 442) in p2, set from `r30 + 112` in sub_827D96B8.
- The minimap's rotation (GuiNaviMap+288, set by sub_827D24A8 from sub_827D96B8) is the CAMERA's
  heading: handle [[0x830E5120] + 64] (obj 0x6E0CD470, pitched down 12 degrees, ~3 units up);
  atan2(forward.x, forward.z) = -0.0717 / -2.026 matches +288 = -0.0721 / -2.03.
- Heading sign: exported as atan2(forward.x, -forward.z): 0 = facing -Z (map up, since map
  py = (z - oz) * 2 grows with +Z), +pi/2 = facing +X (map right), i.e. clockwise on the map, the
  same sense as Android's Canvas.rotate. Unverified on device: that the map is not mirrored
  (check a landmark; if mirrored, flip the sign of X in both position and heading).

## Markers (minimap icons) — 2026-11-11
- **Where:** GuiNaviMap (vtable 0x821279C8) keeps its icons in 160 slots at `+9196 + 4*id` (count at `+10020`; `sub_827D3E18(navimap, id)` releases one, `sub_827D6958(navimap, type, ...)` creates one, `sub_82840188` sets the type). Icon objects (vtable 0x8213FDB8): world **X at +0x70, Z at +0x74**, **type at +0xE0** (-1 = unused), entity number in the `gr_naviicon` layout at +0xEE. The navimap instance is captured by a hook on `sub_827D6958` (r3) and re-checked by vtable on every read. Types come from the field objects' data (WDB `u5DefaultNaviIconType`), not a table in the code.
- **Verified (plaza2 dumps, Luxerion North Station plaza, night, day 6):** 27 icons; static ones identical across all six snapshots, 12 of the 20 NPC dots move. Type 0 is Lightning herself (equal to the leader chain position, skipped in the export).
- **Type tokens** (named by matching the minimap screenshot at each marker's offset from Lightning, minimap ~4.5 px per world unit, map-up = -Z): 3 `quest` (cyan "!" circle, 13 units behind her; high), 17 `npc` (green dots; high), 19 `shop` (red/white shield; high for the icon, the shop kind is a guess), 13 `inn` (gold bed; medium), 22 `door` (roof icon, partly hidden by the overlay; medium), 8 `warp` (two in Luxerion, at (-240, 412) near North Station and (-397, 763); icon not seen, low). Unmapped types export as `t<N>`. Quest destinations placed by GuiDestPoint ("[GuiNaviMapIcon] GuiDestPoint::_removeGuiIcon()") use the same slots; their type is not identified yet (no active destination in the plaza dumps).
- **Export:** `mk=TOKEN:X:Z,...` (world units, one decimal), places and quests first, then NPCs, at most 64. Example (plaza2_p0a): `mk=shop:-278.0:444.0,warp:-240.0:412.0,warp:-397.0:763.0,door:-283.0:434.0,quest:-260.8:461.2,inn:-256.0:443.0,npc:-267.8:461.2,...`
- **Icon rectangles** (original atlas pixels x0,y0,x1,y1; atlases from gui/resident/system.x360.xgr, 128x64 each):
  - player arrow: `naviicon` (0,1,13,17); quest "!" circle: `naviicon` (14,0,32,18) (the game tints it cyan); "!" square: `naviicon` (33,0,51,18)
  - shop (shield): `weiss_navi_icon` (23,1,43,25); inn (gold bed): `weiss_navi_icon` (27,27,51,46); door (roof): `weiss_navi_icon` (1,27,25,47); warp (guess, blue door): `weiss_navi_icon` (1,1,21,25)
  - other `weiss_navi_icon` pieces, meaning unknown: green (45,1,65,25), orange (67,1,89,25), diamond (91,1,115,25), pin (117,1,127,16), mask (53,27,73,45), keyhole (75,27,93,51), green loop arrow (95,27,117,45)
  - npc: the minimap draws plain green dots (no atlas rect needed; draw a filled circle ~4 px at the minimap's scale)
- **Quest text:** not exported. Leads: GuiMainQuestWindow.cpp, `mainQuest`/`mainQuestWork`/`npcQuestStatus` strings (image 0x820092A4 ff), the `quest%04d` / Quest-Text-Res pack loader.

## Dead enemy icons
2026-11-13, offline (scripts out/dual/deadicon/*.py). After a kill the game does not delete the enemy dot: `DeadCharaManager` (object at 0x82EDEE00; +4 = its icon id, -1 = none) runs sub_82634230 ("[GuiNaviMapIcon] DeadCharaManager::releaseNaviIconNoLock() icon %d"), which releases its previous icon and creates a new one with `sub_826FA5D0(type 4, state 2, corpse position)` (caller sub_8262E698 passes actor+240). It keeps one at a time (the next kill replaces it).
- **Icon state = u32 at icon+0xE4**, written by sub_82840748 (slot wrapper sub_827D27D0(navimap, id, state), re-applied on creation by sub_82841158). For type 4, state 2 swaps the icon's child sprites (child +0x14 shown, +0x18 hidden, animation 2): the "defeated" marker, not a live enemy.
- Evidence: Gremlin session slot 15 (icon 0x6D189FC0, x -156.6 z 511.0): +0xE4 = 0 in battle (g_item, s_hit1), 2 on the results screen and after (g_post, g_post2, st_none1/st_stag1/st_after1, 8 min later); DeadCharaManager+4 = 15 in g_post2/st_*. Every live type-4 icon reads 0: Fanatics st_* slots 10-12, fan3/fan4 and r_start/r_ko slots 6-8, Niblet lb_* slot 28. Enemy icons carry no owner handle (entity number +0xEE = 0xFFFF), so the state is the test.
- Note: e_field (before the fight) has no type-4 icon at all; the roaming Gremlin got its dot only when it came near.
- **Rule:** skip icons with type 4 and u32 +0xE4 == 2 (or export them as a separate `dead` token).
