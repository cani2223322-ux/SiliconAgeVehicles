package com.sva.airport;

import java.util.ArrayList;
import java.util.List;

/**
 * Airport wave B1 (plan-next §9, items 24-26): what is painted on and lit round a runway defined by its two «Торец полосы»
 * markers (IlsPairing.Rwy) - pure layout in the runway's frame (u across, + right of the landing direction; v along from the
 * threshold), so the client's batched renderer (client.AirportRenderSVA: one display list of paint per runway, one batch
 * of light sprites per frame) and the self-test read the same numbers. Sizes per AptScale (1 block = 10 m-eq) where the real
 * thing is readable at that size; the paint's widths have a floor so a 3-wide strip still shows its marks.
 * <ul>
 * <li><b>Paint</b> (paint()): the threshold «piano keys» (stripes() by the width: 6..16 stripes, 30 m-eq long, 6 m-eq
 * from the threshold), the designator (two digits of DIGITS, the parallel's letter L / C / R nearer the threshold), the
 * centre-line dashes (30 m-eq on, 20 off) between the two designators, the touchdown zone's bars (150, 600, 750, 900 m-eq:
 * 3 / 2 / 2 / 1 stripes a side) and the aiming point (two wide bars at 400 m-eq), the side stripes along both edges. Each
 * piece is a quad {u0, v0, u1, v1}; long ones are cut into PIECE-long parts (the renderer paints only over runway blocks).</li>
 * <li><b>Lights</b> (lights(), for a viewer landing in direction dir): edge (EDGE_STEP; white, yellow over the last
 * EDGE_YELLOW - the caution zone), centre line (CL_STEP; white, red / white alternating from CL_ALT before the end, red over
 * the last CL_RED), the touchdown zone's barrettes (TDZ_STEP rows to TDZ_LEN), the threshold's green bar (the far end's
 * red), and the approach lights (ALS, when the end has them): a centre line of 5-lamp barrettes every ALS_STEP to
 * ALS_LEN before the threshold, crossbars at ALS_BARS, and the sequenced flasher («бегущий огонь»: alsFlash, twice a
 * second from ALS_LEN in to ALS_FLASH_END).</li>
 * <li><b>Brightness</b>: 5 steps (LEVEL); AUTO (LIGHTS_AUTO) - on at night, in rain or with the RVR under AUTO_RVR (the
 * step by the darkness and the visibility - autoStep).</li>
 * </ul>
 */
public final class RunwayLayout {

    /** The lights' mode of a runway end: AUTO, or a fixed step 0 (off) .. 5. */
    public static final int LIGHTS_AUTO = -1, STEPS = 5;
    /**
     * Airport wave B2: a runway end's mode «ВЫШКА» - its lights follow the tower that owns its runway (the tower's runway /
     * approach steps); without a tower it is AUTO. Any other mode on the end's own screen is a local override.
     */
    public static final int LIGHTS_TOWER = -2;
    /** Intensity of each step (0 = off). */
    public static final double[] LEVEL = {0, 0.3, 0.5, 0.7, 0.85, 1.0};
    /** AUTO: on under this RVR (m-eq), the brightest under AUTO_RVR_LOW. */
    public static final double AUTO_RVR = 1500, AUTO_RVR_LOW = 800;
    /** Light colours. */
    public static final int WHITE = 0, YELLOW = 1, RED = 2, GREEN = 3, FLASH = 4,
            /** Airport wave B2: the taxiways' blue edge lights. */
            BLUE = 5;
    /** Spacing (blocks). */
    public static final double EDGE_STEP = 6, EDGE_YELLOW = 60, CL_STEP = 1.5, CL_ALT = 90, CL_RED = 30, TDZ_STEP = 3, TDZ_LEN = 90,
            ALS_STEP = 3, ALS_LEN = 90, ALS_FLASH_END = 30, THR_STEP = 0.5;
    /** The ALS crossbars: {distance before the threshold, half width}. */
    public static final double[][] ALS_BARS = {{15, 2.0}, {30, 3.0}, {45, 4.0}, {60, 5.0}, {75, 6.0}};
    /** The flasher's run (ticks): twice a second, the run takes RUN of it. */
    public static final double FLASH_PERIOD = 10, FLASH_RUN = 0.8, FLASH_WIDTH = 0.06;
    /** Paint pieces are at most this long (blocks). */
    public static final double PIECE = 4;

