package com.sva.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★14 markings (aircraft stage B; mock-up docs/mockups/veh_air2_4_textures.png) as pure math - the stencil font, the
 * marking images and where each one sits per model - so the dedicated server's self-test checks them; ObjModelSVA /
 * LiverySVA only upload and draw.
 * <p>
 * Every marking is a decal over a box in model units, projected either from the side (SIDE: x / y, faces with |nz| >
 * SkinMath.SIDE_NZ looking away from the centre plane - like the board decal; `readable` ones are mirrored on the left
 * side so the text reads left to right on both) or from above (TOP: x / z on faces with ny > minN - the wing / flap
 * stencils, the anti-glare panel; u runs along +z, the image's top toward the nose, so a text on either wing reads from
 * behind the aircraft). Only the outermost painted faces take them (WeatherMath.Outer), drawn like the livery: opaque
 * texels, polygon offset, no depth writes.
 * <p>
 * The font: textures/models/stencil_font.png (scratchpad airB/font.py): 5-row pixel glyphs in 6 x 6 cells, 16 per row,
 * in the order of CHARS; a glyph's width is its rightmost lit column (the space: a faint marker in the cell's 6th row).
 */
public final class MarkMath {

    public static final int SIDE = 0, TOP = 1;
    /** The font atlas' characters, in cell order. */
    public static final String CHARS = "АБВГДЕЖЗИЙКЛМНОП"
            + "РСТУФХЦЧШЩЪЫЬЭЮЯ" + "S0123456789-.!:/ ";
    public static final String FONT = "/assets/siliconagevehicles/textures/models/stencil_font.png";
    public static final int CELL = 6, PER_ROW = 16, ROWS = 5;

    public static final int RED = 0xCD201C, YELLOW = 0xFAC41E, INK = 0x1C1C20, WHITE = 0xF0F0F0, MATTE = 0x2C2E36;

    /** Texts used (the self-test checks the font has every letter and none is a forbidden glyph). Registration: «СА-07» + board. */
    public static final String NO_STEP = "НЕ НАСТУПАТЬ",
            RESCUE = "СПАСЕНИЕ", PROP = "ВИНТ!",
            JACK = "ДОМКР.", TOW = "БУКСИР",
            DANGER = "ОПАСНО", BORE = "ОПАСНО: БУР",
            REG = "СА-07";

    public static final String[] TEXTS = {NO_STEP, RESCUE, PROP, JACK, TOW, DANGER, BORE, REG + "00", "0123456789"};

    private MarkMath() {
    }

    // ------------------------------------------------------------------ the font

    private static boolean[][][] glyphs;
    private static int[] widths;

    /** Loads the atlas once (null glyphs when it is missing: no markings, the self-test fails). */
    private static synchronized void font() {
        if (glyphs != null) {
            return;
        }
        glyphs = new boolean[CHARS.length()][][];
        widths = new int[CHARS.length()];
        try {
            java.io.InputStream in = MarkMath.class.getResourceAsStream(FONT);
            if (in == null) {
                return;
            }
            java.awt.image.BufferedImage img;
            try {
                img = javax.imageio.ImageIO.read(in);
            } finally {
                in.close();
            }
            for (int k = 0; k < CHARS.length(); k++) {
                int cx = k % PER_ROW * CELL, cy = k / PER_ROW * CELL;
                if (cy + CELL > img.getHeight()) {
                    continue;
                }
                boolean[][] g = new boolean[ROWS][CELL];
                int w = 0;
                for (int y = 0; y < CELL; y++) {
                    for (int x = 0; x < CELL; x++) {
                        int a = img.getRGB(cx + x, cy + y) >>> 24;
                        if (a > 0) {
                            if (y < ROWS && a > 128) {
                                g[y][x] = true;
                            }
                            w = Math.max(w, x + 1);
                        }
                    }
                }
                glyphs[k] = g;
                widths[k] = w;
            }
        } catch (Exception e) {
            // no font: has() says false for everything
        }
    }

    /** Does the font have this character? */
    public static boolean has(char c) {
        font();
        int k = CHARS.indexOf(c);
        return k >= 0 && glyphs[k] != null && widths[k] > 0;
    }

