package com.sva.vehicle;

/**
 * Hi-res cockpit (2K / 4K wave) as pure math - no GL, no AWT, no Minecraft client classes, so the dedicated server's
 * self-test checks it; the client (client/HiResSVA, HiFontSVA, FaceArtSVA, HiResPaintSVA) only paints and uploads what
 * this lays out.
 * - Quality: config vehicles.cockpitQuality AUTO / 1K / 2K / 4K; AUTO from the window's height (<= 1200 -> 1K, <= 1800 ->
 *   2K, else 4K). The level picks the glyph size of the text atlas (32 / 64 / 128 px) and the baked dial faces (256 / 512
 *   / 1024 px).
 * - The glyph cell: the vanilla FontRenderer's 8-unit line box (cap top at -4, baseline at +3, y down) sits inside a
 *   10-unit cell (-5..+5: room for Й / Ё over the caps and the descenders), so every caller's maths (height / 8, widths in
 *   "font pixels") stays as it was - only the units get finer.
 * - The atlas: cells of one height packed into shelves (rows), the atlas a power of two; mips by a premultiplied box
 *   filter down to an 8 px glyph (the cell padding keeps neighbours from bleeding in).
 * - Textures are luminance + alpha (2 bytes a texel): the text and the dial marks are white / grey, the colour comes from
 *   glColor (GL_MODULATE).
 */
public final class HiResMath {

    /** Config values; AUTO first (the default). */
    public static final String[] QUALITY_KEYS = {"AUTO", "1K", "2K", "4K"};
    public static final int AUTO = 0, Q1K = 1, Q2K = 2, Q4K = 3;

    /** The cell in vanilla font units (y down): top, height, the baseline, the cap height. */
    public static final float CELL_TOP = -5F, CELL_UNITS = 10F, BASELINE = 3F, CAP_UNITS = 7F;
    /** The vanilla line box the callers size text by (FontRenderer: 8 px a line). */
    public static final float LINE_UNITS = 8F;
    /** Glyphs smaller than this (px) get no further mip level (the padding would run out). */
    public static final int MIN_MIP_GLYPH = 8;

    private HiResMath() {
    }

    // ------------------------------------------------------------------ quality

