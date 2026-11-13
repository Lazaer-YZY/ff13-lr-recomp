package com.lrrecomp.android;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The game's textures, decoded on the device. Port of tools/lr_gui_tex.py.
 *
 * GUI packs are WPD archives (.xgr) of txbh entries (a 'SEDBtxb' wrapper around a GTEX header);
 * the pixels are in the paired .imgb. GTEX (offsets from 'GTEX'): +6 format, +7 mip count,
 * +9 type, +10 u16 width, +12 u16 height, +16 u32 offset of the mip table, whose entries are
 * u32 offset + u32 size into the .imgb. Formats: 24 DXT1, 25 DXT3, 26 DXT5, 3/4 32-bit ARGB.
 * Xbox 360 data is tiled (Xenos 2D tiling, in 4x4 blocks for DXT) and stored as big-endian words.
 */
final class GuiTex {
    private GuiTex() {}

    static final class WpdEntry {
        final String name, ext;
        final byte[] data;
        final int off, size;

        WpdEntry(String name, String ext, byte[] data, int off, int size) {
            this.name = name;
            this.ext = ext;
            this.data = data;
            this.off = off;
            this.size = size;
        }

        byte[] body() { return java.util.Arrays.copyOfRange(data, off, off + size); }
    }

    static final class Texture {
        final String name;
        final int fmt, type, width, height;
        final byte[] data;

        Texture(String name, int fmt, int type, int width, int height, byte[] data) {
            this.name = name;
            this.fmt = fmt;
            this.type = type;
            this.width = width;
            this.height = height;
            this.data = data;
        }
    }

    static int be32(byte[] d, int o) {
        return (d[o] & 0xFF) << 24 | (d[o + 1] & 0xFF) << 16 | (d[o + 2] & 0xFF) << 8 | (d[o + 3] & 0xFF);
    }

    static int be16(byte[] d, int o) {
        return (d[o] & 0xFF) << 8 | (d[o + 1] & 0xFF);
    }

    private static String cstr(byte[] d, int o, int max) {
        int e = o;
        while (e < o + max && d[e] != 0) e++;
        return new String(d, o, e - o, StandardCharsets.ISO_8859_1);
    }

    static List<WpdEntry> wpdEntries(byte[] d) {
        List<WpdEntry> out = new ArrayList<>();
        if (d.length < 16 || d[0] != 'W' || d[1] != 'P' || d[2] != 'D' || d[3] != 0) return out;
        int n = be32(d, 4);
        for (int i = 0; i < n; i++) {
            int o = 16 + 32 * i;
            int off = be32(d, o + 16), size = be32(d, o + 20);
            int end = Math.min(d.length, off + size);
            out.add(new WpdEntry(cstr(d, o, 16), cstr(d, o + 24, 8), d, off, Math.max(0, end - off)));
        }
        return out;
    }

