package com.lrrecomp.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The game's packed archives (weiss_data/sys/filelist*.bin + white_img*.bin), read on the device.
 * Port of tools/lr_shader_scan.py (decrypt_filelist, parse_filelist, read_entry) and
 * tools/lr_gui_tex.py archive_index / tools/lr_navimap.py zone_index.
 *
 * filelist: optional 32-byte crypt header (u32 LE tag 0x1DE03478 at 0x14, BE body size at 0x10),
 * 8-byte XOR-table cipher; then u32 chunk table offset, chunk data offset, entry count; 8-byte
 * entries; 12-byte chunk records (uncompressed, compressed, offset) of zlib text
 * "sector:usize:csize:path" (hex). Entry data: at sector * 2048, csize bytes, zlib unless csize == usize.
 */
final class LrArchive {
    static final class Entry {
        final File image;
        final long sector;
        final int usize, csize;

        Entry(File image, long sector, int usize, int csize) {
            this.image = image;
            this.sector = sector;
            this.usize = usize;
            this.csize = csize;
        }
    }

    private static final String[][] LISTS = {
        { "sys/filelistu.x360.bin", "sys/white_imgu.x360.bin" },
        { "sys/filelist2u.x360.bin", "sys/white_img2u.x360.bin" },
        { "sys/filelist_scru.x360.bin", "sys/white_scru.x360.bin" } };

    final Map<String, Entry> index = new HashMap<>();

    /** The system archives (first list wins, as archive_index). */
    static LrArchive system(File gameRoot) throws IOException {
        File wd = new File(gameRoot, "weiss_data");
        LrArchive a = new LrArchive();
        for (String[] l : LISTS) {
            File img = new File(wd, l[1]);
            for (Object[] e : parse(readFile(new File(wd, l[0])))) {
                String p = (String) e[3];
                if (!a.index.containsKey(p)) a.index.put(p, new Entry(img, (Long) e[0], (Integer) e[1], (Integer) e[2]));
            }
        }
        return a;
    }

    /** Adds the z0100 zone archive (its file list is in the system archive; zone entries win). */
    void addZone0100(File gameRoot) throws IOException {
        byte[] lst = read("zone/filelist_z0100u.x360.bin");
        File img = new File(new File(gameRoot, "weiss_data"), "zone/white_z0100u_img.x360.bin");
        for (Object[] e : parse(lst)) index.put((String) e[3], new Entry(img, (Long) e[0], (Integer) e[1], (Integer) e[2]));
    }

    boolean has(String path) { return index.containsKey(path); }

    byte[] read(String path) throws IOException {
        Entry e = index.get(path);
        if (e == null) throw new IOException("not in the archives: " + path);
        byte[] b = new byte[e.csize];
        try (RandomAccessFile f = new RandomAccessFile(e.image, "r")) {
            f.seek(e.sector * 2048L);
            f.readFully(b);
        }
        return e.csize == e.usize ? b : inflate(b, 0, b.length, e.usize);
    }