    private RunwayLayout() {
    }

    // ---------------------------------------------------------------- paint

    /** Threshold stripes by the runway's width (blocks). */
    public static int stripes(int width) {
        return width <= 3 ? 6 : width <= 5 ? 8 : width <= 7 ? 12 : 16;
    }

    /** The paint's line width floor (blocks). */
    static double line(int width) {
        return Math.max(0.15, width * 0.035);
    }

    /** 3 x 5 glyphs, top row first: the digits and L, C, R. */
    public static final String[][] DIGITS = {
        {"111", "101", "101", "101", "111"}, {"010", "110", "010", "010", "111"}, {"111", "001", "111", "100", "111"},
        {"111", "001", "111", "001", "111"}, {"101", "101", "111", "001", "001"}, {"111", "100", "111", "001", "111"},
        {"111", "100", "111", "101", "111"}, {"111", "001", "001", "010", "010"}, {"111", "101", "111", "101", "111"},
        {"111", "101", "111", "001", "111"}};
    public static final String[] GLYPH_L = {"100", "100", "100", "100", "111"}, GLYPH_C = {"111", "100", "100", "100", "111"},
            GLYPH_R = {"110", "101", "110", "101", "101"};

    /** The stripes start / length (blocks from the threshold), the gap to the designator, the designator's cell. */
    public static final double KEYS_V0 = 0.6, KEYS_LEN = 3.0, DESIG_GAP = 1.2;

    /** The cell size of the designator's glyphs across (blocks); along it is 1.6 x. */
    public static double cell(int width) {
        return Math.min(0.36, width * 0.8 / 7);
    }

    /** Where the designator ends (v, blocks from the threshold) - the centre line starts after it. */
    public static double designatorEnd(String name, int width) {
        double ch = cell(width) * 1.6;
        boolean letter = name != null && name.length() > 2;
        return KEYS_V0 + KEYS_LEN + DESIG_GAP + 5 * ch + (letter ? 5 * ch + ch : 0);
    }

    /** One end's paint in its own frame (u, v): keys, designator; TDZ and aiming point up to `maxV`. */
    public static List<double[]> endPaint(String name, int width, double maxV) {
        List<double[]> l = new ArrayList<double[]>();
        // the piano keys
        int n = stripes(width) / 2;
        double half = width / 2.0 - 0.3, inner = 0.25, pitch = (half - inner) / n, sw = pitch * 0.55;
        for (int k = 0; k < n; k++) {
            double u0 = inner + k * pitch + (pitch - sw) / 2;
            add(l, u0, KEYS_V0, u0 + sw, KEYS_V0 + KEYS_LEN);
            add(l, -u0 - sw, KEYS_V0, -u0, KEYS_V0 + KEYS_LEN);
        }
        // the designator: the letter nearer the threshold, then the number (top rows farthest - read on the approach)
        double cw = cell(width), ch = cw * 1.6;
        double v = KEYS_V0 + KEYS_LEN + DESIG_GAP;
        String num = name == null || name.length() < 2 ? "00" : name.substring(0, 2);
        if (name != null && name.length() > 2) {
            char c = name.charAt(2);
            glyph(l, c == 'L' ? GLYPH_L : c == 'C' ? GLYPH_C : GLYPH_R, -1.5 * cw, v, cw, ch);
            v += 5 * ch + ch;
        }
        for (int d = 0; d < 2; d++) {
            int digit = num.charAt(d) - '0';
            if (digit < 0 || digit > 9) {
                continue;
            }
            glyph(l, DIGITS[digit], -3.5 * cw + d * 4 * cw, v, cw, ch);
        }
        // the touchdown zone: {v, stripes a side}; the aiming point at 40
        double[][] tdz = {{15, 3}, {60, 2}, {75, 2}, {90, 1}};
        double sl = 2.25, sw2 = line(width) * 1.2, gap = sw2 * 0.9, uIn = Math.max(0.6, width * 0.16);
        for (double[] t : tdz) {
            if (t[0] + sl > maxV) {
                continue;
            }
            for (int k = 0; k < (int) t[1]; k++) {
                double u0 = uIn + k * (sw2 + gap);
                add(l, u0, t[0], u0 + sw2, t[0] + sl);
                add(l, -u0 - sw2, t[0], -u0, t[0] + sl);
            }
        }
        if (40 + 4.5 <= maxV) {
            double aw = Math.max(0.3, Math.min(0.6, width * 0.1)), au = Math.max(0.5, width * 0.18);
            add(l, au, 40, au + aw, 44.5);
            add(l, -au - aw, 40, -au, 44.5);
        }
        return l;
    }

