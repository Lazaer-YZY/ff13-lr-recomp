# Second screen: battle data (offline RE, 2026-11-11)

Source: guest snapshots `out/dual/dumps/btl1_*` (prologue battle vs Anubys, The Savior, HP 2500,
tutorial pauses; b1 = right after an attack combo, b2 = 3 s later) and `plaza2_*` (Luxerion field,
day 6, three schemata). Code: `generated/default` (xref helper `out/dual/xref.py`, function index
`out/dual/funcs.idx`). Implemented in `src/lr_companion.inc` (`lr_companion_battle`).

## Objects (found through the battle HUD's own classes)
| What | Where | Verified |
|---|---|---|
| GuiBattleTargetInfoWindow | vtable 0x8210BCB0; ctor `sub_82755768` (r3 = this; uses layout "#BtlTgtInfoWin"), dtor `sub_827546C8`; both hooked, instance kept in `g_lr_btl_target` | b1/b2: 0x6D1897D0 |
| chain window ("#BtlChain") | target +0x10, vtable 0x82106438; +0xA8 -> enemy name (char*) | "Anubys" |
| HP/ATB window | target +0x14, vtable 0x82107EC8 (ctor `sub_82749618`); +0x90 -> member | b1/b2 |
| HP/ATB member | vtable 0x82107E40 (ctor `sub_82748830`); +0x08 HP, +0x0C shown HP, +0x10 max HP (all 2500 in battle, 2600 in the field save; current vs max not yet told apart by a hit) | b1/b2/plaza |
| ATB gauge per schema | member +0x8C / +0x90 / +0x94 -> gauge (vtable 0x821393E0): +0xE0 current (float), +0xE4 shown (float), +0xE8 max (int); ATB x100 | s0 6000 -> 9830 -> 10000 (screens 093 shortly after, then 100) |
| enemy HP | target +0xB8 -> gauge (same class), +0xBC the lagging one | 5000 -> 4214 after the combo |
| command window (abilities) | vtable 0x82130CA0; ctor `sub_827F96E8`, dtor `sub_827F8AE0` (hooked, `g_lr_btl_command`); char* +0x214 Y, +0x274 X, +0x154 B, +0x1B4 A (empty = image "") | Thunder / Guard / Attack / - |
| schema records | [0x8315817C] + 0xA0 + i*0x130, i = 0..2: +0x00 garb id (-1 empty), +0x40 schema name (empty = the garb's), +0x70 garb name, +0xB0 garb model | battle: The Savior/Equilibrium, Dark Muse; field: + Mist Wizard |
| battle flag | target window alive AND battle global [0x830D8398] +0x100 starts "scene/bt" ("scene/btsc05600" here; zero in the field) | b1/b2 vs plaza |
| battle chara table | battle global +468 (indexed by handle >> 16, `sub_823F48F0`) | empty in these snapshots |

## Found offline (2026-11-11 night)
| What | Where | How |
|---|---|---|
| Active schema | u8 at `[0x830D8398] + 0x745` (battle global) | `sub_823E6B78` returns it; SetATB `sub_82747DF0(member, value, schema)` compares its schema argument with it. 0 in every snapshot (prologue = one schema; field = no battle running). |
| HP current vs max | member +0x08 = **max**, +0x0C / +0x10 = current | SetHP `sub_82746840(member, hp)` stores hp at +0x0C and +0x10 and sets the gauge (+0x20) maximum from +0x08. The first export had them swapped. |
| ATB gauges | member +0x8C+4i (main), +0x98+4i and +0xA4+4i (animated copies) | SetATB writes all three for schema i. Schemata 1 and 2 have live gauges even in the one-schema prologue (b1: 6000 / 10000 / 5683). |
| Inactive schemata's abilities | not in the schema record (+0xB0/+0xC0/+0xD0 are the garb, weapon and shield model names, e.g. p_lt_ba00, w_ltw_ea08, w_lts_ea00) | Cached instead: the command window's four names are stored for the active schema each poll and kept per schema (keyed by name and garb), saved to `<app dir>/companion_abilities.txt`. All three rows fill in once the player has switched through the schemata, and from the start of later sessions. |
| Battle flag lifetime | the target window's destructor hook clears `g_lr_btl_target`; the field has no "scene/bt" path at battle global +0x100 | `battle=1` ends with the battle. |

## Exported keys
`battle=1`, `active`, `hp` (current), `hpmax`, `s{i}ab0..3` (remembered abilities per schema), `s{i}atb`, `s{i}atbmax`, `s{i}name`, `s{i}garb`, `ab0..ab3` (the
active schema's abilities, Y X B A; CompanionState puts them on the active row once `active` is
known), `ename`, `ehp`, `ehpmax`.

## Not found / not verified (next device probe)
- **Verify on the Thor:** `active` follows LB/RB, the cached abilities fill all three rows, and
  `hp` drops below `hpmax` after a hit. Probe: `bash tools/companion_battle_probe.sh btl2` in a
  three-schema battle (any save after the prologue; Luxerion at night has roaming monsters), then
  read `LrCompanionState` via the bottom screen or a snapshot (battle global +0x745, member +0x10).
- **Stagger** and weaknesses/resistances (Libra): chain window +0xD0/+0xD4 and the target window's
  sub-objects (+0xB0/+0xB4/+0xC0/+0xC4) +0x60/+0x64 are only screen positions (they follow the enemy:
  772 -> 633 -> 926). After three hits in the prologue nothing else in the target window changed, so
  the tutorial battle runs no stagger wave. LR shows a wave, not a percentage: export `staggerfill`
  (0..1) and a staggered state once found. Needs snapshots of a real fight: before hits, while the
  wave is high, and while staggered; plus one after Libra shows weaknesses (element icons under the
  target name).
- **EP**: not in the HUD's gauge objects (no gauge with EP-like values in the field or battle
  snapshots); the EP orb (field HUD bottom left, "4" in the plaza) is another class. Next: find the
  orb's number source via the field HUD code, or snapshot pairs before/after an EP ability.

