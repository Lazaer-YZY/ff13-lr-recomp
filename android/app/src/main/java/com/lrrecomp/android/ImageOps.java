package com.lrrecomp.android;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Pure-Java image helpers for the on-device companion assets (no Bitmap: runs on any thread, in
 * JVM tests, and in strips so large maps never sit in memory at full size):
 * a Lanczos-3 resampler with Pillow's coefficients (Image.resize(..., LANCZOS): horizontal then
 * vertical pass, 8-bit rounding between them), premultiplied RGBA helpers, and a streaming PNG writer.
 */
final class ImageOps {
    private ImageOps() {}

    /** Per output pixel: first source index, tap count, weights (k per pixel). */
    static final class Coeffs {
        final int[] start, len;
        final float[] w;
        final int k;

        Coeffs(int out, int k) {
            start = new int[out];
            len = new int[out];
            w = new float[out * k];
            this.k = k;
        }
    }

    private static double sinc(double x) {
        if (x == 0) return 1;
        x *= Math.PI;
        return Math.sin(x) / x;
    }

    private static double lanczos(double x) {
        return x > -3 && x < 3 ? sinc(x) * sinc(x / 3) : 0;
    }

    /** Pillow's precompute_coeffs for LANCZOS (support 3). */
    static Coeffs lanczos(int inSize, int outSize) {
        double scale = inSize / (double) outSize;
        double filterscale = Math.max(scale, 1.0);
        double support = 3 * filterscale;
        int k = (int) Math.ceil(support) * 2 + 1;
        Coeffs c = new Coeffs(outSize, k);
        double ss = 1.0 / filterscale;
        double[] tmp = new double[k];
        for (int xx = 0; xx < outSize; xx++) {
            double center = (xx + 0.5) * scale;
            int xmin = (int) (center - support + 0.5);
            if (xmin < 0) xmin = 0;
            int xmax = (int) (center + support + 0.5);
            if (xmax > inSize) xmax = inSize;
            xmax -= xmin;
            double ww = 0;
            for (int x = 0; x < xmax; x++) {
                tmp[x] = lanczos((x + xmin - center + 0.5) * ss);
                ww += tmp[x];
            }
            for (int x = 0; x < xmax; x++) c.w[xx * k + x] = (float) (ww != 0 ? tmp[x] / ww : tmp[x]);
            c.start[xx] = xmin;
            c.len[xx] = xmax;
        }
        return c;
    }

    private static int clip8(float v) {
        int i = (int) Math.floor(v + 0.5f);
        return i < 0 ? 0 : i > 255 ? 255 : i;
    }

