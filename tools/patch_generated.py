"""Post-codegen patches for generated/default (run after every `rexglue codegen`).

Adapted from leftisthominid/lr-ffxiii-port (GPL-3.0), builder/patches.ps1:
  1. lr_pch.h: replace ReXGlue's process-wide "interrupts off" mutex macros
     (REX_CHECK/ENTER/LEAVE_GLOBAL_LOCK) with src/lr_guest_irq.inc (per-thread state,
     LR_GUEST_IRQ_LOCK v7). lr_hooks.cpp relies on the same per-thread state.
  2. lr_recomp.*.cpp: a memory fence after each guest lwsync / eieio / sync (codegen only
     leaves a comment). Required on ARM; on x86 they compile to compiler barriers.
  3. sub_82C45608: the effect phase's election spins wait instead of spinning (src/lr_hooks.cpp).
  4. Wide screens: UI code that reads the frame's size from the engine's singleton itself gets the
     UI's 1280x720 canvas (lr_ui_canvas_size / lr_ui_canvas_ratio); see src/lr_render_size.cpp.

Idempotent. Usage: python -I tools/patch_generated.py [C:/lr] [--report-ui-canvas]
Never run while a build is compiling generated/.
"""
import pathlib
import re
import sys

paths = [a for a in sys.argv[1:] if not a.startswith("--")]
root = pathlib.Path(paths[0] if paths else r"C:/lr")
gen = root / "generated" / "default"
inc = root / "src" / "lr_guest_irq.inc"


def patch_pch(pch: pathlib.Path) -> str:
    text = pch.read_text(encoding="utf-8")
    if "LR_GUEST_IRQ_LOCK v7" in text:
        return "pch: already patched"
    if "LR_GUEST_IRQ_LOCK" in text or "LR_FAIR_GLOBAL_LOCK" in text:
        raise SystemExit(f"{pch} carries an older interrupt-lock patch; re-run codegen")
    start = text.find("#define REX_CHECK_GLOBAL_LOCK()")
    if start < 0:
        raise SystemExit(f"REX_CHECK_GLOBAL_LOCK not found in {pch}")
    m = re.compile(r"(?s)#define REX_LEAVE_GLOBAL_LOCK\(\).*?\} while \(0\)\r?\n").search(text, start)
    if not m:
        raise SystemExit(f"REX_LEAVE_GLOBAL_LOCK not found in {pch}")
    body = inc.read_text(encoding="utf-8")
    text = text[:start] + body + text[m.end():]
    pch.write_text(text, encoding="utf-8", newline="")
    check = pch.read_text(encoding="utf-8")
    if "#define enableFlushMode disableFlushMode" not in check:
        raise SystemExit("the interrupt-lock patch did not stick")
    return "pch: patched"


FENCE = {
    "lwsync": "std::atomic_thread_fence(std::memory_order_acq_rel);",
    "eieio": "std::atomic_thread_fence(std::memory_order_acq_rel);",
    "sync": "std::atomic_thread_fence(std::memory_order_seq_cst);",
}
BARRIER = re.compile(r"(?m)^(\t// (lwsync|eieio|sync) ?\n)(?!\tstd::atomic_thread_fence)")


def add_barriers() -> str:
    total = files = 0
    for f in sorted(gen.glob("*.cpp")):
        s = f.read_bytes().decode("utf-8")
        t, n = BARRIER.subn(lambda m: m.group(1) + "\t" + FENCE[m.group(2)] + "\n", s)
        if n:
            f.write_bytes(t.encode("utf-8"))
            total += n
            files += 1
    return f"barriers: {total} fences added in {files} files"


EFFECT_MARK = "// LR_EFFECT_SPINS v1"
EFFECT_DECL = (EFFECT_MARK + "\n"
               'extern "C" void lr_guest_wait_nonzero(uint8_t* base, uint32_t address);\n'
               'extern "C" void lr_guest_wake_word(uint8_t* base, uint32_t address);\n')
# sub_82C45608 (effect phase 0): the three inlined election spins and their winner stores,
# see lr_guest_wait_nonzero in src/lr_hooks.cpp.
EFFECT_LOOPS = {"loc_82C458AC": "r30", "loc_82C45AC0": "r28", "loc_82C45AF8": "r27"}