    /** A config string -> AUTO / Q1K / Q2K / Q4K (case and blanks ignored; unknown -> AUTO). */
    public static int parse(String s) {
        if (s == null) {
            return AUTO;
        }
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < QUALITY_KEYS.length; i++) {
            if (QUALITY_KEYS[i].equals(t)) {
                return i;
            }
        }
        return AUTO;
    }

    /** The level (Q1K..Q4K) for a setting and the window's height in pixels (AUTO: <= 1200 1K, <= 1800 2K, else 4K). */
    public static int level(int quality, int displayHeight) {
        if (quality >= Q1K && quality <= Q4K) {
            return quality;
        }
        if (displayHeight <= 1200) {
            return Q1K;
        }
        return displayHeight <= 1800 ? Q2K : Q4K;
    }

    /** Glyph cell height (px) of the text atlas at a level. */
    public static int glyphPx(int level) {
        return level >= Q4K ? 128 : level == Q2K ? 64 : 32;
    }

    /** A baked dial face's side (px) at a level. */
    public static int facePx(int level) {
        return level >= Q4K ? 1024 : level == Q2K ? 512 : 256;
    }

    // ------------------------------------------------------------------ glyph metrics

    /** The padding round each glyph cell (px): an eighth of the glyph, at least 2 (mip bleeding). */
    public static int pad(int glyphPx) {
        return Math.max(2, glyphPx / 8);
    }

    /** The baseline inside the (unpadded) cell, px from its top - fractional (Java2D draws at sub-pixel positions). */
    public static float baselinePx(int glyphPx) {
        return (BASELINE - CELL_TOP) / CELL_UNITS * glyphPx;
    }

    /** Vanilla font units per atlas pixel. */
    public static float unitsPerPx(int glyphPx) {
        return CELL_UNITS / glyphPx;
    }

    /** The scale (model units per font unit) of a text `widthUnits` wide in a box `height` tall (8 units) and maxW wide. */
    public static float fit(float widthUnits, float height, float maxW) {
        float k = height / LINE_UNITS;
        return widthUnits <= 0F ? k : Math.min(k, maxW / widthUnits);
    }

    // ------------------------------------------------------------------ atlas layout

    public static int pow2(int n) {
        int p = 1;
        while (p < n) {
            p <<= 1;
        }
        return p;
    }

    /** The atlas width for a glyph size: 16 cells of the glyph's height (the ~170 glyphs then fill a square). */
    public static int atlasWidth(int glyphPx) {
        return pow2(glyphPx * 16);
    }

    /**
     * Shelf packing: cells of the given widths (px, padding included), all cellH tall, left to right in rows atlasW wide.
     * Returns {atlasW, atlasH (a power of two), x0, y0, x1, y1, ...} or null when a cell is wider than the atlas.
     */
    public static int[] pack(int[] widths, int cellH, int atlasW) {
        int[] out = new int[2 + widths.length * 2];
        int x = 0, y = 0;
        for (int i = 0; i < widths.length; i++) {
            if (widths[i] > atlasW) {
                return null;
            }
            if (x + widths[i] > atlasW) {
                x = 0;
                y += cellH;
            }
            out[2 + i * 2] = x;
            out[3 + i * 2] = y;
            x += widths[i];
        }
        out[0] = atlasW;
        out[1] = pow2(y + cellH);
        return out;
    }

    /** Mip levels of the atlas: the base plus halvings while the glyph stays >= MIN_MIP_GLYPH px. */
    public static int atlasLevels(int glyphPx) {
        int n = 1;
        for (int g = glyphPx / 2; g >= MIN_MIP_GLYPH; g /= 2) {
            n++;
        }
        return n;
    }

    /** Mip levels of a baked face: down to a 4 px side. */
    public static int faceLevels(int px) {
        int n = 1;
        for (int g = px / 2; g >= 4; g /= 2) {
            n++;
        }
        return n;
    }

    /** VRAM of a w x h texture, bytes a texel, with `levels` mips (each a quarter of the one before). */
    public static long vramBytes(int w, int h, int bytesPerTexel, int levels) {
        long sum = 0;
        for (int i = 0; i < levels; i++) {
            sum += (long) Math.max(1, w >> i) * Math.max(1, h >> i) * bytesPerTexel;
        }
        return sum;
    }

    /**
     * One mip step of a luminance + alpha image (2 bytes a texel): each 2 x 2 block -> one texel, the alpha averaged, the
     * luminance weighted by alpha (premultiplied: transparent texels don't darken the edges). Odd sizes clamp.
     */
    public static byte[] downLA(byte[] la, int w, int h) {
        int nw = Math.max(1, w / 2), nh = Math.max(1, h / 2);
        byte[] out = new byte[nw * nh * 2];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int la2 = 0, a2 = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.min(w - 1, x * 2 + dx), sy = Math.min(h - 1, y * 2 + dy), i = (sy * w + sx) * 2;
                        int l = la[i] & 255, a = la[i + 1] & 255;
                        la2 += l * a;
                        a2 += a;
                    }
                }
                int o = (y * nw + x) * 2;
                out[o] = (byte) (a2 == 0 ? 255 : (la2 + a2 / 2) / a2);
                out[o + 1] = (byte) ((a2 + 2) / 4);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ cache keys

    /** The baked faces' kinds. */
    public static final int FACE_DIAL = 1, FACE_SCALE = 2;

    /**
     * A baked face's cache key: its kind and up to four numbers (the dial: its reading's kind, min, max; the glass scale:
     * max, minor, major). The quality level and the language are not in it - a change of either empties the whole cache.
     * Mutable so the renderer looks up with one reused probe (no garbage per frame); copy() before storing.
     */
    public static final class Key {
        public int face, a;
        public float b, c, d;

        public Key set(int face, int a, float b, float c, float d) {
            this.face = face;
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
            return this;
        }

        public Key copy() {
            return new Key().set(face, a, b, c, d);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key k = (Key) o;
            return face == k.face && a == k.a && Float.floatToIntBits(b) == Float.floatToIntBits(k.b)
                    && Float.floatToIntBits(c) == Float.floatToIntBits(k.c) && Float.floatToIntBits(d) == Float.floatToIntBits(k.d);
        }

        @Override
        public int hashCode() {
            int h = face * 31 + a;
            h = h * 31 + Float.floatToIntBits(b);
            h = h * 31 + Float.floatToIntBits(c);
            return h * 31 + Float.floatToIntBits(d);
        }

        @Override
        public String toString() {
            return face + "/" + a + "/" + b + "/" + c + "/" + d;
        }
    }
}