    /**
     * Resamples the output window [ox0,ox1) x [oy0,oy1) (in full output coordinates) of an 8-bit
     * image. src holds the source window [sx0, sx0+sw) x [sy0, sy0+sh) of the full source, srcCh
     * interleaved channels of which nCh starting at chOff are resampled; the window must cover the
     * taps of the requested outputs. Returns (oy1-oy0) rows of (ox1-ox0) * nCh bytes.
     */
    static byte[] resample(byte[] src, int sx0, int sy0, int sw, int sh, int srcCh, int chOff, int nCh,
                           Coeffs cx, Coeffs cy, int ox0, int ox1, int oy0, int oy1) {
        int ow = ox1 - ox0, oh = oy1 - oy0;
        int ymin = Integer.MAX_VALUE, ymax = 0;
        for (int y = oy0; y < oy1; y++) {
            ymin = Math.min(ymin, cy.start[y]);
            ymax = Math.max(ymax, cy.start[y] + cy.len[y]);
        }
        if (ymin < sy0 || ymax > sy0 + sh) throw new IllegalArgumentException("source window too small (rows)");
        int rows = ymax - ymin;
        byte[] tmp = new byte[rows * ow * nCh];
        float[] acc = new float[nCh];
        for (int y = 0; y < rows; y++) {
            int srow = (ymin + y - sy0) * sw;
            int trow = y * ow * nCh;
            for (int x = 0; x < ow; x++) {
                int ox = ox0 + x;
                int s = cx.start[ox] - sx0, n = cx.len[ox], wb = ox * cx.k;
                if (s < 0 || s + n > sw) throw new IllegalArgumentException("source window too small (columns)");
                for (int c = 0; c < nCh; c++) acc[c] = 0;
                int p = (srow + s) * srcCh + chOff;
                for (int t = 0; t < n; t++, p += srcCh) {
                    float wt = cx.w[wb + t];
                    for (int c = 0; c < nCh; c++) acc[c] += (src[p + c] & 0xFF) * wt;
                }
                for (int c = 0; c < nCh; c++) tmp[trow + x * nCh + c] = (byte) clip8(acc[c]);
            }
        }
        byte[] out = new byte[oh * ow * nCh];
        int rowLen = ow * nCh;
        float[] line = new float[rowLen];
        for (int y = 0; y < oh; y++) {
            int oy = oy0 + y;
            int s = cy.start[oy] - ymin, n = cy.len[oy], wb = oy * cy.k;
            java.util.Arrays.fill(line, 0);
            for (int t = 0; t < n; t++) {
                float wt = cy.w[wb + t];
                int p = (s + t) * rowLen;
                for (int i = 0; i < rowLen; i++) line[i] += (tmp[p + i] & 0xFF) * wt;
            }
            int q = y * rowLen;
            for (int i = 0; i < rowLen; i++) out[q + i] = (byte) clip8(line[i]);
        }
        return out;
    }