    /** The text's width in texels (1 texel between letters), unknown characters as 3. */
    public static int textWidth(String s) {
        font();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            int k = CHARS.indexOf(s.charAt(i));
            w += (k >= 0 && glyphs[k] != null ? widths[k] : 3) + (i > 0 ? 1 : 0);
        }
        return w;
    }

    private static void text(Img im, String s, int x0, int y0, int argb) {
        font();
        int x = x0;
        for (int i = 0; i < s.length(); i++) {
            int k = CHARS.indexOf(s.charAt(i));
            if (k < 0 || glyphs[k] == null) {
                x += 4;
                continue;
            }
            for (int r = 0; r < ROWS; r++) {
                for (int c = 0; c < widths[k]; c++) {
                    if (glyphs[k][r][c]) {
                        im.set(x + c, y0 + r, argb);
                    }
                }
            }
            x += widths[k] + 1;
        }
    }

    // ------------------------------------------------------------------ the images

    /** An ARGB image (row-major). */
    public static final class Img {
        public final int w, h;
        public final int[] px;

        Img(int w, int h) {
            this.w = w;
            this.h = h;
            px = new int[w * h];
        }

        void set(int x, int y, int argb) {
            if (x >= 0 && y >= 0 && x < w && y < h) {
                px[y * w + x] = argb;
            }
        }

        void fill(int x0, int y0, int x1, int y1, int argb) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    set(x, y, argb);
                }
            }
        }
    }

    /** A stencil: the text with a 1-texel margin, on `bg` (-1 clear). */
    public static Img stencil(String s, int fg, int bg) {
        Img im = new Img(textWidth(s) + 2, ROWS + 2);
        if (bg >= 0) {
            im.fill(0, 0, im.w - 1, im.h - 1, 0xFF000000 | bg);
        }
        text(im, s, 1, 1, 0xFF000000 | fg);
        return im;
    }

    /**
     * The image of marking `key` (board: the vehicle's board number, rgb: the livery's decal colour for the registration
     * and the fin number). Keys: see Mark / marks().
     */
    public static Img image(String key, int board, int rgb) {
        if ("antiglare".equals(key)) {
            Img im = new Img(8, 8);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    im.set(x, y, WeatherMath.hash(x, y, 71) < 0.3F ? 0xFF34363E : 0xFF000000 | MATTE);
                }
            }
            return im;
        }
        if ("red".equals(key)) {
            Img im = new Img(2, 2);
            im.fill(0, 0, 1, 1, 0xFF000000 | RED);
            return im;
        }
        if ("walk".equals(key)) {
            // the walkway: dark anti-slip checker, a black border line along both spanwise ends
            Img im = new Img(20, 20);
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    boolean on = (x / 3 + y / 3) % 2 == 0 && x % 3 != 0 && y % 3 != 0;
                    im.set(x, y, x == 0 || x == 19 ? 0xFF000000 | INK : on ? 0xFF3A3A40 : 0xFF242428);
                }
            }
            return im;
        }
        if ("corners".equals(key)) {
            // the rescue cut-in corners around a door: yellow L marks, 1/4 of each side long
            Img im = new Img(24, 24);
            for (int k = 0; k < 6; k++) {
                for (int t = 0; t < 2; t++) {
                    int a = k, b = t;
                    im.set(a, b, 0xFF000000 | YELLOW);
                    im.set(b, a, 0xFF000000 | YELLOW);
                    im.set(23 - a, b, 0xFF000000 | YELLOW);
                    im.set(23 - b, a, 0xFF000000 | YELLOW);
                    im.set(a, 23 - b, 0xFF000000 | YELLOW);
                    im.set(b, 23 - a, 0xFF000000 | YELLOW);
                    im.set(23 - a, 23 - b, 0xFF000000 | YELLOW);
                    im.set(23 - b, 23 - a, 0xFF000000 | YELLOW);
                }
            }
            return im;
        }
        if ("arrow".equals(key)) {
            // the rescue arrow: yellow with a red rim, pointing to +u (the door); RESCUE's stencil goes over its shaft
            int tw = textWidth(RESCUE) + 4;
            Img im = new Img(tw + 6, 11);
            for (int y = 0; y < 11; y++) {
                for (int x = 0; x < im.w; x++) {
                    boolean in = x < tw ? y >= 1 && y <= 9 : Math.abs(y - 5) <= (im.w - 1 - x);
                    boolean edge = in && (x == 0 || (x < tw ? y == 1 || y == 9 : Math.abs(y - 5) == im.w - 1 - x));
                    if (in) {
                        im.set(x, y, 0xFF000000 | (edge ? RED : YELLOW));
                    }
                }
            }
            return im;
        }
        if ("rescue".equals(key)) {
            return stencil(RESCUE, RED, -1);
        }
        if ("prop".equals(key)) {
            return stencil(PROP, WHITE, RED);
        }
        if ("danger".equals(key)) {
            return stencil(DANGER, WHITE, RED);
        }
        if ("bore".equals(key)) {
            return stencil(BORE, INK, YELLOW);
        }
        if ("tow".equals(key)) {
            return stencil(TOW, INK, YELLOW);
        }
        if ("nostep".equals(key)) {
            Img s = stencil(NO_STEP, INK, -1);
            Img im = new Img(s.w + 2, s.h + 2);                      // a thin frame around it
            for (int y = 0; y < im.h; y++) {
                for (int x = 0; x < im.w; x++) {
                    if (x == 0 || y == 0 || x == im.w - 1 || y == im.h - 1) {
                        im.set(x, y, 0xFF000000 | INK);
                    } else if (s.px[(y - 1) * s.w + x - 1] != 0) {
                        im.set(x, y, s.px[(y - 1) * s.w + x - 1]);
                    }
                }
            }
            return im;
        }
        if ("jack".equals(key)) {
            // a yellow triangle (the jacking point) + «ДОМКР.»
            Img s = stencil(JACK, INK, -1);
            Img im = new Img(s.w + 7, s.h);
            for (int y = 1; y <= 5; y++) {
                for (int x = 0; x < 7; x++) {
                    if (Math.abs(x - 3) <= (6 - y) * 3 / 5 + (y == 1 ? 1 : 0)) {
                        im.set(x, y, 0xFF000000 | YELLOW);
                    }
                }
            }
            for (int y = 0; y < s.h; y++) {
                for (int x = 0; x < s.w; x++) {
                    if (s.px[y * s.w + x] != 0) {
                        im.set(x + 7, y, s.px[y * s.w + x]);
                    }
                }
            }
            return im;
        }
        if ("reg".equals(key)) {
            return stencil(registration(board), rgb, -1);
        }
        if ("finno".equals(key)) {
            return stencil(SkinMath.boardText(board), rgb, -1);
        }
        if ("emblem".equals(key)) {
            int[] d = SkinMath.decal(board, rgb);                  // the decal's hexagon "SA" emblem (its first 16 columns)
            Img im = new Img(16, 16);
            for (int y = 0; y < 16; y++) {
                System.arraycopy(d, y * SkinMath.DECAL_W, im.px, y * 16, 16);
            }
            return im;
        }
        return null;
    }

    /** The image with a clear 1-texel rim (GL_CLAMP: faces reaching out of the box sample clear texels). */
    public static Img padded(Img im) {
        Img p = new Img(im.w + 2, im.h + 2);
        for (int y = 0; y < im.h; y++) {
            System.arraycopy(im.px, y * im.w, p.px, (y + 1) * p.w + 1, im.w);
        }
        return p;
    }

    private static final Map<String, int[]> SIZES = new HashMap<String, int[]>();

    /** The (unpadded) image size of a key {w, h} - the same for every board number / colour. */
    public static synchronized int[] size(String key) {
        int[] d = SIZES.get(key);
        if (d == null) {
            Img im = image(key, 23, 0xFFFFFF);
            d = im == null ? new int[] {1, 1} : new int[] {im.w, im.h};
            SIZES.put(key, d);
        }
        return d;
    }

    /**
     * The texture coordinates (into the padded image) of model point (x, y, z) on a face with normal z `fnz` under
     * marking `mk`: SIDE u along x (mirrored on the left side when readable), v down y; TOP u along z, v from the box's
     * front (x1) back.
     */
    public static void uv(Mark mk, float x, float y, float z, float fnz, float[] out) {
        int[] d = size(mk.key);
        float[] b = mk.box;
        float u, v;
        if (mk.proj == SIDE) {
            u = (x - b[0]) / (b[2] - b[0]);
            if (mk.readable && fnz < 0) {
                u = 1F - u;
            }
            v = (b[3] - y) / (b[3] - b[1]);
        } else {
            u = (z - b[1]) / (b[3] - b[1]);
            v = (b[2] - x) / (b[2] - b[0]);
        }
        out[0] = (1F + u * d[0]) / (d[0] + 2);
        out[1] = (1F + v * d[1]) / (d[1] + 2);
    }

    /**
     * Does a triangle (face normal fn) lie under the marking: facing right (SIDE: sideways, away from z = 0; TOP: ny >
     * minN) and overlapping its box?
     */
    public static boolean on(Mark mk, float[] fn, float[] a, float[] b, float[] c) {
        float[] bx = mk.box;
        int k;
        if (mk.proj == SIDE) {
            if (Math.abs(fn[2]) <= SkinMath.SIDE_NZ || fn[2] * (a[2] + b[2] + c[2]) <= 0) {
                return false;
            }
            k = 1;
        } else {
            if (fn[1] <= mk.minN) {
                return false;
            }
            k = 2;
        }
        return Math.max(a[0], Math.max(b[0], c[0])) > bx[0] && Math.min(a[0], Math.min(b[0], c[0])) < bx[2]
                && Math.max(a[k], Math.max(b[k], c[k])) > bx[1] && Math.min(a[k], Math.min(b[k], c[k])) < bx[3];
    }

    /** The registration painted on the rear fuselage: «СА-07» + the board number («СА-0723»). */
    public static String registration(int board) {
        return REG + SkinMath.boardText(board);
    }

    /** Does the image depend on the board number / the livery colour (then it is cached per those)? */
    public static boolean perBoard(String key) {
        return "reg".equals(key) || "finno".equals(key) || "emblem".equals(key);
    }

    // ------------------------------------------------------------------ the markings per model

    /** One marking: image `key` over `box` (SIDE {x0, y0, x1, y1}, TOP {x0, z0, x1, z1}). */
    public static final class Mark {
        public final String key;
        public final int proj;
        public final float[] box;
        public final boolean readable;
        /** TOP: the faces' minimal ny. */
        public final float minN;
        /** The share of the box that must lie on painted outer faces (self-test), 0 = not checked (shapes over glass). */
        public final float cover;

        Mark(String key, int proj, float[] box, boolean readable, float minN, float cover) {
            this.key = key;
            this.proj = proj;
            this.box = box;
            this.readable = readable;
            this.minN = minN;
            this.cover = cover;
        }
    }

    private static final Map<String, List<Mark>> MARKS = new HashMap<String, List<Mark>>();

    /** The model's markings (empty for most ground vehicles - their board decal stays). */
    public static synchronized List<Mark> marks(String model) {
        if (MARKS.isEmpty()) {
            define();
        }
        List<Mark> l = MARKS.get(model);
        return l == null ? Collections.<Mark>emptyList() : l;
    }

    private static List<Mark> list(String model) {
        List<Mark> l = MARKS.get(model);
        if (l == null) {
            l = new ArrayList<Mark>();
            MARKS.put(model, l);
        }
        return l;
    }

    /** A side marking from x0 at centre height yc, h tall, as wide as its image's aspect says. */
    private static void side(String model, String key, float x0, float yc, float h, boolean readable, float cover) {
        Img im = image(key, 23, 0xFFFFFF);
        float w = h * im.w / im.h;
        list(model).add(new Mark(key, SIDE, new float[] {x0, yc - h / 2F, x0 + w, yc + h / 2F}, readable, 0F, cover));
    }

    private static void sideBox(String model, String key, float x0, float y0, float x1, float y1, boolean readable, float cover) {
        list(model).add(new Mark(key, SIDE, new float[] {x0, y0, x1, y1}, readable, 0F, cover));
    }

    private static void top(String model, String key, float x0, float z0, float x1, float z1, float minN, float cover) {
        list(model).add(new Mark(key, TOP, new float[] {x0, z0, x1, z1}, false, minN, cover));
    }

    /** A spanwise text on both wings' tops: the band x0..x1 (its height), from |z| = z0 outward, as long as its aspect. */
    private static void wings(String model, String key, float x0, float x1, float z0) {
        Img im = image(key, 23, 0xFFFFFF);
        float len = (x1 - x0) * im.w / im.h;
        top(model, key, x0, z0, x1, z0 + len, SkinMath.TOP_NY, 0.8F);
        top(model, key, x0, -z0 - len, x1, -z0, SkinMath.TOP_NY, 0.8F);
    }

    /**
     * Positions (model units) from the mock-up's concept coordinates (veh_air3d.paint_tex: model = (concept - OFF) / 16)
     * and the exported OBJs (scratchpad airB/ortho.py), drawn after the livery so they sit on top of its stripe.
     */
    private static void define() {
        // light plane: matte anti-glare panel on the cowling ahead of the windscreen (concept x 18.9..27.2, |z| < 3.4); the
        // propeller's red danger band (concept x 25.75..26.25) + «ВИНТ!» under it; the rescue arrow with «СПАСЕНИЕ» and
        // the yellow corners around the door; the jacking point ahead of it, towing at the tail; registration on the rear
        // fuselage (y 0.75..0.85: below, the stabiliser; above, the fuselage curves away); the board number and the SA
        // emblem on the fin; the walkway at the wing roots and «НЕ НАСТУПАТЬ» along the wing's trailing edge (the
        // retracted flaps sit under it - coverage maps from scratchpad airB/cov.py)
        String m = "plane";
        top(m, "antiglare", 1.49F, -0.22F, 1.97F, 0.22F, 0.3F, 0.8F);
        sideBox(m, "red", 1.90F, 0.30F, 1.94F, 1.20F, false, 0F);
        top(m, "red", 1.90F, -0.45F, 1.94F, 0.45F, 0.3F, 0F);
        side(m, "prop", 1.52F, 0.56F, 0.075F, true, 0.8F);
        side(m, "arrow", 0.02F, 0.52F, 0.11F, false, 0.6F);
        side(m, "rescue", 0.06F, 0.52F, 0.07F, true, 0.6F);
        sideBox(m, "corners", 0.58F, 0.47F, 1.01F, 0.88F, false, 0F);
        side(m, "jack", -0.25F, 0.52F, 0.055F, true, 0.6F);
        side(m, "tow", -1.98F, 0.72F, 0.07F, true, 0.6F);
        side(m, "reg", -1.75F, 0.80F, 0.09F, true, 0.8F);
        side(m, "finno", -2.30F, 1.15F, 0.22F, true, 0.6F);
        sideBox(m, "emblem", -2.32F, 1.30F, -2.14F, 1.48F, true, 0.6F);
        top(m, "walk", 0.66F, 0.43F, 0.95F, 0.75F, SkinMath.TOP_NY, 0.8F);
        top(m, "walk", 0.66F, -0.75F, 0.95F, -0.43F, SkinMath.TOP_NY, 0.8F);
        wings(m, "nostep", 0.43F, 0.53F, 0.5F);
        // jet: anti-glare ahead of the canopy, «ОПАСНО» ahead of the intakes, registration aft, the fin's number + emblem,
        // «НЕ НАСТУПАТЬ» on the wing roots
        m = "jet";
        top(m, "antiglare", 1.75F, -0.13F, 2.15F, 0.13F, 0.3F, 0.8F);
        side(m, "danger", 1.13F, 0.46F, 0.07F, true, 0.6F);
        side(m, "reg", -2.60F, 0.50F, 0.09F, true, 0.8F);
        side(m, "finno", -2.80F, 1.08F, 0.20F, true, 0.6F);
        sideBox(m, "emblem", -2.83F, 1.20F, -2.67F, 1.36F, true, 0.6F);
        side(m, "jack", -0.62F, 0.36F, 0.06F, true, 0.5F);
        wings(m, "nostep", -1.50F, -1.38F, 0.55F);
        // tiltrotor: anti-glare on the nose, rescue arrow + corners at the white cabin door, registration, fin number /
        // emblem under the T-tail, «ВИНТ!» on the nacelles' outboard sides, «НЕ НАСТУПАТЬ» on the flaperons, walkway
        m = "tilt";
        top(m, "antiglare", 2.04F, -0.15F, 2.22F, 0.15F, 0.3F, 0.6F);
        side(m, "arrow", -1.52F, 0.72F, 0.10F, false, 0.6F);
        side(m, "rescue", -1.49F, 0.72F, 0.065F, true, 0.6F);
        sideBox(m, "corners", -0.95F, 0.58F, -0.05F, 0.97F, false, 0F);
        side(m, "reg", -1.95F, 0.80F, 0.10F, true, 0.8F);
        side(m, "finno", -2.0F, 1.16F, 0.20F, true, 0.6F);
        sideBox(m, "emblem", -2.12F, 1.36F, -1.94F, 1.54F, true, 0.6F);
        side(m, "prop", 0.45F, 1.10F, 0.08F, true, 0.5F);
        side(m, "jack", -0.60F, 0.53F, 0.055F, true, 0.6F);
        wings(m, "nostep", 0.12F, 0.24F, 0.5F);
        top(m, "walk", 0.45F, 0.42F, 0.75F, 0.75F, SkinMath.TOP_NY, 0.8F);
        top(m, "walk", 0.45F, -0.75F, 0.75F, -0.42F, SkinMath.TOP_NY, 0.8F);
        // helicopter: «ВИНТ!» on the tail boom ahead of the tail rotor, the registration on the boom
        m = "heli";
        side(m, "prop", -2.20F, 1.065F, 0.07F, true, 0.6F);
        side(m, "reg", -1.35F, 1.06F, 0.07F, true, 0.6F);
        // the drill: «ОПАСНО: БУР» on the body ahead of the bore
        side("drill", "bore", 0.25F, 0.95F, 0.09F, true, 0.8F);
    }
}