## 2026-11-12 — HP / ATB in field battles (the owner's play: missing outside the prologue)
- The chain target window +0x14 -> HP/ATB window only held in the prologue battle. The game itself
  finds the HP/ATB window through the GUI manager (sub_82710528 -> sub_8270E0E0): manager
  `[0x831203AC]`, window id `[mgr+6888]`, list `[mgr+2032]` (next +16, id +84), fallback list
  `[mgr+3804]` (next +0, id +8, flag +12 == 0, object +16). The window (vt 0x82107EC8) is resident
  (the field HUD shows HP too), its +0x90 member (vt 0x82107E40) holds HP max +0x08 / current +0x10
  and the three ATB gauges at +0x8C/+0x90/+0x94 as before.
- Verified: btl1_b1 (prologue) id 34 -> 0x6D1A81E0, HP 2500/2500, ATB 6000/10000/5683 (x100);
  plaza2_p0a (Luxerion field) id 32 -> 0x6D1B6CB0, HP 2600/2600 (field: gauges idle).
- Enemy HP: target +0xB8 (prologue), with +0xBC/+0xB4/+0xB0/+0xC0/+0xC4 tried next for other battle
  types (no field-battle snapshot was available: fan1.gmem was never written; verify on the device).

## 2026-11-13 — HP from the HUD's HP gauge; EP (field battle snapshots fan3/fan4)
- HP window member (vt 0x82107E40, via the GUI manager) holds gauges (vt 0x821393E0): +0x1C = HP (cur float +0xE0, max int +0xE8), +0x20 = red damage trail, +0x24 = heal preview. Values: fan3 416/2967, fan4 0/2967 (Lightning KO, HUD 0), btl1 2500/2500, plaza2 2600/2600. The member's ints: +0x08 = current, +0x0C/+0x10 = max (fan3 416 / 2967 / 2967). The earlier export read +0x10 as current and +0x08 as max: right only in the prologue, where all were 2500.
- EP: member+0x60 = 4 in every snapshot (field and battle), the number in the HUD's EP orb. Exported as ep=4.0; epmax unknown (win+0x94 varies 3-5 between snapshots: not the max). Medium-low confidence; fractional EP (the orb's ring) not found.
- Device check: in a field battle, hp should follow the HUD number when Lightning is hit; after an EP ability (e.g. Curaja / Overclock costs EP) ep should drop by its cost. Snapshot pair before/after an EP ability: `bash tools/companion_dump.sh ep_before` / `ep_after`, then compare member+0x60 and look for a float near it.