    /** RGBA -> premultiplied (Pillow RGBA->RGBa), in place. */
    static void premultiply(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            int a = rgba[i + 3] & 0xFF;
            if (a == 255) continue;
            for (int c = 0; c < 3; c++) {
                int t = (rgba[i + c] & 0xFF) * a + 128;
                rgba[i + c] = (byte) (((t >> 8) + t) >> 8);
            }
        }
    }

    /** Premultiplied -> RGBA (Pillow RGBa->RGBA), in place. */
    static void unpremultiply(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            int a = rgba[i + 3] & 0xFF;
            if (a == 255 || a == 0) continue;
            for (int c = 0; c < 3; c++) rgba[i + c] = (byte) Math.min(255, 255 * (rgba[i + c] & 0xFF) / a);
        }
    }

    /**
     * Whole RGBA image scaled by Lanczos-3 with premultiplied alpha, then an unsharp mask (Gaussian
     * sigma, amount 1.3 = 130 %, threshold 2, as Pillow's UnsharpMask; sigma 0 = none), written as
     * PNG in strips.
     */
    static void scaleRgbaToPng(byte[] rgba, int w, int h, int dw, int dh, File out, float sigma, float amount) throws IOException {
        byte[] pre = rgba.clone();
        premultiply(pre);
        Coeffs cx = lanczos(w, dw), cy = lanczos(h, dh);
        int r = sigma > 0 ? (int) Math.ceil(3 * sigma) : 0;
        float[] kern = new float[2 * r + 1];
        float ks = 0;
        for (int i = -r; i <= r; i++) ks += kern[i + r] = (float) Math.exp(-0.5 * i * i / Math.max(sigma * sigma, 1e-6));
        for (int i = 0; i < kern.length; i++) kern[i] /= ks;
        final int S = 128, row = dw * 4;
        try (Png png = new Png(out, dw, dh)) {
            for (int y0 = 0; y0 < dh; y0 += S) {
                int y1 = Math.min(dh, y0 + S);
                int e0 = Math.max(0, y0 - r), e1 = Math.min(dh, y1 + r);
                byte[] ext = resample(pre, 0, 0, w, h, 4, 0, 4, cx, cy, 0, dw, e0, e1);
                byte[] strip;
                if (r == 0) {
                    strip = ext;
                } else {
                    int n = e1 - e0;
                    float[] hb = new float[n * row];  // horizontal blur (edge pixels repeated)
                    for (int y = 0; y < n; y++)
                        for (int x = 0; x < dw; x++)
                            for (int c = 0; c < 4; c++) {
                                float acc = 0;
                                for (int t = -r; t <= r; t++) {
                                    int xx = Math.min(dw - 1, Math.max(0, x + t));
                                    acc += (ext[y * row + xx * 4 + c] & 0xFF) * kern[t + r];
                                }
                                hb[y * row + x * 4 + c] = acc;
                            }
                    strip = new byte[(y1 - y0) * row];
                    for (int y = y0; y < y1; y++)
                        for (int i = 0; i < row; i++) {
                            float acc = 0;
                            for (int t = -r; t <= r; t++) {
                                int yy = Math.min(dh - 1, Math.max(0, y + t)) - e0;
                                acc += hb[yy * row + i] * kern[t + r];
                            }
                            int v = ext[(y - e0) * row + i] & 0xFF;
                            float d = v - acc;
                            int o = Math.abs(d) < 2 ? v : clip8(v + d * amount);
                            strip[(y - y0) * row + i] = (byte) o;
                        }
                    for (int i = 0; i < strip.length; i += 4) {  // stay a valid premultiplied colour
                        int a = strip[i + 3] & 0xFF;
                        for (int c = 0; c < 3; c++) if ((strip[i + c] & 0xFF) > a) strip[i + c] = (byte) a;
                    }
                }
                unpremultiply(strip);
                for (int y = 0; y < y1 - y0; y++) png.row(strip, y * row);
            }
        }
    }

    static void writePng(File out, byte[] rgba, int w, int h) throws IOException {
        try (Png png = new Png(out, w, h)) {
            for (int y = 0; y < h; y++) png.row(rgba, y * w * 4);
        }
    }

    /** Streaming RGBA8 PNG writer (filter Sub on every row, one IDAT). */
    static final class Png implements Closeable {
        private final File file;
        private final int w, h;
        private final ByteArrayOutputStream idat = new ByteArrayOutputStream();
        private final Deflater deflater = new Deflater(6);
        private final DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater, 1 << 16);
        private final byte[] line;
        private int rows;

        Png(File file, int w, int h) {
            this.file = file;
            this.w = w;
            this.h = h;
            line = new byte[1 + w * 4];
        }

        void row(byte[] rgba, int off) throws IOException {
            line[0] = 1;  // Sub
            for (int i = 0; i < 4 && i < w * 4; i++) line[1 + i] = rgba[off + i];
            for (int i = 4; i < w * 4; i++) line[1 + i] = (byte) (rgba[off + i] - rgba[off + i - 4]);
            z.write(line);
            rows++;
        }

        @Override
        public void close() throws IOException {
            if (rows != h) {
                deflater.end();
                throw new IOException("png: " + rows + " of " + h + " rows");
            }
            z.finish();
            deflater.end();
            File tmp = new File(file.getPath() + ".part");
            try (DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 16))) {
                o.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
                ByteArrayOutputStream hdr = new ByteArrayOutputStream();
                DataOutputStream d = new DataOutputStream(hdr);
                d.writeInt(w);
                d.writeInt(h);
                d.write(new byte[] { 8, 6, 0, 0, 0 });
                chunk(o, "IHDR", hdr.toByteArray());
                chunk(o, "IDAT", idat.toByteArray());
                chunk(o, "IEND", new byte[0]);
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) throw new IOException("png: cannot rename " + tmp);
            }
        }

        private static void chunk(DataOutputStream o, String type, byte[] data) throws IOException {
            byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(t);
            crc.update(data);
            o.writeInt(data.length);
            o.write(t);
            o.write(data);
            o.writeInt((int) crc.getValue());
        }
    }
}
