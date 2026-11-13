"""Post-codegen patches for generated/default (run after every `rexglue codegen`).

Adapted from leftisthominid/lr-ffxiii-port (GPL-3.0), builder/patches.ps1:
  1. lr_pch.h: replace ReXGlue's process-wide "interrupts off" mutex macros
     (REX_CHECK/ENTER/LEAVE_GLOBAL_LOCK) with src/lr_guest_irq.inc (per-thread state,
     LR_GUEST_IRQ_LOCK v7). lr_hooks.cpp relies on the same per-thread state.
  2. lr_recomp.*.cpp: a memory fence after each guest lwsync / eieio / sync (codegen only
     leaves a comment). Required on ARM; on x86 they compile to compiler barriers.

Idempotent. Usage: python -I tools/patch_generated.py [C:/lr]
Never run while a build is compiling generated/.
"""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else r"C:/lr")
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


print(patch_pch(gen / "lr_pch.h"))
print(add_barriers())
print(patch_effect_spins())
