"""Temperature-over-time chart (SVG, no dependencies) from tools/thor_heat_sampler.sh CSVs.

Usage: python -I tools/heat_svg.py OUT.svg "LABEL=path/to.csv" ["LABEL=path/to.csv" ...]
Plots the CPU (solid) and GPU (dashed) temperatures while the game ran, minutes from the first running sample.
Older CSVs without cpu_c/gpu_c: the hottest sensor of any kind (solid) and the board sensor xo-therm (dashed).
"""
import csv
import sys

COLORS = ["#e8590c", "#1c7ed6", "#2b8a3e", "#ae3ec9"]
W, H = 760, 300
L, R, T, B = 52, 150, 16, 40  # margins


def load(path):
    rows = [r for r in csv.DictReader(open(path, newline="")) if r["running"] == "1"]
    secs = []
    for r in rows:
        h, m, s = (int(x) for x in r["time"].split(":"))
        secs.append(h * 3600 + m * 60 + s)
    t0 = secs[0]
    def col(k):
        return [((s - t0) / 60.0, float(r[k])) for s, r in zip(secs, rows) if r.get(k) and float(r[k]) > 0]
    if col("cpu_c") and col("gpu_c"):
        return col("cpu_c"), col("gpu_c"), ("CPU", "GPU")
    return col("temp_c"), col("board_c"), ("chip", "board")


def main():
    out, series = sys.argv[1], []
    for arg in sys.argv[2:]:
        label, path = arg.split("=", 1)
        series.append((label, load(path)))
    xmax = max(p[0][-1][0] for _, p in series)
    xmax = max(1, int(xmax + 0.999))
    ymin, ymax = 20, 100
    pw, ph = W - L - R, H - T - B
    sx = lambda x: L + x / xmax * pw
    sy = lambda y: T + (ymax - y) / (ymax - ymin) * ph
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" '
         'font-family="-apple-system,Segoe UI,Helvetica,Arial,sans-serif" font-size="12">',
         '<style>text{fill:#57606a}.g{stroke:#8c959f;stroke-opacity:.35}'
         '@media (prefers-color-scheme:dark){text{fill:#9198a1}}</style>']
    for y in range(ymin, ymax + 1, 10):
        o.append(f'<line class="g" x1="{L}" x2="{L + pw}" y1="{sy(y):.1f}" y2="{sy(y):.1f}"/>')
        o.append(f'<text x="{L - 8}" y="{sy(y) + 4:.1f}" text-anchor="end">{y}</text>')
    step = 1 if xmax <= 12 else 2
    for x in range(0, xmax + 1, step):
        o.append(f'<text x="{sx(x):.1f}" y="{T + ph + 18}" text-anchor="middle">{x}</text>')
    o.append(f'<text x="{L + pw / 2}" y="{H - 4}" text-anchor="middle">minutes of play</text>')
    names = series[0][1][2]
    o.append(f'<text x="14" y="{T + ph / 2}" text-anchor="middle" transform="rotate(-90 14 {T + ph / 2})">'
             f'°C (solid: {names[0]}, dashed: {names[1]})</text>')
    for i, (label, (pts, board, names)) in enumerate(series):
        c = COLORS[i % len(COLORS)]
        d = " ".join(f"{sx(x):.1f},{sy(y):.1f}" for x, y in pts)
        o.append(f'<polyline fill="none" stroke="{c}" stroke-width="2" stroke-linejoin="round" points="{d}"/>')
        if board:
            d = " ".join(f"{sx(x):.1f},{sy(y):.1f}" for x, y in board)
            o.append(f'<polyline fill="none" stroke="{c}" stroke-width="2" stroke-dasharray="5 4" points="{d}"/>')
        avg = sum(y for _, y in pts) / len(pts)
        ly = T + 14 + i * 52
        o.append(f'<line x1="{L + pw + 14}" x2="{L + pw + 34}" y1="{ly - 4}" y2="{ly - 4}" stroke="{c}" stroke-width="3"/>')
        o.append(f'<text x="{L + pw + 40}" y="{ly}">{label}</text>')
        o.append(f'<text x="{L + pw + 40}" y="{ly + 15}">{names[0]} avg {avg:.0f}, peak {max(y for _, y in pts):.0f}</text>')
        if board:
            o.append(f'<text x="{L + pw + 40}" y="{ly + 30}">{names[1]} avg {sum(y for _, y in board) / len(board):.0f}, '
                     f'peak {max(y for _, y in board):.0f}</text>')
    o.append("</svg>")
    open(out, "w", encoding="utf-8", newline="\n").write("\n".join(o) + "\n")


if __name__ == "__main__":
    main()