## 2026-11-13 (later) — member+0x60 is not EP; EP export removed

- The HP member's constructor (sub_82748830, stores vt 0x82107E40) writes +0x60 = 4 unconditionally, and
  sub_827468D0 (`if (n > 0) member+0x60 = n`) indexes the table at 0x82107E84 (0, 100, 80, 60, 40, 20)
  with it: it is the HP number's digit count / layout offset. Every HP seen so far (2500, 2600, 2967)
  has four digits, which is why it matched the EP orb's "4". The `ep=` key is no longer exported.
- EP max 5 / current 4 searched in r_ko, r_start, fan3, fan4: no adjacent pair as u32 (4/5, 40/50,
  400/500, 4000/5000), and f32 4.0/5.0 hit thousands of unrelated addresses. A battle stats block at
  0x6E111990.. holds max HP 2967 (+0x0C), 10000s and 5000 at 0x6E111A48 (next to 10500): unidentified,
  constant across all battle snapshots, absent in the field (plaza2).
- Next: snapshot pair right before and after an EP ability (or after a kill that refills EP) and
  `gmem.py same`/`track` for the changed value; the EP menu (r_ko, "Current EP") is a second route via
  the window that formats it.
- Stagger and Libra weaknesses: not attempted in this pass.

## Stagger

Snapshots st_stag1 (paused, Fanatic A staggered, HP 8988), st_after1 (paused ~1 min later, HP 8280),
st_none1 (running, not staggered); target info window T = 0x6D191450 in all three. Scripts:
out/dual/stag1.py .. stag8.py (stag8 checks every dump in out/dual/dumps).
- **Staggered flag: u32 T+0x64, bit 0x200.** 0x1A40 in st_stag1, 0x18C0 in st_after1/st_none1 and in
  every other battle dump (btl1_*, fan3/4, g_ep1/g_item, r_ko/r_start, s_hit1), paused or not. Staggered
  sets 0x200 and clears 0x80. High confidence (one staggered sample, 20+ non-staggered).
- Corroborating (same window, stagger only): T+0xC0 (layout container, vt 0x82141F40) +0xF4 child count
  = 1 with child +0xEC -> node vt 0x82141F3C named "sys68" at +0x18 (the STAGGER banner effect), 0 in
  the others; chain window (T+0x10, "#BtlChain") animation clip id u32 +0x2F8 = 0x47 / f32 +0x2FC
  1000 ms (0x41 / 166.7 ms or 0x29 / 250 ms otherwise; stale pointer at +0x2F0 = 0 while staggered).
- after1 was no longer staggered (flag clear, enemy motion back to "m293_def").
- Enemy actor (game side, vt 0x8203BAB0; Fanatic A 0x6E111680; also Lightning 0x6E115760 and Fanatics
  B/C 0x6E119840/0x6E11D920): +0x2F0/+0x318 current HP u32, +0x31C max HP; current motion name at
  +0x33A8 = "m293_break1" while staggered ("m293_def" otherwise). No pointer to it from the HUD found.
- Not found: no stagger/chain percent (LR has no chain bonus %), no stagger meter / wave amplitude and
  no stagger timer in the window, its children (2 levels) or the actor (single staggered sample; a
  timer needs two staggered snapshots seconds apart). The HP gauge's red section (gauge T+0xBC,
  +0xE0 = 10393 vs shown 8988, trail timer +0x110 = 716 of +0x114 = 1500) is the damage trail, not
  stagger.

