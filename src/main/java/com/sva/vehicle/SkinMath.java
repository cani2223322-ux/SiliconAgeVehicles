package com.sva.vehicle;

import com.sc.energy.Tier;

/**
 * Vehicle skins (design upgrade *1-*3) as pure math - no GL, no Minecraft client classes, so the dedicated server's
 * self-test checks it and the client renderer (ObjModelSVA / LiverySVA) only uploads and binds what this computes.
 * - *1 material textures: per triangle a 16x16 tile (textures/models/TILE.png, GL_REPEAT, grey detail multiplied by the
 *   material's colour) chosen by material and face orientation; UVs by box projection along the face's dominant axis,
 *   one tile per model unit = one texel per concept unit (export_obj.py: concept units / 16), like the mockups.
 * - *2 seams / rivets live in the paint tile (a panel per model unit); the charge hatch sits in the side decal.
 * - *3 liveries by tier: an overlay image per (model, livery, material) projected from the side over the paint's bbox
 *   (stripe, hazard band, glowing lines), the paint recoloured for III / Singular, and the side decal (SA emblem + board
 *   number + charge hatch) per board number.
 */
public final class SkinMath {

    /** Liveries: I «Рабочая» (MV), II «Гражданская» (HV, EV), III «Кремниевая» (QV, XV), «Сингулярная» (SV). */
    public static final int WORK = 0, CIVIL = 1, SILICON = 2, SINGULAR = 3;
    /** Texels per model unit (the tile size and the overlay's resolution). */
    public static final int TEXELS = 16;
    /** Faces whose normal's |y| is above this are "top" faces: no stripe / hazard band there (glow lines still run over them). */
    public static final float TOP_NY = 0.75F;
    /** The side decal goes on faces whose normal's |z| is above this. */
    public static final float SIDE_NZ = 0.55F;
    /** Decal image size (texels): emblem 0..15, number 18..32, charge hatch 43..56. */
    public static final int DECAL_W = 64, DECAL_H = 16;

    private SkinMath() {
    }

    // ------------------------------------------------------------------ liveries, board number

    public static int livery(Tier t) {
        if (t == null) {
            return WORK;
        }
        switch (t) {
            case LV:
            case MV:
                return WORK;
            case HV:
            case EV:
            case IV:
                return CIVIL;
            case QV:
            case XV:
                return SILICON;
            default:
                return SINGULAR;
        }
    }

    /** Glowing lines of III / Singular (0xRRGGBB), -1 none. */
    public static int glowColour(int livery) {
        return livery == SILICON ? 0x5AD2FF : livery == SINGULAR ? 0xC878FF : -1;
    }