    static void glyph(List<double[]> l, String[] g, double u0, double v0, double cw, double ch) {
        for (int row = 0; row < 5; row++) {
            for (int col = 0; col < 3; col++) {
                if (g[row].charAt(col) == '1') {
                    double v = v0 + (4 - row) * ch;
                    // mirrored across: the pilot reads it facing +v with + u to his right
                    add(l, u0 + col * cw, v, u0 + (col + 1) * cw, v + ch);
                }
            }
        }
    }

    static void add(List<double[]> l, double u0, double v0, double u1, double v1) {
        l.add(new double[] {Math.min(u0, u1), Math.min(v0, v1), Math.max(u0, u1), Math.max(v0, v1)});
    }

    /**
     * A whole runway's paint in end A's frame (u across + right landing A -> B, v from A): both ends (B's mirrored), the
     * centre line between the designators, the side stripes - long pieces cut to PIECE.
     */
    public static List<double[]> paint(double len, int width, String nameA, String nameB) {
        List<double[]> l = new ArrayList<double[]>();
        double half = len / 2 - 2;
        l.addAll(endPaint(nameA, width, half));
        for (double[] q : endPaint(nameB, width, half)) {
            l.add(new double[] {-q[2], len - q[3], -q[0], len - q[1]});
        }
        double lw = line(width);
        double v0 = designatorEnd(nameA, width) + 2, v1 = len - designatorEnd(nameB, width) - 2;
        for (double v = v0; v + 1 < v1; v += 5) {
            add(l, -lw / 2, v, lw / 2, Math.min(v + 3, v1));
        }
        double e = width / 2.0 - 0.15 - lw * 0.4;
        for (double v = 0; v < len; v += PIECE) {
            double w1 = Math.min(len, v + PIECE);
            add(l, e - lw * 0.8, v, e, w1);
            add(l, -e, v, -e + lw * 0.8, w1);
        }
        return l;
    }

    // ---------------------------------------------------------------- lights

    /** A light: u, v (end A's frame), its colour for the viewer (WHITE..FLASH), its height over the surface (blocks), flash phase. */
    public static final class Light {
        public final double u, v, h;
        public final int colour;
        /** ALS flashers: their place in the run (0 outer .. 1 inner), else -1. */
        public final double run;

        Light(double u, double v, double h, int colour, double run) {
            this.u = u;
            this.v = v;
            this.h = h;
            this.colour = colour;
            this.run = run;
        }
    }

    /** An edge light `v` blocks from the threshold of the viewer's landing direction: yellow over the last EDGE_YELLOW. */
    public static int edgeColour(double v, double len) {
        return len - v <= EDGE_YELLOW ? YELLOW : WHITE;
    }

    /** A centre-line light `toEnd` blocks before the far end, index `i` along: white; red / white from CL_ALT; red from CL_RED. */
    public static int centreColour(double toEnd, int i) {
        if (toEnd <= CL_RED) {
            return RED;
        }
        if (toEnd <= CL_ALT) {
            return (i & 1) == 0 ? RED : WHITE;
        }
        return WHITE;
    }