def patch_effect_spins() -> str:
    for f in sorted(gen.glob("lr_recomp.*.cpp")):
        s = f.read_bytes().decode("utf-8")
        start = s.find("DEFINE_REX_FUNC(sub_82C45608) {")
        if start < 0:
            continue
        if EFFECT_MARK in s:
            return f"effect spins: already patched ({f.name})"
        end = s.find("\nDEFINE_REX_FUNC(", start + 10)
        end = len(s) if end < 0 else end
        body = s[start:end]
        for label, reg in EFFECT_LOOPS.items():
            old = f"\n{label}:\n"
            if body.count(old) != 1:
                raise SystemExit(f"effect spins: {label} not found once in {f.name}")
            # Registers are "ctx.rN" or plain locals "rN" (non_volatile_as_local).
            m = re.search(rf"\tREX_STORE_U32\(((?:ctx\.)?{reg})\.u32 \+ 32, (?:ctx\.)?r18\.u32\);\n", body)
            if not m or len(re.findall(re.escape(m.group(0)), body)) != 1:
                raise SystemExit(f"effect spins: winner store via {reg} not found once in {f.name}")
            r = m.group(1)
            body = body.replace(old, f"{old}\tlr_guest_wait_nonzero(base, {r}.u32 + 32);\n")
            body = body.replace(m.group(0), f"{m.group(0)}\tlr_guest_wake_word(base, {r}.u32 + 32);\n")
        head = s.find("\n") + 1  # after the first #include line
        s = s[:head] + EFFECT_DECL + s[head:start] + body + s[end:]
        f.write_bytes(s.encode("utf-8"))
        return f"effect spins: patched {len(EFFECT_LOOPS)} loops in {f.name}"
    raise SystemExit("effect spins: sub_82C45608 not found")


CANVAS_MARK = "// LR_UI_CANVAS v1"
CANVAS_DECL = (CANVAS_MARK + "\n"
               'extern "C" uint32_t lr_ui_canvas_size(uint32_t value, uint32_t offset);\n'
               'extern "C" double lr_ui_canvas_ratio(double value);\n')
# Wide screens (src/lr_render_size.cpp): UI code that reads the frame's size from the singleton
# 0x83147A54 itself instead of through the getters. Their reads of +32 / +36 (width / height) and
# +40 (height / width) go through lr_ui_canvas_size / lr_ui_canvas_ratio, which hand the UI its
# 1280x720 canvas while the frame is wide and the value itself otherwise.
# Function -> (how the singleton's pointer is loaded, the number of reads expected):
#   "global": lwz rN,31316(rM), the pointer's global (markers' screen bounds and clamps, 3D item views);
#   "layer":  lwz rN,244(rM), the pointer a UI layer keeps: the layer classes with their own inlined copy of
#             sub_82323090, which copies the size to the layer's +256 / +260 (the minimap's layer among them).
CANVAS_READERS = {
    "sub_823FFF98": ("global", 2), "sub_82454AF0": ("global", 2), "sub_82454C60": ("global", 2),
    "sub_82456068": ("global", 2), "sub_824587E0": ("global", 1), "sub_824A17D8": ("global", 2),
    "sub_82552B80": ("global", 2), "sub_82553130": ("global", 2), "sub_825627A0": ("global", 2),
    "sub_82736DA8": ("global", 2), "sub_8276C820": ("global", 1),
    "sub_8275CCB8": ("layer", 2), "sub_8278DF98": ("layer", 2), "sub_827C3D38": ("layer", 2),
    "sub_827E4E90": ("layer", 2), "sub_82823ED0": ("layer", 2), "sub_82836450": ("layer", 4),
    "sub_82837578": ("layer", 4), "sub_8283D380": ("layer", 2), "sub_828418E0": ("layer", 6),
}
CANVAS_POINTER = {
    "global": re.compile(r"^\t// lwz (r\d+),31316\(r\d+\)$"),
    "layer": re.compile(r"^\t// lwz (r\d+),244\(r\d+\)$"),
}
CANVAS_SIZE = re.compile(r"^\t// lwz (r\d+),(32|36)\((r\d+)\)$")
CANVAS_RATIO = re.compile(r"^\t// lfs (f\d+),40\((r\d+)\)$")
CANVAS_WRITE = re.compile(r"^\t// (?:lwz|lwzx|mr|li|lis|addi|addis|ld|lbz|lhz|add|subf|or|ori) (r\d+),")


