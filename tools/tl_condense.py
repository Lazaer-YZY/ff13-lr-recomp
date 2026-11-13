import re, sys
path, marker = sys.argv[1], sys.argv[2]
lines = open(path, encoding='utf-8', errors='replace').read().splitlines()
start = next(i for i, l in enumerate(lines) if 'LR timeline (' + marker in l)
hdr = lines[start]; print(hdr[hdr.index('threads:'):][:600])
ev = []
for l in lines[start + 1:]:
    if 'LR timeline (' in l or 'LR timeline:' not in l: break
    ev += [p.strip() for p in l.split(' | ')[1:]]
by = {}
for e in ev:
    m = re.match(r'([\d.]+) T(\d+) (.*)', e)
    if not m: continue
    t, th, rest = float(m.group(1)), int(m.group(2)), m.group(3)
    mw = re.match(r'wait ([\d.]+)ms (\w+)<(\w+) o(\w+) t(\w+) r(\w+)', rest)
    if mw:
        d = float(mw.group(1))
        if d < 1.0: continue
        rest = f"W{d:.1f} {mw.group(2)[-6:]}<{mw.group(3)[-6:]} o{mw.group(4)[-4:]} t{mw.group(5)} r{mw.group(6)} ->{t+d:.1f}"
    if rest.startswith('setK 4000810C') or rest.startswith('setK 4001B520') or rest.startswith('setK 400124CC'): continue
    by.setdefault(th, []).append((t, rest))
for th in sorted(by):
    print(f"T{th}: " + " ; ".join(f"{t:.1f} {r}" for t, r in by[th]))