    /**
     * Board number 1..99 from the vehicle's id (VehicleState.vehId, a UUID string kept in the item's NBT): stable for the
     * vehicle's whole life (placed / picked up / transferred), spread evenly (FNV-1a over the chars, not String.hashCode
     * whose low digits follow the last chars). null / "" -> 1.
     */
    public static int boardNumber(String id) {
        if (id == null || id.length() == 0) {
            return 1;
        }
        int h = 0x811C9DC5;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x01000193;
        }
        return (int) ((h & 0xFFFFFFFFL) % 99L) + 1;
    }

    /** Two digits ("07"); out of range -> clamped into 1..99. */
    public static String boardText(int n) {
        n = Math.max(1, Math.min(99, n));
        return (n < 10 ? "0" : "") + n;
    }

    /** The painted shell materials (overlays, decal, recolour). */
    public static boolean paintable(String mat) {
        return "paint".equals(mat) || "paint2".equals(mat) || "paint3".equals(mat) || "white".equals(mat);
    }

    /**
     * The livery's colour of a material (0xRRGGBB): III / Singular repaint the main paint (`primary`: paint, or white on a
     * model without paint - the shuttle) and paint2; I / II and everything else keep `base`.
     */
    public static int paintColour(int livery, String mat, String primary, int base) {
        if (livery != SILICON && livery != SINGULAR) {
            return base;
        }
        if (mat.equals(primary)) {
            return livery == SILICON ? 0x1E2636 : 0x180E28;
        }
        if ("paint2".equals(mat)) {
            return livery == SILICON ? 0x121620 : 0x0C0814;
        }
        return base;
    }

    /** Liveries II stripe colour: paint3, else white, else paint2 (the model's own accent paint). */
    public static int civilStripe(java.util.Map<String, Integer> palette) {
        Integer c = palette.get("paint3");
        if (c == null) {
            c = palette.get("white");
        }
        if (c == null) {
            c = palette.get("paint2");
        }
        return c == null ? 0x2E5288 : c.intValue();
    }

    /** The decal's emblem / number colour. */
    public static int decalColour(int livery, int civilStripe) {
        switch (livery) {
            case WORK:
                return 0x1A1A1E;
            case CIVIL:
                return civilStripe;
            default:
                return glowColour(livery);
        }
    }

    // ------------------------------------------------------------------ *1 tiles and UVs

    /** Dominant axis of a (face) normal: 0 x, 1 y, 2 z. */
    public static int axis(float nx, float ny, float nz) {
        float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        return ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
    }

    /**
     * Box projection (out = {u, v}, in tiles): along x u = z, v = -y; along y u = x, v = z; along z u = x, v = -y (texture
     * rows go down, so "up" in the tile is up on the model's sides). One tile per model unit; continuous over every face
     * of one orientation, so neighbouring triangles of a flat panel share the texels.
     */
    public static void uv(int axis, float x, float y, float z, float[] out) {
        if (axis == 0) {
            out[0] = z;
            out[1] = -y;
        } else if (axis == 1) {
            out[0] = x;
            out[1] = z;
        } else {
            out[0] = x;
            out[1] = -y;
        }
    }

    /**
     * The tile of a triangle of material `mat` with the face normal's dominant `axis` and y component `fny`; null = drawn
     * untextured (screens, gauges, holograms, the singularity... - their own look stays). The dark material is
     * checker plate on floors / steps and a grille on faces looking forward / back, as in the mockup.
     */
    public static String tile(String mat, int axis, float fny, boolean emissive) {
        if (paintable(mat)) {
            return "paint";
        }
        if ("lamp".equals(mat) || "tail".equals(mat) || "red".equals(mat) || "amber".equals(mat) || "green".equals(mat)) {
            return "lamp";
        }
        if (emissive) {
            return null;
        }
        if ("dark".equals(mat) || "net".equals(mat)) {
            return fny > 0.7F ? "dark_plate" : axis == 0 ? "dark_grille" : "dark";
        }
        if ("steel".equals(mat)) {
            return "steel";
        }
        if ("chrome".equals(mat) || "disc".equals(mat) || "caliper".equals(mat)) {
            return "chrome";
        }
        if ("rubber".equals(mat)) {
            return "rubber";
        }
        if ("tread".equals(mat)) {
            return "tread";
        }
        if ("fabric".equals(mat) || "belt".equals(mat)) {
            return "fabric";
        }
        if ("leather".equals(mat)) {
            return "leather";
        }
        if ("carpet".equals(mat)) {
            return "carpet";
        }
        if ("trim".equals(mat)) {
            return "rubber";                                // ★7 inserts: the livery I look without interiorTrim
        }
        if ("headliner".equals(mat)) {
            return "fabric";
        }
        if ("glass".equals(mat) || "canopy".equals(mat)) {
            return "glass";
        }
        if ("hazard".equals(mat) || "solar".equals(mat) || "wood".equals(mat) || "stone".equals(mat)) {
            return mat;
        }
        return "metal";
    }

    /** Every tile tile() can return (the self-test checks each PNG is in the jar). */
    public static final String[] TILES = {"paint", "paint_smooth", "metal", "steel", "chrome", "rubber", "tread", "dark", "dark_plate",
            "dark_grille", "fabric", "leather", "carpet", "glass", "hazard", "solar", "lamp", "wood", "stone", "alcantara", "velvet", "carbon",
            "mat", "darkglass", "headliner"};

    /** The tile actually bound for a livery: the Singular paint has seams but no rivets. */
    public static String tileFor(int livery, String tile) {
        return livery == SINGULAR && "paint".equals(tile) ? "paint_smooth" : tile;
    }

    // ------------------------------------------------------------------ ★7 interior trim by tier (config vehicles.interiorTrim)

    /** Trim roles: seats / upholstery (fabric, leather), the floor (carpet), inserts (trim), the roof lining (headliner); -1 none. */
    public static final int SEAT = 0, FLOOR = 1, INSERT = 2, LINING = 3;
    /**
     * Per livery (WORK, CIVIL, SILICON, SINGULAR) and role: the tile and the colour (mock-up veh_cab_4_trim_night.png):
     * I rubber + fabric, ribbed mats; II leather, carpet, wood inserts, a light perforated lining; III alcantara, carbon;
     * Singular velvet + dark glass. The seat belts ('belt') and everything else keep their own look.
     */
    private static final String[][] TRIM_TILE = {{"fabric", "mat", "rubber", "fabric"}, {"leather", "carpet", "wood", "headliner"},
            {"alcantara", "carbon", "carbon", "alcantara"}, {"velvet", "darkglass", "darkglass", "velvet"}};
    private static final int[][] TRIM_RGB = {{0x3A3E48, 0x2A2A2E, 0x2C2C30, 0x4A4E56}, {0x6E3E24, 0x2A2624, 0x8C5E30, 0x8A8478},
            {0x2C313C, 0x1A1C22, 0x22242A, 0x30343E}, {0x2E1A44, 0x100C18, 0x1A1026, 0x2A1A3C}};

    /** The trim role of a material, -1 when it is not interior trim. */
    public static int trimRole(String mat) {
        if ("fabric".equals(mat) || "leather".equals(mat)) {
            return SEAT;
        }
        if ("carpet".equals(mat)) {
            return FLOOR;
        }
        if ("trim".equals(mat)) {
            return INSERT;
        }
        return "headliner".equals(mat) ? LINING : -1;
    }

    /** The tile bound for a trim material in a livery (`tile` unchanged for livery -1 or a non-trim material). */
    public static String trimTile(int livery, String mat, String tile) {
        int r = trimRole(mat);
        return livery < 0 || livery > SINGULAR || r < 0 || tile == null ? tile : TRIM_TILE[livery][r];
    }

    /** The colour of a trim material in a livery (0xRRGGBB; `base` for livery -1 or a non-trim material). */
    public static int trimColour(int livery, String mat, int base) {
        int r = trimRole(mat);
        return livery < 0 || livery > SINGULAR || r < 0 ? base : TRIM_RGB[livery][r];
    }

    /** Tiles that carry their own colours: the group is drawn white under them (hazard stripes, solar cells). */
    public static boolean ownColours(String mat) {
        return "hazard".equals(mat) || "solar".equals(mat);
    }

    // ------------------------------------------------------------------ *3 the side-projected livery overlay

    /** Overlay image width / height for the paint bbox {x0, y0, x1, y1} (one texel per concept unit + a clear 1-texel rim). */
    public static int overlayW(float[] box) {
        return (int) Math.ceil((box[2] - box[0]) * TEXELS) + 2;
    }

    public static int overlayH(float[] box) {
        return (int) Math.ceil((box[3] - box[1]) * TEXELS) + 2;
    }

    /** Overlay texture coordinates of model point (x, y): out = {u, v} in 0..1 (side projection, z ignored). */
    public static void overlayUv(float[] box, float x, float y, float[] out) {
        out[0] = (1F + (x - box[0]) * TEXELS) / overlayW(box);
        out[1] = (1F + (box[3] - y) * TEXELS) / overlayH(box);
    }

    /**
     * The overlay of one material (ARGB, row-major, alpha 0 or 255 - the cockpit clip passes may draw it twice), or null
     * when it would be empty. `glow`: the emissive layer (III / Singular lines), else the lit layer (I: dark stripe + black
     * and yellow hazard band along the lowest 20 % of the paint; II: a slanted stripe in the accent colour). Patterns in
     * concept units (texels) as in veh_tex2.shade_tex.
     */
    public static int[] overlay(int livery, String mat, String primary, float[] box, int civilStripe, int matColour, boolean glow) {
        int w = overlayW(box), h = overlayH(box);
        int[] px = new int[w * h];
        boolean any = false;
        float height = Math.max(box[3] - box[1], 1e-4F), length = Math.max(box[2] - box[0], 1e-4F);
        int glowRgb = glowColour(livery);
        if (glow && (glowRgb < 0 || !(mat.equals(primary) || "paint2".equals(mat)))) {
            return null;
        }
        if (!glow && (livery == SILICON || livery == SINGULAR)) {
            return null;
        }
        int midRow = -1;
        if (glow) {
            midRow = 1 + (int) Math.floor((box[3] - (box[1] + height * 0.5F)) * TEXELS);
        }
        for (int j = 1; j < h - 1; j++) {
            float y = box[3] - (j - 1 + 0.5F) / TEXELS;
            float hh = (y - box[1]) / height;
            for (int i = 1; i < w - 1; i++) {
                float x = box[0] + (i - 1 + 0.5F) / TEXELS;
                float u = (x - box[0]) / length;
                int c = 0;
                float xc = x * TEXELS, yc = y * TEXELS;
                if (glow) {
                    float t = xc * 0.9F + yc * 0.4F;
                    float m = t - 22F * (float) Math.floor(t / 22F);      // every 22 texels: 1-texel lines, twice the mock's width
                    if (m < 1.0F || j == midRow) {
                        c = 0xFF000000 | glowRgb;
                    }
                } else if (livery == WORK) {
                    if ("paint".equals(mat) && hh < 0.2F) {
                        c = (((int) Math.floor(xc) + (int) Math.floor(yc)) & 3) < 2 ? 0xFF18181A : 0xFFEBBE1E;
                    } else if ((mat.equals(primary) || "white".equals(mat)) && hh > 0.40F && hh < 0.47F) {
                        c = 0xFF1A1A1E;
                    }
                } else if (livery == CIVIL) {
                    float s = (u - 0.5F) * 0.06F;
                    if ((mat.equals(primary) || "white".equals(mat)) && (matColour & 0xFFFFFF) != (civilStripe & 0xFFFFFF)
                            && hh > 0.36F + s && hh < 0.42F + s) {
                        c = 0xFF000000 | civilStripe;
                    }
                }
                if (c != 0) {
                    px[j * w + i] = c;
                    any = true;
                }
            }
        }
        return any ? px : null;
    }

    // ------------------------------------------------------------------ *2 / *3 the side decal

    /**
     * Decal boxes {left x, centre y, width} in model units, per model (height = width / 4), from the mockup's positions
     * (veh_tex2.setup, concept units -> export_obj's centred model space), then fitted (scratchpad decal_fit2.py) so that,
     * seen from either side, the outermost surface over the box is painted and looks sideways (|nz| > SIDE_NZ): no wheel,
     * fender or pipe in front of the emblem / number - the self-test checks that coverage.
     */
    private static final java.util.Map<String, float[]> DECALS = new java.util.HashMap<String, float[]>();

    static {
        decal("buggy", -0.46F, 0.65F, 1.06F);
        decal("truck", -0.85F, 1.39F, 2.03F);
        decal("drill", -1.62F, 1.42F, 1.72F);
        decal("hover", -0.83F, 0.39F, 0.94F);
        decal("heli", -0.40F, 0.99F, 1.41F);
        decal("plane", -1.25F, 0.74F, 1.25F);
        decal("jet", -1.82F, 0.69F, 0.85F);
        decal("tilt", -1.16F, 0.83F, 1.56F);
        decal("shuttle", -1.90F, 0.98F, 1.88F);
        decal("ship", -0.39F, 0.69F, 1.99F);
    }

    private static void decal(String model, float left, float y, float w) {
        DECALS.put(model, new float[] {left, y, w});
    }

    /** {x0, y0, x1, y1} of the model's decal (model units), or null (no decal). */
    public static float[] decalBox(String model) {
        float[] d = DECALS.get(model);
        if (d == null) {
            return null;
        }
        float hh = d[2] / 4F;
        return new float[] {d[0], d[1] - hh / 2F, d[0] + d[2], d[1] + hh / 2F};
    }

    /**
     * Decal texture coordinates of (x, y) on a side face looking to `nz`'s side: u along x, mirrored on the left side
     * (z < 0, seen looking +z, where +x runs to the left) so the emblem leads and the number reads left to right on both.
     */
    public static void decalUv(float[] box, float x, float y, float nz, float[] out) {
        float u = (x - box[0]) / (box[2] - box[0]);
        out[0] = nz < 0 ? 1F - u : u;
        out[1] = (box[3] - y) / (box[3] - box[1]);
    }

    /** 3x5 pixel glyphs (rows top-down, bit 2 = left): 0-9, then S, A. */
    private static final int[][] GLYPH = {{7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
            {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 2, 2, 2}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7}, {3, 4, 2, 1, 6}, {2, 5, 7, 5, 5}};

    private static void glyph(int[] px, int g, int x0, int y0, int scale, int argb) {
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c < 3; c++) {
                if ((GLYPH[g][r] >> (2 - c) & 1) != 0) {
                    for (int dy = 0; dy < scale; dy++) {
                        for (int dx = 0; dx < scale; dx++) {
                            px[(y0 + r * scale + dy) * DECAL_W + x0 + c * scale + dx] = argb;
                        }
                    }
                }
            }
        }
    }

    /**
     * The decal (DECAL_W x DECAL_H ARGB): a hexagon "SA" emblem in `rgb` with a contrasting core, the two-digit board number,
     * and the charge hatch (dark frame, yellow bolt). Clear elsewhere, with a clear 1-texel rim (GL_CLAMP: faces reaching
     * out of the box sample clear texels).
     */
    public static int[] decal(int number, int rgb) {
        int[] px = new int[DECAL_W * DECAL_H];
        int fg = 0xFF000000 | rgb & 0xFFFFFF;
        // the emblem's core contrasts with its rim: dark under a light colour, light under a dark one (livery I)
        int lum = (rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255);
        int core = lum < 900 ? 0xFFEDEDED : 0xFF141418;
        // emblem: pointy-top hexagon around (8, 8), outer radius 6.9, its core 2 texels in
        for (int y = 1; y < 15; y++) {
            for (int x = 1; x < 15; x++) {
                float dx = Math.abs(x + 0.5F - 8F), dy = Math.abs(y + 0.5F - 8F);
                float d = Math.max(dx / 0.866F, dy + dx * 0.577F);           // hex "radius" (pointy top)
                if (d <= 6.9F) {
                    px[y * DECAL_W + x] = d <= 5.0F ? core : fg;
                }
            }
        }
        glyph(px, 10, 4, 6, 1, fg);
        glyph(px, 11, 9, 6, 1, fg);
        String n = boardText(number);
        glyph(px, n.charAt(0) - '0', 18, 3, 2, fg);
        glyph(px, n.charAt(1) - '0', 26, 3, 2, fg);
        // charge hatch: a 14 x 11 frame, hinge dots, the bolt
        int hx0 = 43, hx1 = 56, hy0 = 2, hy1 = 12;
        for (int y = hy0; y <= hy1; y++) {
            for (int x = hx0; x <= hx1; x++) {
                if (x == hx0 || x == hx1 || y == hy0 || y == hy1) {
                    px[y * DECAL_W + x] = 0xFF1E1E22;
                }
            }
        }
        int[][] bolt = {{51, 4}, {50, 5}, {49, 6}, {50, 6}, {51, 6}, {52, 6}, {51, 7}, {50, 8}, {49, 9}, {50, 4}, {49, 5}, {48, 6}};
        for (int[] p : bolt) {
            px[p[1] * DECAL_W + p[0]] = 0xFFFFCD28;
        }
        return px;
    }
}
