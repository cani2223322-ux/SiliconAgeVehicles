package com.sva.vehicle;

/**
 * Liner wave 1 (plan-next §7, mock-up docs/mockups/veh_liner_10_quality.png): pure numbers of the SA-320's procedural
 * textures and of its exterior's levels of detail - the self-test checks them; client/LinerTexSVA bakes, client/LinerModelSVA
 * picks the LOD.
 * <p>
 * <b>The atlas.</b> Every cockpit / cabin panel is painted from ONE description (LinerPaint, the port of the concept's
 * itex.py / icabtex.py) into one square atlas at the quality's size - 4096 / 2048 / 1024 / 512 px: the same layout in
 * panel units (1 unit ~ 1 mm) scaled by size / ATLAS_UNITS, so the tiers differ only in sharpness. RECTS: each panel's
 * place in the 4096-unit layout {x, y, w, h}.
 * <p>
 * <b>LOD.</b> The full exterior (LOD0, ~30.7k triangles) up to LOD1_AT blocks, LOD1 (~17.3k) up to LOD2_AT, LOD2 (~9.9k)
 * beyond; a band of LOD_HYST blocks keeps it from flickering at the border.
 */
public final class LinerTexMath {

    /** Config vehicles.linerTextures. */
    public static final String[] KEYS = {"AUTO", "LOW", "1K", "2K", "4K"};
    public static final int AUTO = 0, LOW = 1, Q1K = 2, Q2K = 3, Q4K = 4;
    /** The layout's size (units): the 4K atlas is 1 px a unit. */
    public static final int ATLAS_UNITS = 4096;

    /** Panels in the atlas (LinerControls.OVH .. SCR, then the cabin's pieces). */
    public static final int T_OVH = 0, T_GS = 1, T_MP = 2, T_PED = 3, T_SCL = 4, T_SCR = 5, T_SEAT = 6, T_CARPET = 7, T_WALL = 8, T_BINS = 9,
            T_BULK = 10, T_CEIL = 11, T_FABRIC = 12, T_GALLEY = 13, T_LINING = 14, T_PLAIN = 15, RECT_COUNT = 16;
    /**
     * {x, y, w, h} in layout units. Row 1: the overhead 1800 x 1400, the two lateral consoles 500 x 1400, the pedestal 760 x
     * 1660; row 2 (y 1660): the main panel 2000 x 620, the glare shield 2000 x 180 (+ the cabin's bulkhead, seat back,
     * carpet...); every rect 8 units apart (mipmaps don't bleed).
     */
    public static final int[][] RECTS = {
            {0, 0, 1800, 1400}, {0, 1680, 2000, 180}, {0, 1880, 2000, 620}, {2824, 0, 760, 1660}, {1808, 0, 500, 1400}, {2316, 0, 500, 1400},
            {2008, 1680, 450, 900}, {2466, 1680, 600, 600}, {3074, 1680, 1000, 700}, {0, 2508, 1600, 360}, {1608, 2588, 1100, 1000},
            {2716, 2392, 1000, 600}, {3592, 0, 480, 480}, {2716, 3000, 700, 1000}, {3424, 3000, 600, 600}, {3592, 488, 64, 64}};

    /** LOD switch distances (blocks) and the hysteresis band. */
    public static final double LOD1_AT = 64, LOD2_AT = 160, LOD_HYST = 6;

    private LinerTexMath() {
    }

    public static int parse(String s) {
        if (s == null) {
            return AUTO;
        }
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(t)) {
                return i;
            }
        }
        return AUTO;
    }

    /**
     * The atlas size (px) for the liner's setting and the cockpits' level (HiResMath.level: 1K / 2K / 4K): AUTO follows the
     * cockpits' quality.
     */
    public static int size(int setting, int cockpitLevel) {
        switch (setting) {
            case LOW:
                return 512;
            case Q1K:
                return 1024;
            case Q2K:
                return 2048;
            case Q4K:
                return 4096;
            default:
                return cockpitLevel >= HiResMath.Q4K ? 4096 : cockpitLevel == HiResMath.Q2K ? 2048 : 1024;
        }
    }

    /** px per layout unit at an atlas size. */
    public static double scale(int size) {
        return size / (double) ATLAS_UNITS;
    }

    /** Mipmap levels of a square atlas down to 4 px. */
    public static int levels(int size) {
        return HiResMath.faceLevels(size);
    }

    /** Video memory of the atlas with its mipmaps and the glow mask at half size (RGBA). */
    public static long vram(int size) {
        return HiResMath.vramBytes(size, size, 4, levels(size)) + HiResMath.vramBytes(size / 2, size / 2, 4, levels(size / 2));
    }

    /** A panel unit point -> atlas UV (0..1). */
    public static float u(int rect, double tx) {
        return (float) ((RECTS[rect][0] + tx) / ATLAS_UNITS);
    }

    public static float v(int rect, double ty) {
        return (float) ((RECTS[rect][1] + ty) / ATLAS_UNITS);
    }

    /** Do two rects overlap (with a gap of `gap` units required)? */
    public static boolean overlap(int[] a, int[] b, int gap) {
        return a[0] < b[0] + b[2] + gap && b[0] < a[0] + a[2] + gap && a[1] < b[1] + b[3] + gap && b[1] < a[1] + a[3] + gap;
    }

    /**
     * The LOD (0..2) for a distance (blocks), from the one drawn last (hysteresis: it changes only LOD_HYST past a border).
     */
    public static int lod(double dist, int last) {
        int want = dist < LOD1_AT ? 0 : dist < LOD2_AT ? 1 : 2;
        if (want == last || last < 0 || last > 2) {
            return want;
        }
        double border = want > last ? (want == 1 ? LOD1_AT : LOD2_AT) : (last == 1 ? LOD1_AT : LOD2_AT);
        return Math.abs(dist - border) < LOD_HYST ? last : want;
    }
}