    static byte[] readFile(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        }
    }

    static byte[] inflate(byte[] b, int off, int len, int expect) throws IOException {
        Inflater inf = new Inflater();
        try {
            inf.setInput(b, off, len);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(expect, 64));
            byte[] buf = new byte[Math.max(Math.min(expect, 1 << 20), 4096)];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException ex) {
            throw new IOException("zlib: " + ex.getMessage());
        } finally {
            inf.end();
        }
    }

    // ---- file lists ----------------------------------------------------------------------

    private static long u32le(byte[] d, int o) {
        return (d[o] & 0xFFL) | (d[o + 1] & 0xFFL) << 8 | (d[o + 2] & 0xFFL) << 16 | (d[o + 3] & 0xFFL) << 24;
    }

    private static long u32be(byte[] d, int o) {
        return (d[o] & 0xFFL) << 24 | (d[o + 1] & 0xFFL) << 16 | (d[o + 2] & 0xFFL) << 8 | (d[o + 3] & 0xFFL);
    }

    private static void put32le(byte[] d, int o, long v) {
        d[o] = (byte) v;
        d[o + 1] = (byte) (v >>> 8);
        d[o + 2] = (byte) (v >>> 16);
        d[o + 3] = (byte) (v >>> 24);
    }

    private static final long M32 = 0xFFFFFFFFL;

    static byte[] xorTable(long seed64) {
        byte[] seed = new byte[8];  // big-endian bytes of seed64
        for (int i = 0; i < 8; i++) seed[i] = (byte) (seed64 >>> (56 - 8 * i));
        long a = u32le(seed, 0), b = u32le(seed, 4);
        a = ((a << 8) | (a >>> 24)) & M32;
        b = ((b >>> 16) | (b << 16)) & M32;
        int[] blk = new int[8];
        for (int i = 0; i < 4; i++) {
            blk[i] = (int) ((b >>> (8 * i)) & 0xFF);
            blk[4 + i] = (int) ((a >>> (8 * i)) & 0xFF);
        }
        blk[0] = (blk[0] + 0x45) & 0xFF;
        for (int i = 1; i < 8; i++) {
            int t = blk[i] + 0xD4 + blk[i - 1];
            t ^= blk[i - 1] << 2;
            t ^= 0x45;
            blk[i] = t & 0xFF;
        }
        byte[] table = new byte[264];
        long prev = 0;
        for (int i = 0; i < 8; i++) {
            table[i] = (byte) blk[i];
            prev |= (long) blk[i] << (8 * i);
        }
        for (int i = 1; i < 0x21; i++) {
            long ha = prev & M32, hb = prev >>> 32;
            long x = prev * 5;
            x ^= hb << 32;
            long ta = ha ^ (x & M32);
            long tb = x >>> 32;
            x = ha | (x & 0xFFFFFFFF00000000L);
            long xa = (x & M32) ^ ta;
            long xb = tb ^ hb;
            put32le(table, i * 8, xa);
            put32le(table, i * 8 + 4, xb);
            prev = xa | (xb << 32);
        }
        return table;
    }

    static byte[] decrypt(byte[] data) throws IOException {
        int body = (int) u32be(data, 16) + 8;
        int seed32 = (data[9] & 0xFF) << 24 | (data[12] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | (data[0] & 0xFF);
        byte[] table = xorTable((long) seed32);  // sign-extended, as the game
        byte[] out = data.clone();
        if (32 + body > data.length) throw new IOException("file list truncated");
        int pos = 32;
        int[] d = new int[8];
        for (int n = 0; n < body / 8; n++) {
            long counter = n * 8L;
            int off = (int) (counter & 0xF8);
            for (int k = 0; k < 8; k++) {
                int v = k == 0 ? (((n ^ 69) & 255) ^ (data[pos] & 0xFF)) : ((data[pos + k - 1] & 0xFF) ^ (data[pos + k] & 0xFF));
                for (int j = 0; j < 8; j++) v = (((v + 120) & 0xFF) - (table[off + j] & 0xFF)) & 0xFF;
                d[k] = v;
            }
            long hi = d[4] | (long) d[5] << 8 | (long) d[6] << 16 | (long) d[7] << 24;
            long lo = d[0] | (long) d[1] << 8 | (long) d[2] << 16 | (long) d[3] << 24;
            long xlo = u32le(table, off), xhi = u32le(table, off + 4);
            long c = counter;
            long ab = c << 10, cd = c << 20, ef = c << 30;
            long cv = (cd & M32) | (ab & M32);
            long dv = (cd >>> 32) | (ab >>> 32);
            long ev = ((ef & M32) | c | cv) & M32;
            long fv = ((ef >>> 32) | dv) & M32;
            long carry1 = ev > (~0xA1652347L & M32) ? 1 : 0;
            long k1 = (ev + 0xA1652347L) & M32;
            long k2 = (fv + carry1) & M32;
            long lox = (lo - xlo) & M32;
            long hix = (hi - xhi - (lo < xlo ? 1 : 0)) & M32;
            lox ^= k1;
            hix ^= k2;
            lox ^= xlo;
            hix ^= xhi;
            put32le(out, pos, hix);
            put32le(out, pos + 4, lox);
            pos += 8;
        }
        int size = (int) u32be(out, 16);
        long chk = u32le(out, 32 + size + 4);
        long calc = 0;
        for (int i = 0; i < size / 4; i++) calc += out[32 + 4 * i] & 0xFF;
        if (chk != (calc & M32)) throw new IOException("file list checksum mismatch after decrypt");
        return out;
    }

    /** Entries as {Long sector, Integer usize, Integer csize, String path}. */
    static java.util.List<Object[]> parse(byte[] raw) throws IOException {
        int base = 0;
        if (raw.length >= 24 && u32le(raw, 20) == 0x1DE03478L) {
            raw = decrypt(raw);
            base = 32;
        }
        final boolean be = u32le(raw, base + 8) >= 0x100000L;
        final byte[] r = raw;
        java.util.function.IntToLongFunction rd = o -> be ? u32be(r, o) : u32le(r, o);
        int chunkTab = (int) rd.applyAsLong(base), chunkData = (int) rd.applyAsLong(base + 4);
        int count = (int) rd.applyAsLong(base + 8);
        int[] info = new int[count], chunk = new int[count];
        int nchunks = 0;
        for (int i = 0; i < count; i++) {
            int p = base + 12 + i * 8;
            int in = be ? ((r[p + 4] & 0xFF) << 8 | (r[p + 5] & 0xFF)) : ((r[p + 4] & 0xFF) | (r[p + 5] & 0xFF) << 8);
            chunk[i] = ((r[p + 6] & 0xFF) << 1) | (in >> 15);
            info[i] = in & 0x7FFF;
            nchunks = Math.max(nchunks, chunk[i] + 1);
        }
        byte[][] chunks = new byte[nchunks][];
        for (int c = 0; c < nchunks; c++) {
            int t = base + chunkTab + 12 * c;
            int un = (int) rd.applyAsLong(t), cm = (int) rd.applyAsLong(t + 4), cp = (int) rd.applyAsLong(t + 8);
            chunks[c] = inflate(r, base + chunkData + cp, cm, un);
            if (chunks[c].length != un) throw new IOException("file list chunk size");
        }
        java.util.List<Object[]> out = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] b = chunks[chunk[i]];
            int s = info[i], e = s;
            while (e < b.length && b[e] != 0) e++;
            String text = new String(b, s, e - s, StandardCharsets.US_ASCII);
            String[] f = text.split(":", 4);
            if (f.length < 4) continue;
            out.add(new Object[] { Long.parseLong(f[0], 16), (int) Long.parseLong(f[1], 16),
                (int) Long.parseLong(f[2], 16), f[3].trim() });
        }
        return out;
    }
}
