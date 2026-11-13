"""Minimal XDVDFS (Xbox 360 game partition) reader.

Usage:
  python -I xdvdfs.py <iso> list                    # print tree
  python -I xdvdfs.py <iso> extract <out_dir> [path] # extract one file (by path) or all
"""
import os, struct, sys

SECTOR = 2048
MAGIC = b"MICROSOFT*XBOX*MEDIA"
BASES = [0x2080000, 0xFD90000, 0x18300000, 0]


def find_base(f):
    for b in BASES:
        f.seek(b + 32 * SECTOR)
        if f.read(20) == MAGIC:
            return b
    raise SystemExit("no XDVDFS volume descriptor found")


class Vol:
    def __init__(self, path):
        self.f = open(path, "rb")
        self.base = find_base(self.f)
        self.f.seek(self.base + 32 * SECTOR + 20)
        self.root_sector, self.root_size = struct.unpack("<II", self.f.read(8))

    def read(self, sector, size):
        self.f.seek(self.base + sector * SECTOR)
        return self.f.read(size)

    def walk_dir(self, sector, size, prefix=""):
        """Yield (path, sector, size, is_dir) for entries in a directory."""
        if size == 0:
            return
        data = self.read(sector, size)
        seen = set()

        def visit(off):
            if off in seen or off + 14 > len(data):
                return
            seen.add(off)
            l, r, ssec, ssize, attr, nlen = struct.unpack_from("<HHIIBB", data, off)
            if l == 0xFFFF and r == 0xFFFF:
                return
            name = data[off + 14: off + 14 + nlen].decode("ascii", "replace")
            is_dir = bool(attr & 0x10)
            if l:
                visit(l * 4)
            yield_list.append((prefix + name, ssec, ssize, is_dir))
            if r:
                visit(r * 4)

        yield_list = []
        visit(0)
        for item in yield_list:
            yield item
            if item[3]:
                yield from self.walk_dir(item[1], item[2], item[0] + "/")

    def entries(self):
        return self.walk_dir(self.root_sector, self.root_size)

    def extract(self, entry, out_root):
        path, ssec, ssize, is_dir = entry
        dst = os.path.join(out_root, *path.split("/"))
        if is_dir:
            os.makedirs(dst, exist_ok=True)
            return
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        self.f.seek(self.base + ssec * SECTOR)
        with open(dst, "wb") as o:
            left = ssize
            while left:
                chunk = self.f.read(min(left, 8 << 20))
                if not chunk:
                    raise IOError("short read on " + path)
                o.write(chunk)
                left -= len(chunk)


def main():
    iso, cmd = sys.argv[1], sys.argv[2]
    v = Vol(iso)
    print(f"# base=0x{v.base:X} root_sector={v.root_sector} root_size={v.root_size}", file=sys.stderr)
    if cmd == "list":
        n = 0
        total = 0
        for p, s, z, d in v.entries():
            print(("D " if d else "F ") + f"{z:>12} {p}")
            if not d:
                n += 1
                total += z
        print(f"# {n} files, {total} bytes", file=sys.stderr)
    elif cmd == "extract":
        out = sys.argv[3]
        want = sys.argv[4].lower() if len(sys.argv) > 4 else None
        n = 0
        for e in v.entries():
            if want is None or e[0].lower() == want:
                v.extract(e, out)
                n += 1
        print(f"# extracted {n} entries to {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