## EP
Current EP ("Gp": script functions getPartyGp / getPartyMaxGp) is a u32 at [0x8315817C] + 0x2850, its
maximum at + 0x2854, both in 1/2000 EP (iGpUnit, battle constants near 0x6DB169B0): 2000 = 1.0, 10000 = 5.0.
Verified across 10 snapshots in three sessions (1.0 shown "1/5"; 1.0475 after a Gremlin, the results screen
floors the gain to "0.04"; 4.0 with the HUD orb "4"; 4.4; 0.75). The battle menu shows EP floored.
The old `ep=` (member+0x60) was the HP number's digit count.

## Libra / vulnerabilities
2026-11-13, offline from lb_before / lb_screen / lb_after (Niblet, spec "m300", actor 0x6E115760) plus
g_*/s_hit1 (Gremlin m041), fan3/r_* and st_* (Fanatic m293). Scripts: out/dual/lb/*.py (wpd.py/wrec.py
decode the in-memory WDB datasheets; LSB-first bit packing inside each 32-bit word). Code read from the
recomp: GuiLibra item update sub_827774F8, resist lookup sub_823DE990, category sub_828E1A60.
- **Target -> actor.** Target info window T (vt 0x8210BCB0) +0xE0 = actor handle; actor =
  [[[0x830D839C] + 0x154] + 4 * (handle >> 16)] (game's own resolver sub_823F4568; low half is a serial).
  Checked in 6 dumps / 3 enemy kinds (lb: 0x10000 -> Niblet; st_*: 0x2 -> Fanatic A at slot 0).
  Actor+0x23B8 = spec name (char[16], "m300"); actor+0x23B0 -> its BattleCharaSpec record.
- **Values: s32[7] at actor+0x1314, index e = 0 physical, 1 magic, 2 fire, 3 ice, 4 thunder, 5 unused
  (always 0, not shown), 6 wind.** Percent damage taken, copied from BattleCharaSpec i10ElemDefExVal0..6.
  Niblet = 50,150,150,100,100,0,100 (Gremlin 150,50,100..; Fanatic all 100). The Libra screen maps a value v
  with thresholds at 0x830D8430+0x24C..0x25C (120, 1, 29, 30, 70): v >= 120 Weakness ($libra_08), 30..70
  Halved ($libra_09), 1..29 resistant ($libra_12), 0 immune ($libra_10), v < 0 absorb ($libra_11), else
  Normal ($libra_13). Element order from DamageSrc u1ElemAttr0..6 (attack at010_10/20/30/50 = phys+bit2/3/4/6,
  spells mb000/100/200/400 = magic+bit2/3/4/6) and Niblet's screen. Caveats (game code, not seen live): if
  u32 actor+0x2D0 != 0 a BattleState override is used instead; an active status 23 replaces e >= 2.
- **Revealed flags (what the Libra screen shows; '?' otherwise).** Live record R = [actor+0x38C] (R+0x08 =
  spec name). Libra item i is revealed when bit (i & 31) of BE u32 at R+0x3C+4*(i>>5) is set; elements are
  items 10+e, i.e. bits 10..16 of u32 R+0x3C. Niblet R+0x3C = 0x64003FFF: bits 10-13 set (physical, magic,
  fire, ice), 14 thunder and 16 wind clear: exactly the screen. Unchanged by opening Libra (before/screen/after).
- **Persistent copy:** u64 at [0x8312F648] + 0x1F4 + 8 * MonsterBook.u9SaveIndex (object vt 0x82181758 at
  0x6E1AFCC0 in all sessions), written at battle end (Gremlin idx 1 appears only in g_post*; Fanatic idx 98
  = the live copy in fan3). Niblet's idx 21 is still 0 mid-battle, so the HUD must read the live R.
- Unknown: the meaning of the other bits (items 20-33 look like status resistances, 51-60 drops / stagger
  conditions; not mapped), the exact English of $libra_12, and the overrides above (no sample).
  Confidence: values and element bits high (code path + Niblet screen match), element order high, handle
  path medium-high (target vs first enemy not separated: every sample targeted its first enemy slot).