    /**
     * Every light a viewer landing in direction `dir` (+1 toward B) sees, in end A's frame: edge, centre line, TDZ (his
     * landing end), threshold green / end red, the ALS of his landing end when it has one (alsA / alsB).
     */
    public static List<Light> lights(double len, int width, int dir, boolean alsA, boolean alsB) {
        List<Light> l = new ArrayList<Light>();
        double eu = width / 2.0 + 0.15;
        // edge lights both sides
        int n = (int) Math.floor(len / EDGE_STEP);
        for (int i = 0; i <= n; i++) {
            double v = Math.min(len, i * EDGE_STEP);
            double vd = dir > 0 ? v : len - v;
            int c = edgeColour(vd, len);
            l.add(new Light(eu, v, 0.25, c, -1));
            l.add(new Light(-eu, v, 0.25, c, -1));
        }
        // the centre line
        int m = (int) Math.floor((len - 1) / CL_STEP);
        for (int i = 1; i <= m; i++) {
            double v = i * CL_STEP;
            double toEnd = dir > 0 ? len - v : v;
            int idx = dir > 0 ? i : m - i;
            l.add(new Light(0, v, 0.03, centreColour(toEnd, idx), -1));
        }
        // the touchdown zone of the viewer's landing end
        double tu = Math.max(0.6, width * 0.2);
        for (double vd = TDZ_STEP; vd <= Math.min(TDZ_LEN, len / 2); vd += TDZ_STEP) {
            double v = dir > 0 ? vd : len - vd;
            for (int k = 0; k < 3; k++) {
                double u = tu + k * 0.15;
                l.add(new Light(u, v, 0.03, WHITE, -1));
                l.add(new Light(-u, v, 0.03, WHITE, -1));
            }
        }
        // threshold (green, his end) and end (red, the far end)
        double half = width / 2.0;
        for (double u = -half + 0.25; u <= half - 0.25 + 1e-6; u += THR_STEP) {
            l.add(new Light(u, dir > 0 ? -0.2 : len + 0.2, 0.15, GREEN, -1));
            l.add(new Light(u, dir > 0 ? len + 0.2 : -0.2, 0.15, RED, -1));
        }
        // the approach lights of his landing end
        if (dir > 0 ? alsA : alsB) {
            for (Light a : als()) {
                l.add(dir > 0 ? new Light(a.u, -a.v, a.h, a.colour, a.run) : new Light(-a.u, len + a.v, a.h, a.colour, a.run));
            }
        }
        return l;
    }

    /** The ALS in its own frame: v = distance BEFORE the threshold (the caller turns it), the barrettes, bars and flashers. */
    public static List<Light> als() {
        List<Light> l = new ArrayList<Light>();
        for (double d = ALS_STEP; d <= ALS_LEN + 1e-6; d += ALS_STEP) {
            double h = 0.3 + d * 0.01;
            for (int k = -2; k <= 2; k++) {
                l.add(new Light(k * 0.2, d, h, WHITE, -1));
            }
            if (d >= ALS_FLASH_END - 1e-6) {
                l.add(new Light(0, d, h + 0.25, FLASH, (ALS_LEN - d) / (ALS_LEN - ALS_FLASH_END)));
            }
        }
        for (double[] b : ALS_BARS) {
            double h = 0.3 + b[0] * 0.01;
            for (double u = 0.6; u <= b[1] + 1e-6; u += 0.6) {
                l.add(new Light(u, b[0], h, WHITE, -1));
                l.add(new Light(-u, b[0], h, WHITE, -1));
            }
        }
        return l;
    }

    /** A flasher's brightness (0..1) at world time `time` (ticks) by its place in the run (0 outer .. 1 inner). */
    public static double alsFlash(double time, double run) {
        double ph = (time % FLASH_PERIOD + FLASH_PERIOD) % FLASH_PERIOD / FLASH_PERIOD;
        double at = Math.max(0, Math.min(1, run)) * FLASH_RUN;
        return Math.max(0, 1 - Math.abs(ph - at) / FLASH_WIDTH);
    }

    // ---------------------------------------------------------------- brightness

    /** AUTO's step: off by a clear day; night 3, dusk / rain 4, RVR under AUTO_RVR_LOW 5 (by day too), under AUTO_RVR 4. */
    public static int autoStep(boolean night, boolean rain, double rvr) {
        if (rvr < AUTO_RVR_LOW) {
            return 5;
        }
        if (rvr < AUTO_RVR || rain) {
            return 4;
        }
        return night ? 3 : 0;
    }

    /** The step burning: the end's mode (AUTO or fixed) with the conditions. */
    public static int step(int mode, boolean night, boolean rain, double rvr) {
        if (mode == LIGHTS_AUTO || mode == LIGHTS_TOWER) {
            return autoStep(night, rain, rvr);
        }
        return Math.max(0, Math.min(STEPS, mode));
    }

    /** A sprite's size (blocks) at `dist` blocks for its step: distance-independent on the screen beyond a near floor. */
    public static double spriteSize(double dist, int step) {
        double k = LEVEL[Math.max(0, Math.min(STEPS, step))];
        return Math.max(0.1, dist * 0.0032) * (0.55 + 0.45 * k);
    }
}
