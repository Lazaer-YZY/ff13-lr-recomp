"""Frame statistics of a player-agent session from the game log, leaving out the 10-s windows
that contain a screenshot (adb screencap stalls the game ~1 s; tools/thor_pad.sh shot appends
its device time to out/thor/live/shots.log).

Usage: python -I tools/walk_stats.py LOG SHOTS_LOG "YYYY-MM-DD HH:MM:SS" ["YYYY-MM-DD HH:MM:SS"]
Prints one row per clean window (main thread frames / over 45 ms / ms per frame split, GPU ms
per frame and its biggest parts) and totals for the clean windows.
"""
import re
import sys
from datetime import datetime, timedelta

FMT = "%Y-%m-%d %H:%M:%S"
log_path, shots_path, t_from = sys.argv[1], sys.argv[2], datetime.strptime(sys.argv[3], FMT)
t_to = datetime.strptime(sys.argv[4], FMT) if len(sys.argv) > 4 else datetime.max

shots = []
for line in open(shots_path, encoding="utf-8", errors="replace"):
    line = line.strip()
    if line:
        shots.append(datetime.strptime(line, FMT))

main_re = re.compile(
    r"^\[(\S+ \S+?)\.\d+\].*LR main thread \(10 s\): (\d+) frames, (\d+) over 45 ms; per frame "
    r"([\d.]+) ms = game code ([\d.]+) \+ sleeping ([\d.]+) \+ waiting ([\d.]+)")
gpu_re = re.compile(
    r"^\[(\S+ \S+?)\.\d+\].*GPU timing \([\d.]+ s, (\d+) frames, per frame\): total ([\d.]+) ms "
    r"\((\d+)% of wall time\) = (.*?); presenter")
slow_re = re.compile(r"^\[(\S+ \S+?)\.\d+\].*(LR slow frame|GPU slow frame)")

windows = {}  # end time (s resolution) -> dict
slow = []
for line in open(log_path, encoding="utf-8", errors="replace"):
    m = main_re.match(line)
    if m:
        t = datetime.strptime(m.group(1), FMT)
        if t_from <= t <= t_to:
            w = windows.setdefault(t, {})
            w.update(frames=int(m.group(2)), slow=int(m.group(3)), ms=float(m.group(4)),
                      code=float(m.group(5)), sleep=float(m.group(6)), wait=float(m.group(7)))
        continue
    m = gpu_re.match(line)
    if m:
        t = datetime.strptime(m.group(1), FMT)
        if t_from <= t <= t_to:
            # Same 10-s tick as the main-thread line (within a second).
            key = min(windows, key=lambda k: abs((k - t).total_seconds()), default=t)
            if abs((key - t).total_seconds()) > 2:
                key = t
            parts = sorted(((float(v), k) for k, v in
                            (p.strip().rsplit(" ", 1) for p in m.group(5).split("+"))),
                           reverse=True)
            windows.setdefault(key, {}).update(gpu=float(m.group(3)), gpu_pct=int(m.group(4)),
                                               gpu_parts=parts[:3])
        continue
    m = slow_re.match(line)
    if m:
        t = datetime.strptime(m.group(1), FMT)
        if t_from <= t <= t_to:
            slow.append((t, m.group(2)))

def has_shot(end):
    return any(end - timedelta(seconds=11) <= s <= end + timedelta(seconds=1) for s in shots)

tot = dict(frames=0, slow=0, n=0, ms=0.0, code=0.0, wait=0.0, gpu=0.0, gpu_n=0)
dirty = 0
print("window end  frames >45ms  ms/frame (code wait)  GPU ms/frame (% wall)  top GPU parts")
for end in sorted(windows):
    w = windows[end]
    if "frames" not in w:
        continue
    if has_shot(end):
        dirty += 1
        continue
    parts = " ".join(f"{k} {v:.1f}" for v, k in w.get("gpu_parts", []))
    print(f"{end:%H:%M:%S}  {w['frames']:6d} {w['slow']:5d}  {w['ms']:6.1f} ({w['code']:.1f} {w['wait']:.1f})"
          f"   {w.get('gpu', 0):6.2f} ({w.get('gpu_pct', 0)}%)   {parts}")
    tot["frames"] += w["frames"]; tot["slow"] += w["slow"]; tot["n"] += 1
    tot["ms"] += w["ms"]; tot["code"] += w["code"]; tot["wait"] += w["wait"]
    if "gpu" in w:
        tot["gpu"] += w["gpu"]; tot["gpu_n"] += 1
n = max(tot["n"], 1)
print(f"\nclean windows {tot['n']} (left out with a screenshot: {dirty}); {tot['frames']} frames, "
      f"{tot['slow']} over 45 ms ({100 * tot['slow'] / max(tot['frames'], 1):.1f}%); "
      f"{tot['ms'] / n:.1f} ms/frame = code {tot['code'] / n:.1f} + wait {tot['wait'] / n:.1f}; "
      f"GPU {tot['gpu'] / max(tot['gpu_n'], 1):.2f} ms/frame")
clean_slow = [s for s in slow if not any(abs((s[0] - x).total_seconds()) <= 2 for x in shots)]
print(f"slow-frame log lines away from screenshots: "
      f"{sum(1 for s in clean_slow if s[1] == 'LR slow frame')} LR, "
      f"{sum(1 for s in clean_slow if s[1] == 'GPU slow frame')} GPU")
