"""The cheat bits of the Java dialog (Cheats.java) and the game side (src/lr_cheats.h) agree.

python -I tests/host/check_cheat_bits.py [REPO]   (exit 0 = they agree)
"""
import re
import sys
from pathlib import Path

repo = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parents[2])
header = (repo / "src/lr_cheats.h").read_text(encoding="utf-8")
java = (repo / "android/app/src/main/java/com/lrrecomp/android/Cheats.java").read_text(encoding="utf-8")

native = {name: int(value) for name, value in re.findall(r"constexpr int (kLrCheat\w+) = (\d+);", header)}
java_bits = [int(b) for b in re.findall(r"new Cheat\((\d+),", java)]
native_bits = sorted(v for k, v in native.items() if k != "kLrCheatMask")
mask_expr = re.search(r"constexpr int kLrCheatMask = ([^;]+);", header)

problems = []
if not native_bits:
    problems.append("no kLrCheat* bits found in src/lr_cheats.h")
if sorted(java_bits) != native_bits:
    problems.append(f"Cheats.java bits {sorted(java_bits)} != lr_cheats.h bits {native_bits}")
if mask_expr:
    names = [n.strip() for n in mask_expr.group(1).split("|")]
    if sorted(native.get(n, -1) for n in names) != native_bits:
        problems.append(f"kLrCheatMask ({mask_expr.group(1)}) does not cover every kLrCheat* bit")
else:
    problems.append("kLrCheatMask not found")

for p in problems:
    print("FAIL", p)
print("check_cheat_bits:", "OK" if not problems else f"{len(problems)} problem(s)", native_bits)
sys.exit(1 if problems else 0)
