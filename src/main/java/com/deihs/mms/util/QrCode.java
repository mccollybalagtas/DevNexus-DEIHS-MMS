package com.deihs.mms.util;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/**
 * Minimal QR Code generator (byte mode, ECC level M, versions 1-6).
 * Enough for DEIHS asset tags like "DEIHS-ASSET:FAC:001".
 * This is intentionally small and self-contained (no external deps).
 */
public final class QrCode {

    private QrCode() {}

    public static Image toFxImage(String text, int scale, int border) {
        boolean[][] modules = encode(text);
        int size = modules.length;
        int imgSize = (size + border * 2) * scale;
        WritableImage img = new WritableImage(imgSize, imgSize);
        PixelWriter pw = img.getPixelWriter();
        javafx.scene.paint.Color white = javafx.scene.paint.Color.WHITE;
        javafx.scene.paint.Color black = javafx.scene.paint.Color.web("#111827");
        for (int y = 0; y < imgSize; y++) {
            for (int x = 0; x < imgSize; x++) {
                pw.setColor(x, y, white);
            }
        }
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (modules[r][c]) {
                    int x0 = (c + border) * scale;
                    int y0 = (r + border) * scale;
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            pw.setColor(x0 + dx, y0 + dy, black);
                        }
                    }
                }
            }
        }
        return img;
    }

    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int version = pickVersion(data.length);
        int size = 17 + version * 4;
        int dataCodewords = DATA_CODEWORDS_M[version];
        int eccCodewords = ECC_CODEWORDS_M[version];

        byte[] dataBits = buildDataCodewords(data, version, dataCodewords);
        byte[] ecc = reedSolomon(dataBits, eccCodewords);
        byte[] full = new byte[dataBits.length + ecc.length];
        System.arraycopy(dataBits, 0, full, 0, dataBits.length);
        System.arraycopy(ecc, 0, full, dataBits.length, ecc.length);

        boolean[][] modules = new boolean[size][size];
        boolean[][] reserved = new boolean[size][size];
        placeFinders(modules, reserved, size);
        placeAlignment(modules, reserved, version, size);
        placeTiming(modules, reserved, size);
        modules[4 * version + 9][8] = true;
        reserved[4 * version + 9][8] = true;
        reserveFormat(reserved, size);
        if (version >= 2) reserveVersion(reserved, size);

        placeData(modules, reserved, full, size);
        int mask = chooseMask(modules, reserved, size);
        applyMask(modules, reserved, size, mask);
        placeFormat(modules, size, mask);
        if (version >= 2) placeVersion(modules, size, version);
        return modules;
    }

    private static int pickVersion(int len) {
        int[] caps = {0, 14, 26, 42, 62, 84, 106}; // byte mode, ECL M, v1-6 approx
        for (int v = 1; v <= 6; v++) if (len <= caps[v]) return v;
        throw new IllegalArgumentException("QR text too long for built-in encoder (max ~106 UTF-8 bytes).");
    }

    // data codewords for ECL M, versions 1-6
    private static final int[] DATA_CODEWORDS_M = {0, 16, 28, 44, 64, 86, 108};
    private static final int[] ECC_CODEWORDS_M  = {0, 10, 16, 26, 36, 46, 58};

    private static byte[] buildDataCodewords(byte[] data, int version, int capacity) {
        BitBuffer bb = new BitBuffer();
        bb.appendBits(0b0100, 4); // byte mode
        int countBits = version <= 9 ? 8 : 16;
        bb.appendBits(data.length, countBits);
        for (byte b : data) bb.appendBits(b & 0xFF, 8);
        int capBits = capacity * 8;
        bb.appendBits(0, Math.min(4, capBits - bb.size()));
        while (bb.size() % 8 != 0) bb.appendBits(0, 1);
        int[] pads = {0xEC, 0x11};
        int pi = 0;
        while (bb.size() < capBits) { bb.appendBits(pads[pi], 8); pi ^= 1; }
        byte[] out = new byte[capacity];
        for (int i = 0; i < capacity; i++) out[i] = (byte) bb.getByte(i);
        return out;
    }

    private static void placeFinders(boolean[][] m, boolean[][] r, int size) {
        placeFinder(m, r, 0, 0);
        placeFinder(m, r, 0, size - 7);
        placeFinder(m, r, size - 7, 0);
        // separators
        for (int i = 0; i < 8; i++) {
            setIf(m, r, 7, i, false, size); setIf(m, r, i, 7, false, size);
            setIf(m, r, 7, size - 1 - i, false, size); setIf(m, r, i, size - 8, false, size);
            setIf(m, r, size - 8, i, false, size); setIf(m, r, size - 1 - i, 7, false, size);
        }
    }
    private static void placeFinder(boolean[][] m, boolean[][] r, int row, int col) {
        for (int dr = -1; dr <= 7; dr++) for (int dc = -1; dc <= 7; dc++) {
            int rr = row + dr, cc = col + dc;
            if (rr < 0 || rr >= m.length || cc < 0 || cc >= m.length) continue;
            boolean on = (dr >= 0 && dr <= 6 && (dc == 0 || dc == 6)) || (dc >= 0 && dc <= 6 && (dr == 0 || dr == 6)) || (dr >= 2 && dr <= 4 && dc >= 2 && dc <= 4);
            m[rr][cc] = on; r[rr][cc] = true;
        }
    }
    private static void setIf(boolean[][] m, boolean[][] r, int row, int col, boolean val, int size) {
        if (row >= 0 && row < size && col >= 0 && col < size) { m[row][col] = val; r[row][col] = true; }
    }

    private static final int[][] ALIGN_POS = {
            {}, {}, {6,18}, {6,22}, {6,26}, {6,30}, {6,34}
    };
    private static void placeAlignment(boolean[][] m, boolean[][] r, int version, int size) {
        if (version < 2) return;
        int[] pos = ALIGN_POS[version];
        for (int a : pos) for (int b : pos) {
            if (r[a][b]) continue;
            for (int dr = -2; dr <= 2; dr++) for (int dc = -2; dc <= 2; dc++) {
                boolean on = Math.abs(dr)==2 || Math.abs(dc)==2 || (dr==0 && dc==0);
                m[a+dr][b+dc] = on; r[a+dr][b+dc] = true;
            }
        }
    }
    private static void placeTiming(boolean[][] m, boolean[][] r, int size) {
        for (int i = 8; i < size - 8; i++) {
            boolean on = i % 2 == 0;
            if (!r[6][i]) { m[6][i] = on; r[6][i] = true; }
            if (!r[i][6]) { m[i][6] = on; r[i][6] = true; }
        }
    }
    private static boolean[] moduleSet(boolean[][] m, boolean[][] r, int row, int col, boolean val) {
        m[row][col] = val; r[row][col] = true; return new boolean[0];
    }
    private static void reserveFormat(boolean[][] r, int size) {
        for (int i = 0; i < 9; i++) { if (i != 6) { r[8][i] = true; r[i][8] = true; } }
        for (int i = 8; i < size - 7; i++) { r[8][i] = true; r[i][8] = true; }
        r[8][8] = true;
    }
    private static void reserveVersion(boolean[][] r, int size) {
        for (int i = 0; i < 6; i++) for (int j = 0; j < 3; j++) { r[i][size-11+j] = true; r[size-11+j][i] = true; }
    }

    private static void placeData(boolean[][] m, boolean[][] r, byte[] data, int size) {
        int bitIndex = 0;
        int dir = -1;
        for (int col = size - 1; col >= 1; col -= 2) {
            if (col == 6) col--;
            for (int i = 0; i < size; i++) {
                int row = dir == -1 ? size - 1 - i : i;
                for (int dc = 0; dc < 2; dc++) {
                    int c = col - dc;
                    if (r[row][c]) continue;
                    boolean bit = false;
                    if (bitIndex < data.length * 8) {
                        bit = ((data[bitIndex / 8] >>> (7 - bitIndex % 8)) & 1) == 1;
                        bitIndex++;
                    }
                    m[row][c] = bit;
                }
            }
            dir = -dir;
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] r, int size, int mask) {
        for (int row = 0; row < size; row++) for (int col = 0; col < size; col++) {
            if (r[row][col]) continue;
            if (maskBit(mask, row, col)) m[row][col] = !m[row][col];
        }
    }
    private static boolean maskBit(int mask, int row, int col) {
        return switch (mask) {
            case 0 -> (row + col) % 2 == 0;
            case 1 -> row % 2 == 0;
            case 2 -> col % 3 == 0;
            case 3 -> (row + col) % 3 == 0;
            case 4 -> (row / 2 + col / 3) % 2 == 0;
            case 5 -> (row * col) % 2 + (row * col) % 3 == 0;
            case 6 -> ((row * col) % 2 + (row * col) % 3) % 2 == 0;
            case 7 -> ((row + col) % 2 + (row * col) % 3) % 2 == 0;
            default -> false;
        };
    }

    private static int chooseMask(boolean[][] m, boolean[][] r, int size) {
        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] copy = clone(m);
            applyMask(copy, r, size, mask);
            placeFormat(copy, size, mask);
            int pen = penalty(copy, size);
            if (pen < bestPenalty) { bestPenalty = pen; bestMask = mask; }
        }
        return bestMask;
    }

    private static boolean[][] clone(boolean[][] m) {
        boolean[][] c = new boolean[m.length][];
        for (int i = 0; i < m.length; i++) c[i] = m[i].clone();
        return c;
    }

    private static int penalty(boolean[][] m, int size) {
        int p = 0;
        // rule 1 rows
        for (int r = 0; r < size; r++) {
            int run = 1;
            for (int c = 1; c < size; c++) {
                if (m[r][c] == m[r][c-1]) { run++; if (run == 5) p += 3; else if (run > 5) p++; }
                else run = 1;
            }
        }
        // rule 1 cols
        for (int c = 0; c < size; c++) {
            int run = 1;
            for (int r = 1; r < size; r++) {
                if (m[r][c] == m[r-1][c]) { run++; if (run == 5) p += 3; else if (run > 5) p++; }
                else run = 1;
            }
        }
        // rule 2
        for (int r = 0; r < size - 1; r++) for (int c = 0; c < size - 1; c++) {
            boolean v = m[r][c];
            if (v == m[r][c+1] && v == m[r+1][c] && v == m[r+1][c+1]) p += 3;
        }
        // rule 3
        int[] p1 = {1,0,1,1,1,0,1,0,0,0,0};
        int[] p2 = {0,0,0,0,1,0,1,1,1,0,1};
        for (int r = 0; r < size; r++) for (int c = 0; c <= size - 11; c++) {
            if (match(m, r, c, 0, 1, p1, 11) || match(m, r, c, 0, 1, p2, 11)) p += 40;
            if (match(m, c, r, 1, 0, p1, 11) || match(m, c, r, 1, 0, p2, 11)) p += 40;
        }
        // rule 4
        int dark = 0;
        for (boolean[] row : m) for (boolean v : row) if (v) dark++;
        int total = size * size;
        int k = Math.abs(dark * 20 - total * 10) / total;
        p += k * 10;
        return p;
    }
    private static boolean match(boolean[][] m, int r, int c, int dr, int dc, int[] pat, int len) {
        for (int i = 0; i < len; i++) {
            int rr = r + dr * i, cc = c + dc * i;
            if (rr < 0 || rr >= m.length || cc < 0 || cc >= m.length) return false;
            if (m[rr][cc] != (pat[i] == 1)) return false;
        }
        return true;
    }

    private static final int[] FORMAT_BITS = {
            0x77C4,0x72F3,0x7DAA,0x789D,0x662F,0x6318,0x6C41,0x6976,
            0x5412,0x5125,0x5E7C,0x5B4B,0x45F9,0x40CE,0x4F97,0x4AA0,
            0x355F,0x3068,0x3F31,0x3A06,0x24B4,0x2183,0x2EDA,0x2BED,
            0x1689,0x13BE,0x1CE7,0x19D0,0x0762,0x0255,0x0D0C,0x083B
    };
    private static void placeFormat(boolean[][] m, int size, int mask) {
        int bits = FORMAT_BITS[(0 << 3) | mask]; // ECL M = 0
        for (int i = 0; i <= 5; i++) setFmt(m, 8, i, bits, i);
        setFmt(m, 8, 7, bits, 6); setFmt(m, 8, 8, bits, 7); setFmt(m, 7, 8, bits, 8);
        for (int i = 9; i < 15; i++) setFmt(m, 14 - i, 8, bits, i);
        for (int i = 0; i < 8; i++) setFmt(m, size - 1 - i, 8, bits, i);
        for (int i = 8; i < 15; i++) setFmt(m, 8, size - 15 + i, bits, i);
    }
    private static void setFmt(boolean[][] m, int r, int c, int bits, int i) {
        m[r][c] = ((bits >>> (14 - i)) & 1) == 1;
    }

    private static final int[] VERSION_BITS = {0,0,0x07C94,0x085BC,0x09A99,0x0A4D3,0x0BBF6};
    private static void placeVersion(boolean[][] m, int size, int version) {
        int bits = VERSION_BITS[version];
        for (int i = 0; i < 18; i++) {
            boolean bit = ((bits >>> i) & 1) == 1;
            int r = i / 3, c = size - 11 + i % 3;
            m[r][c] = bit; m[c][r] = bit;
        }
    }

    // Reed-Solomon over GF(2^8), primitive poly 0x11D
    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];
    static {
        int x = 1;
        for (int i = 0; i < 255; i++) { EXP[i] = x; LOG[x] = i; x <<= 1; if ((x & 0x100) != 0) x ^= 0x11D; }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
        LOG[0] = 0;
    }
    private static int gfMul(int a, int b) { return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]]; }
    private static byte[] reedSolomon(byte[] data, int eccLen) {
        int[] gen = {1};
        for (int i = 0; i < eccLen; i++) gen = polyMul(gen, new int[]{1, EXP[i]});
        int[] msg = new int[data.length + eccLen];
        for (int i = 0; i < data.length; i++) msg[i] = data[i] & 0xFF;
        for (int i = 0; i < data.length; i++) {
            int coef = msg[i];
            if (coef != 0) for (int j = 0; j < gen.length; j++) msg[i + j] ^= gfMul(gen[j], coef);
        }
        byte[] out = new byte[eccLen];
        for (int i = 0; i < eccLen; i++) out[i] = (byte) msg[data.length + i];
        return out;
    }
    private static int[] polyMul(int[] a, int[] b) {
        int[] r = new int[a.length + b.length - 1];
        for (int i = 0; i < a.length; i++) for (int j = 0; j < b.length; j++) r[i + j] ^= gfMul(a[i], b[j]);
        return r;
    }

    static final class BitBuffer {
        private final java.util.ArrayList<Boolean> bits = new java.util.ArrayList<>();
        void appendBits(int val, int len) { for (int i = len - 1; i >= 0; i--) bits.add(((val >>> i) & 1) == 1); }
        int size() { return bits.size(); }
        int getByte(int i) {
            int v = 0;
            for (int b = 0; b < 8; b++) { v = (v << 1) | (bits.get(i * 8 + b) ? 1 : 0); }
            return v;
        }
    }
}