    private static int indexOf(byte[] d, int from, int to, byte[] pat) {
        outer:
        for (int i = from; i + pat.length <= to; i++) {
            for (int j = 0; j < pat.length; j++) if (d[i + j] != pat[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static final byte[] GTEX = { 'G', 'T', 'E', 'X' };

    static List<Texture> textures(byte[] xgr, byte[] imgb) {
        List<Texture> out = new ArrayList<>();
        for (WpdEntry e : wpdEntries(xgr)) {
            if (!e.ext.equals("txbh")) continue;
            byte[] d = e.data;
            int g = indexOf(d, e.off, e.off + e.size, GTEX);
            if (g < 0) continue;
            int fmt = d[g + 6] & 0xFF, typ = d[g + 9] & 0xFF;
            int w = be16(d, g + 10), h = be16(d, g + 12);
            int table = be32(d, g + 16);
            int off = be32(d, g + table), size = be32(d, g + table + 4);
            int s = Math.min(Math.max(off, 0), imgb.length), t = Math.min(Math.max(off + size, s), imgb.length);
            out.add(new Texture(e.name, fmt, typ, w, h, java.util.Arrays.copyOfRange(imgb, s, t)));
        }
        return out;
    }

    /** Xenos XGAddress2DTiledOffset of block (x, y), in blocks. */
    static int tiledOffset(int x, int y, int aw, int logBpp) {
        long macro = ((long) (x >> 5) + (long) (y >> 5) * (aw >> 5)) << (logBpp + 7);
        long micro = ((x & 7) + ((y & 6) << 2)) << logBpp;
        long off = macro + ((micro & ~15L) << 1) + (micro & 15) + ((long) (y & 8) << (3 + logBpp)) + ((y & 1) << 4);
        off = (((off & ~511L) << 3) + ((off & 448) << 2) + (off & 63) + ((y & 16) << 7)
            + (((((y & 8) >> 2) + (x >> 3)) & 3) << 6)) >> logBpp;
        return (int) off;
    }

    /** Byte offset of every block (row-major), -1 past the data (reads as zeros). */
    private static int[] blockOffsets(int dataLen, int bw, int bh, int bpb, boolean tiled) {
        int[] offs = new int[bw * bh];
        if (tiled) {
            int aw = (bw + 31) & ~31;
            int logBpp = (bpb >> 2) + ((bpb >> 1) >> (bpb >> 2));
            for (int y = 0; y < bh; y++)
                for (int x = 0; x < bw; x++) {
                    int o = tiledOffset(x, y, aw, logBpp) * bpb;
                    offs[y * bw + x] = o + bpb <= dataLen ? o : -1;
                }
        } else {
            int aligned = (bw + 31) & ~31;
            int pitch = dataLen >= aligned * bh * bpb ? aligned : bw;
            for (int y = 0; y < bh; y++)
                for (int x = 0; x < bw; x++) {
                    int o = (y * pitch + x) * bpb;
                    offs[y * bw + x] = o + bpb <= dataLen ? o : -1;
                }
        }
        return offs;
    }

    private static int r5(int c) { return ((c >> 11) & 31) * 255 / 31; }
    private static int g6(int c) { return ((c >> 5) & 63) * 255 / 63; }
    private static int b5(int c) { return (c & 31) * 255 / 31; }

    /** RGBA (w*h*4) or null for formats not decoded. */
    static byte[] decode(int fmt, int w, int h, byte[] data, boolean tiled) {
        if (fmt == 24 || fmt == 25 || fmt == 26) {
            int bpb = fmt == 24 ? 8 : 16;
            int bw = (w + 3) / 4, bh = (h + 3) / 4;
            int[] offs = blockOffsets(data.length, bw, bh, bpb, tiled);
            byte[] out = new byte[w * h * 4];
            byte[] blk = new byte[16];
            int[][] pal = new int[4][4];
            int[] alpha = new int[16];
            for (int by = 0; by < bh; by++)
                for (int bx = 0; bx < bw; bx++) {
                    int o = offs[by * bw + bx];
                    for (int i = 0; i < bpb; i += 2) {  // 8-in-16 swap
                        blk[i] = o < 0 ? 0 : data[o + i + 1];
                        blk[i + 1] = o < 0 ? 0 : data[o + i];
                    }
                    int co = fmt == 24 ? 0 : 8;
                    int c0 = (blk[co] & 0xFF) | (blk[co + 1] & 0xFF) << 8;
                    int c1 = (blk[co + 2] & 0xFF) | (blk[co + 3] & 0xFF) << 8;
                    long bits = (blk[co + 4] & 0xFFL) | (blk[co + 5] & 0xFFL) << 8 | (blk[co + 6] & 0xFFL) << 16 | (blk[co + 7] & 0xFFL) << 24;
                    boolean four = c0 > c1 || fmt != 24;
                    int[] p0 = { r5(c0), g6(c0), b5(c0) }, p1 = { r5(c1), g6(c1), b5(c1) };
                    for (int k = 0; k < 3; k++) {
                        pal[0][k] = p0[k];
                        pal[1][k] = p1[k];
                        pal[2][k] = four ? (2 * p0[k] + p1[k]) / 3 : (p0[k] + p1[k]) / 2;
                        pal[3][k] = four ? (p0[k] + 2 * p1[k]) / 3 : 0;
                    }
                    pal[0][3] = pal[1][3] = pal[2][3] = 255;
                    pal[3][3] = fmt == 24 && !four ? 0 : 255;
                    if (fmt == 26) {
                        int a0 = blk[0] & 0xFF, a1 = blk[1] & 0xFF;
                        long ab = 0;
                        for (int i = 0; i < 6; i++) ab |= (blk[2 + i] & 0xFFL) << (8 * i);
                        int[] ap = new int[8];
                        ap[0] = a0;
                        ap[1] = a1;
                        for (int i = 0; i < 6; i++) {
                            int six = i < 4 ? ((4 - i) * a0 + (i + 1) * a1) / 5 : (i == 4 ? 0 : 255);
                            ap[2 + i] = a0 > a1 ? ((6 - i) * a0 + (1 + i) * a1) / 7 : six;
                        }
                        for (int i = 0; i < 16; i++) alpha[i] = ap[(int) ((ab >>> (3 * i)) & 7)];
                    } else if (fmt == 25) {
                        for (int k = 0; k < 8; k++) {
                            alpha[2 * k] = (blk[k] & 15) * 17;
                            alpha[2 * k + 1] = ((blk[k] >> 4) & 15) * 17;
                        }
                    }
                    for (int i = 0; i < 16; i++) {
                        int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
                        if (x >= w || y >= h) continue;
                        int[] p = pal[(int) ((bits >>> (2 * i)) & 3)];
                        int q = (y * w + x) * 4;
                        out[q] = (byte) p[0];
                        out[q + 1] = (byte) p[1];
                        out[q + 2] = (byte) p[2];
                        out[q + 3] = (byte) (fmt == 24 ? p[3] : alpha[i]);
                    }
                }
            return out;
        }
        if (fmt == 3 || fmt == 4) {
            int[] offs = blockOffsets(data.length, w, h, 4, tiled);
            byte[] out = new byte[w * h * 4];
            for (int i = 0; i < w * h; i++) {
                int o = offs[i];
                if (o < 0) continue;
                out[i * 4] = data[o + 1];
                out[i * 4 + 1] = data[o + 2];
                out[i * 4 + 2] = data[o + 3];
                out[i * 4 + 3] = data[o];
            }
            return out;
        }
        return null;
    }
}