def canvas_patch_function(body: str, name: str, kind: str) -> tuple[str, int]:
    lines = body.split("\n")
    out, pointer, found, i = [], None, 0, 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        i += 1
        m = CANVAS_POINTER[kind].match(line)
        if m:
            pointer = m.group(1)
            continue
        if line.startswith("loc_"):
            pointer = None  # a branch target: the register is not known to hold the pointer here
            continue
        size, ratio = CANVAS_SIZE.match(line), CANVAS_RATIO.match(line)
        if pointer and ((size and size.group(3) == pointer) or (ratio and ratio.group(2) == pointer)):
            # The instruction's own statements, up to the next instruction's comment or a label.
            stmts = []
            while i < len(lines) and not (lines[i].startswith("\t// ") or lines[i].startswith("loc_")
                                          or lines[i] == "}"):
                stmts.append(lines[i])
                i += 1
            out.extend(stmts)
            # Registers are "ctx.rN" or plain locals "rN" (non_volatile_as_local).
            if size:
                pattern = rf"^\t((?:ctx\.)?{size.group(1)})\.u64 = REX_LOAD_U32\("
            else:
                pattern = rf"^\t((?:ctx\.)?{ratio.group(1)})\.f64 = double\(temp\.f32\);"
            dst = [m2.group(1) for m2 in (re.match(pattern, x) for x in stmts) if m2]
            if len(dst) != 1:
                raise SystemExit(f"ui canvas: the statement of '{line.strip()}' in {name} was not recognised")
            if size:
                out.append(f"\t{dst[0]}.u64 = lr_ui_canvas_size({dst[0]}.u32, {size.group(2)});")
                if size.group(1) == pointer:
                    pointer = None
            else:
                out.append(f"\t{dst[0]}.f64 = lr_ui_canvas_ratio({dst[0]}.f64);")
            found += 1
            continue
        w = CANVAS_WRITE.match(line)
        if w and w.group(1) == pointer:
            pointer = None
    return "\n".join(out), found


def patch_ui_canvas(report: bool = False) -> str:
    left = dict(CANVAS_READERS)
    reads = files = 0
    for f in sorted(gen.glob("lr_recomp.*.cpp")):
        s = f.read_bytes().decode("utf-8")
        names = [n for n in left if f"DEFINE_REX_FUNC({n}) {{" in s]
        if not names:
            continue
        changed = False
        for name in names:
            kind, expected = left.pop(name)
            start = s.find(f"DEFINE_REX_FUNC({name}) {{")
            end = s.find("\nDEFINE_REX_FUNC(", start + 10)
            end = len(s) if end < 0 else end
            if "lr_ui_canvas_" in s[start:end]:
                continue  # this function is patched already
            body, found = canvas_patch_function(s[start:end], name, kind)
            if report:
                print(f"  {name}: {found} reads ({kind}), expected {expected}")
            elif found != expected:
                raise SystemExit(f"ui canvas: {found} reads in {name}, expected {expected}")
            s = s[:start] + body + s[end:]
            reads += found
            changed = True
        if changed and not report:
            if CANVAS_MARK not in s:
                head = s.find("\n") + 1  # after the first #include line
                s = s[:head] + CANVAS_DECL + s[head:]
            f.write_bytes(s.encode("utf-8"))
            files += 1
    if left:
        raise SystemExit(f"ui canvas: not found: {sorted(left)}")
    return f"ui canvas: {reads} size reads patched in {files} files" if reads else "ui canvas: already patched"


print(patch_pch(gen / "lr_pch.h"))
print(add_barriers())
print(patch_effect_spins())
print(patch_ui_canvas("--report-ui-canvas" in sys.argv))
