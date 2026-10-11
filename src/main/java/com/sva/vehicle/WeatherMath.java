package com.sva.vehicle;

import java.util.HashMap;
import java.util.Map;

/**
 * ★13 weathering that grows with hull wear (aircraft stage B; mock-up docs/mockups/veh_air2_4_textures.png) as pure math -
 * no GL, so the dedicated server's self-test checks it; ObjModelSVA / LiverySVA only upload and draw what this computes.
 * <p>
 * Wear = 1 - hp / maxHp (the synced hull value the garage repairs - a repaired vehicle is clean again), rounded into
 * BUCKETS steps (0 / 25 / 50 / 75 / 100 %) so each model has at most 4 x a few generated images, made once and cached.
 * The images are projected like the livery overlay, one texel per concept unit (SkinMath.TEXELS per model unit):
 * - side faces (|ny| <= SkinMath.TOP_NY) from the side over the model's x / y box, one image per z zone (a nacelle at the
 *   wing tip gets its own soot without smearing the fuselage at the same x / y);
 * - top faces from above over the x / z box (chips just behind the leading-edge strips, scratches);
 * - bottom faces from below over the x / z box (oil streaks on the belly, soot, mud on the low ones).
 * Only the outermost faces take it (Outer: no soot on the cabin walls, no mud on the floor). Texels are fully opaque or
 * clear (dithered, the mod's pixel look), so the cockpit clip passes that draw a face twice change nothing.
 * Per model (Spec, numbers in model units measured from the exported OBJ, scratchpad airB/ortho.py): exhaust soot
 * sources, doors (chipped edges, the sill worn by boots), chip lines (cowling seams), the mud line, belly oil.
 */
public final class WeatherMath {

    /** Wear steps: 0, 25, 50, 75, 100 %. */
    public static final int BUCKETS = 5;
    /** Layers: painted side faces (grime + chips + scratches), other side faces (grime only), painted top faces (chips),
     * bottom faces low on the vehicle (oil, soot, mud), the higher bottom faces (oil, soot). */
    public static final int SIDE_PAINT = 0, SIDE_OTHER = 1, TOP_PAINT = 2, BOTTOM_LOW = 3, BOTTOM_HIGH = 4;

    public static final int SOOT_A = 0xFF2A2624, SOOT_B = 0xFF46403C, OIL = 0xFF3A2C18, MUD_A = 0xFF604C34, MUD_B = 0xFF7A6448,
            CHIP = 0xFFB0B4BC, CHIP_RIM = 0xFF6E7076, SCRATCH = 0xFFC8CCD2;

    private WeatherMath() {
    }

    /** The wear bucket of a hull: 0 clean (also with vehicles.wear off on the server: no wear exists) .. BUCKETS - 1. */
    public static int bucket(float hp, float maxHp, boolean wearOn) {
        if (!wearOn || maxHp <= 0F || hp != hp) {
            return 0;
        }
        float w = 1F - Math.max(0F, Math.min(1F, hp / maxHp));
        return Math.max(0, Math.min(BUCKETS - 1, Math.round(w * (BUCKETS - 1))));
    }

    /** The wear a bucket stands for, 0..1. */
    public static float level(int bucket) {
        return Math.max(0, Math.min(BUCKETS - 1, bucket)) / (float) (BUCKETS - 1);
    }

    /** Materials that get dirty (the hull and the running gear; not the cabin's upholstery, screens, glass). */
    public static boolean weathered(String mat) {
        return chippable(mat) || "metal".equals(mat) || "steel".equals(mat) || "chrome".equals(mat) || "dark".equals(mat)
                || "net".equals(mat) || "rubber".equals(mat) || "tread".equals(mat) || "lestrip".equals(mat) || "hazard".equals(mat)
                || "black".equals(mat) || "disc".equals(mat) || "caliper".equals(mat);
    }

    /** Painted materials whose paint chips (the livery's shell and the plane's coloured spats / trims). */
    public static boolean chippable(String mat) {
        return SkinMath.paintable(mat) || "accent".equals(mat);
    }

    // ------------------------------------------------------------------ per-model spec

    /** Where a model gets dirty. Every array row is in model units (x forward, y up, z right; zones on |z|). */
    public static final class Spec {
        /** z zones (|z| from, to) beyond zone 0 = everything else. */
        public float[][] zones = new float[0][];
        /** Soot sources {x, y, |z|, zone, dir (-1 blown aft), length at wear 0+, extra length per wear, spread}. */
        public float[][] soot = new float[0][];
        /** Doors {x0, y0, x1, y1, zone}: chips along the outline, most on the sill. */
        public float[][] doors = new float[0][];
        /** Chip lines {x, y0, y1, zone}: seams that get knocked (the cowling's edge). */
        public float[][] lines = new float[0][];
        /** Belly oil {x start, |z| half width, length per wear, dir}. */
        public float[][] oil = new float[0][];
        /** Mud climbs up to this height above the model's bottom at full wear (0: none); bottom faces below it are "low". */
        public float mudTop;
        /** Random scratches on the paint (0..1). */
        public float scratch = 0.5F;
        /** The leading-edge strips (top layer: chips just behind them): this material, at |z| >= edgeMinZ. */
        public String edgeMat;
        public float edgeMinZ;
    }

    private static final Map<String, Spec> SPECS = new HashMap<String, Spec>();

    static {
        // light plane (ortho plane_z1 / _y1): exhaust stubs at (1.76, 0.40, +-0.23) under the cowling, soot blown aft along
        // the lower fuselage (zone 1 = the fuselage and spats, |z| < 0.45 - the wing above stays clean); the door under the
        // cabin glass x 0.58..1.01, y 0.47..0.88 (concept 4.6..11.4 / 7.4..13.9, step at x 0.89); the cowling's rear seam at
        // x 1.48; oil from the cowling's lower seam (x 1.67) aft along the belly; gear / spats up to y 0.36.
        Spec s = spec("plane");
        s.zones = new float[][] {{0F, 0.45F}};
        s.soot = new float[][] {{1.76F, 0.42F, 0.23F, 1, -1, 0.3F, 2.1F, 0.05F}};
        s.doors = new float[][] {{0.58F, 0.47F, 1.01F, 0.88F, 1}};
        s.lines = new float[][] {{1.48F, 0.40F, 1.20F, 1}, {1.94F, 0.40F, 1.10F, 1}};
        s.oil = new float[][] {{1.67F, 0.30F, 1.6F, -1}};
        s.mudTop = 0.36F;
        s.edgeMat = "lestrip";
        // jet: the nozzle (x -2.95, y 0.56, r 0.2) scorches the tail cone; no door (the canopy opens - its thin sill band
        // would be all chips); the intakes' lip seam; the swept leading edge is the 'accent' slat strip (|z| >= 0.5: not the
        // fuselage's stripe)
        s = spec("jet");
        s.zones = new float[][] {{0F, 0.5F}};
        s.soot = new float[][] {{-2.45F, 0.56F, 0F, 1, -1, 0.5F, 0.15F, 0.12F}};
        s.lines = new float[][] {{1.12F, 0.30F, 0.60F, 1}};
        s.oil = new float[][] {{0.9F, 0.25F, 1.6F, -1}};
        s.mudTop = 0.30F;
        s.edgeMat = "accent";
        s.edgeMinZ = 0.5F;
        // tiltrotor: the engines' exhaust at the nacelles' tails (x -0.16, y 1.09, |z| 2.25; zone 2 = the nacelles); the
        // white cabin door x -0.95..-0.05, y 0.58..0.97; fuselage zone 1
        s = spec("tilt");
        s.zones = new float[][] {{0F, 0.5F}, {1.9F, 2.65F}};
        s.soot = new float[][] {{0.32F, 1.09F, 2.25F, 2, -1, 0.25F, 0.3F, 0.06F}};
        s.doors = new float[][] {{-0.95F, 0.58F, -0.05F, 0.97F, 1}};
        s.oil = new float[][] {{1.2F, 0.3F, 1.6F, -1}};
        s.mudTop = 0.35F;
        s.edgeMat = "lestrip";
        // helicopter: turbine exhaust at the cabin roof's tail (x -0.35, y 1.33) over the tail boom; skids / belly mud
        s = spec("heli");
        s.soot = new float[][] {{-0.35F, 1.30F, 0.2F, 0, -1, 0.3F, 1.0F, 0.06F}};
        s.oil = new float[][] {{0.6F, 0.25F, 1.0F, -1}};
        s.mudTop = 0.35F;
        // ground vehicles: mud on the wheels / tracks and the lower body, scratches; the truck's and the drill's stacks
        s = spec("buggy");
        s.mudTop = 0.75F;
        s.scratch = 1F;
        s = spec("truck");
        s.soot = new float[][] {{1.55F, 2.30F, 0.5F, 0, -1, 0.15F, 0.5F, 0.08F}};
        s.mudTop = 0.95F;
        s.scratch = 1F;
        s = spec("drill");
        s.soot = new float[][] {{-1.3F, 2.15F, 0.3F, 0, -1, 0.15F, 0.45F, 0.08F}, {-1.65F, 2.15F, 0.3F, 0, -1, 0.15F, 0.45F, 0.08F}};
        s.mudTop = 1.0F;
        s.scratch = 1F;
        s = spec("hover");
        s.mudTop = 0.25F;
        s.scratch = 0.8F;
        // shuttle: subtle - scorch at the main engine (tail, x -2.6) and a little grime underneath; the Singular ship: none
        s = spec("shuttle");
        s.soot = new float[][] {{-2.55F, 1.15F, 0F, 0, -1, 0.15F, 0.25F, 0.15F}};
        s.mudTop = 0.25F;
        s.scratch = 0.3F;
    }

    private static Spec spec(String model) {
        Spec s = new Spec();
        SPECS.put(model, s);
        return s;
    }

    /** The model's spec, null = never weathered (the Singular ship; the trailers keep their look). */
    public static Spec specOf(String model) {
        return SPECS.get(model);
    }

    /** The zone (0..zones) of a side face whose centre is at |z| = az. */
    public static int zone(Spec s, float az) {
        for (int k = 0; k < s.zones.length; k++) {
            if (az >= s.zones[k][0] && az <= s.zones[k][1]) {
                return k + 1;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ noise

    /** A stable hash of a texel to 0..1 (FNV-like mixing, no java.util.Random: identical on every side). */
    public static float hash(int i, int j, int seed) {
        int h = i * 0x27D4EB2D ^ j * 0x165667B1 ^ seed * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    // ------------------------------------------------------------------ the layers

    /**
     * One side image (ARGB, SkinMath.overlayW / H of `box` = {x0, y0, x1, y1}, the model's side bbox) for zone `zone` at
     * `bucket`; `paint`: SIDE_PAINT (chips / scratches too) else SIDE_OTHER. null when empty.
     */
    public static int[] side(Spec s, float[] box, int zone, int bucket, boolean paint) {
        float w = level(bucket);
        if (s == null || w <= 0F) {
            return null;
        }
        int W = SkinMath.overlayW(box), H = SkinMath.overlayH(box);
        int[] px = new int[W * H];
        boolean any = false;
        float t = SkinMath.TEXELS;
        float mudTop = box[1] + s.mudTop * (0.3F + 0.7F * w);
        for (int j = 1; j < H - 1; j++) {
            float y = box[3] - (j - 1 + 0.5F) / t;
            for (int i = 1; i < W - 1; i++) {
                float x = box[0] + (i - 1 + 0.5F) / t;
                int c = 0;
                if (paint) {
                    // chips: the door outlines (most on the sill), the seams, a few scratches anywhere
                    float zoneW = 0F;
                    for (float[] d : s.doors) {
                        if ((int) d[4] != zone) {
                            continue;
                        }
                        float dx = Math.max(d[0] - x, Math.max(0F, x - d[2])), dy = Math.max(d[1] - y, Math.max(0F, y - d[3]));
                        float out = (float) Math.sqrt(dx * dx + dy * dy);
                        float in = Math.min(Math.min(Math.abs(x - d[0]), Math.abs(x - d[2])), Math.min(Math.abs(y - d[1]), Math.abs(y - d[3])));
                        float ded = out > 0F ? out : in;
                        float k = Math.max(0F, 1F - ded / 0.07F) * (y < d[1] + 0.07F ? 1.6F : 0.8F);
                        zoneW = Math.max(zoneW, k);
                    }
                    for (float[] l : s.lines) {
                        if ((int) l[3] == zone && y >= l[1] && y <= l[2]) {
                            zoneW = Math.max(zoneW, Math.max(0F, 1F - Math.abs(x - l[0]) / 0.05F) * 0.8F);
                        }
                    }
                    float thr = w * 0.5F * zoneW;
                    float h = hash(i, j, 11);
                    if (h < thr) {
                        c = CHIP;
                    } else if (h < thr * 1.45F) {
                        c = CHIP_RIM;
                    } else if (hash(i / 3, j, 12) < s.scratch * w * 0.035F && hash(i, j, 13) < 0.8F) {
                        c = SCRATCH;                                          // short horizontal scratches (3-texel runs)
                    }
                }
                // mud: dense at the bottom, splashes above
                if (s.mudTop > 0F) {
                    float h = hash(i, j, 21);
                    if (y < mudTop) {
                        float p = w * 0.95F * (float) Math.pow((mudTop - y) / Math.max(1e-3F, mudTop - box[1]), 1.1);
                        if (h < p) {
                            c = hash(i, j, 22) < 0.5F ? MUD_A : MUD_B;
                        }
                    } else if (y < mudTop + 0.25F * w && h < 0.05F * w) {
                        c = MUD_B;
                    }
                }
                // soot blown along from the exhausts
                for (float[] so : s.soot) {
                    if ((int) so[3] != zone) {
                        continue;
                    }
                    float d = (x - so[0]) * so[4];
                    float len = so[5] + so[6] * w;
                    if (d < -0.03F || d > len) {
                        continue;
                    }
                    float yc = so[1] + Math.max(0F, d) * 0.05F, sp = so[7] + Math.max(0F, d) * 0.07F;
                    float streak = 0.55F + 0.45F * hash(0, j / 2, 31);
                    float a = w * (1F - Math.max(0F, d) / len) * (float) Math.exp(-((y - yc) / sp) * ((y - yc) / sp)) * streak;
                    a = Math.min(0.95F, a * 2.2F);
                    if (hash(i, j, 32) < a) {
                        c = a > 0.5F || hash(i, j, 33) < 0.5F ? SOOT_A : SOOT_B;
                    }
                }
                if (c != 0) {
                    px[j * W + i] = c;
                    any = true;
                }
            }
        }
        return any ? px : null;
    }

    /**
     * The top image over `box` = {x0, z0, x1, z1} (rows = z): chips in the 3 texels just behind (aft of) a leading-edge
     * texel (`edge`, the strip's footprint, same size; null = none) and scratches. Painted top faces only.
     */
    public static int[] top(Spec s, float[] box, int bucket, boolean[] edge) {
        float w = level(bucket);
        if (s == null || w <= 0F) {
            return null;
        }
        int W = SkinMath.overlayW(box), H = SkinMath.overlayH(box);
        int[] px = new int[W * H];
        boolean any = false;
        for (int j = 1; j < H - 1; j++) {
            for (int i = 1; i < W - 1; i++) {
                int c = 0;
                if (edge != null) {
                    for (int k = 1; k <= 3 && i + k < W; k++) {
                        if (edge[j * W + i + k] && !edge[j * W + i]) {
                            float thr = w * 0.6F * (1F - (k - 1) / 3F);
                            float h = hash(i, j, 41);
                            c = h < thr ? CHIP : h < thr * 1.4F ? CHIP_RIM : 0;
                            break;
                        }
                    }
                }
                if (c == 0 && hash(i / 3, j, 42) < s.scratch * w * 0.02F && hash(i, j, 43) < 0.8F) {
                    c = SCRATCH;
                }
                if (c != 0) {
                    px[j * W + i] = c;
                    any = true;
                }
            }
        }
        return any ? px : null;
    }

    /** The bottom image over `box` = {x0, z0, x1, z1}: belly oil, soot under the sources, mud on the `low` faces. */
    public static int[] bottom(Spec s, float[] box, int bucket, boolean low) {
        float w = level(bucket);
        if (s == null || w <= 0F) {
            return null;
        }
        int W = SkinMath.overlayW(box), H = SkinMath.overlayH(box);
        int[] px = new int[W * H];
        boolean any = false;
        float t = SkinMath.TEXELS;
        for (int j = 1; j < H - 1; j++) {
            float z = box[3] - (j - 1 + 0.5F) / t, az = Math.abs(z);
            for (int i = 1; i < W - 1; i++) {
                float x = box[0] + (i - 1 + 0.5F) / t;
                int c = 0;
                if (low && s.mudTop > 0F && hash(i, j, 51) < w * 0.55F) {
                    c = hash(i, j, 52) < 0.5F ? MUD_A : MUD_B;
                }
                for (float[] o : s.oil) {
                    float d = (x - o[0]) * o[3], len = o[2] * w;
                    if (az <= o[1] && d >= 0F && d <= len && hash(j, 0, 53) > 0.55F) {
                        float a = w * 0.9F * (1F - d / Math.max(1e-3F, len)) * (0.6F + 0.4F * hash(j, i / 4, 54));
                        if (hash(i, j, 55) < a + 0.25F) {
                            c = OIL;
                        }
                    }
                }
                for (float[] so : s.soot) {
                    float d = (x - so[0]) * so[4], len = so[5] + so[6] * w;
                    if (d < -0.03F || d > len) {
                        continue;
                    }
                    float sp = so[7] * 1.5F + Math.max(0F, d) * 0.07F;
                    float a = Math.min(0.9F, 2F * w * (1F - Math.max(0F, d) / len) * (float) Math.exp(-((az - so[2]) / sp) * ((az - so[2]) / sp)));
                    if (hash(i, j, 56) < a) {
                        c = SOOT_A;
                    }
                }
                if (c != 0) {
                    px[j * W + i] = c;
                    any = true;
                }
            }
        }
        return any ? px : null;
    }

    // ------------------------------------------------------------------ the outermost faces

    /**
     * Which faces are outermost along their own facing: six height fields (+-x, +-y, +-z) over the other two axes in
     * cells of 1 / TEXELS, filled with the extreme coordinate of every triangle (sampled at least once per cell, glass
     * included - the cabin seen through it is inside). A face whose dominant normal is (axis, sign) is outer when its
     * centre lies within TOL of that field's extreme at its cell: the cabin's walls, floor and dashboard are not.
     */
    public static final class Outer {
        public static final float TOL = 0.07F;
        private final float[] min, max;
        private final int[] n = new int[3];
        private final float[][] field = new float[6][];
        private final float cell = 1F / SkinMath.TEXELS;

        public Outer(float[] bbMin, float[] bbMax) {
            min = bbMin.clone();
            max = bbMax.clone();
            for (int a = 0; a < 3; a++) {
                n[a] = Math.max(1, (int) Math.ceil((max[a] - min[a]) / cell) + 1);
            }
            for (int d = 0; d < 6; d++) {
                int a = d / 2, b = (a + 1) % 3, c = (a + 2) % 3;
                field[d] = new float[n[b] * n[c]];
                java.util.Arrays.fill(field[d], Float.NaN);
            }
        }

        private int idx(int a, float pb, float pc) {
            int b = (a + 1) % 3, c = (a + 2) % 3;
            int ib = Math.max(0, Math.min(n[b] - 1, (int) ((pb - min[b]) / cell)));
            int ic = Math.max(0, Math.min(n[c] - 1, (int) ((pc - min[c]) / cell)));
            return ib * n[c] + ic;
        }

        private void point(float[] p) {
            for (int a = 0; a < 3; a++) {
                int k = idx(a, p[(a + 1) % 3], p[(a + 2) % 3]);
                float[] hi = field[a * 2], lo = field[a * 2 + 1];
                if (hi[k] != hi[k] || p[a] > hi[k]) {
                    hi[k] = p[a];
                }
                if (lo[k] != lo[k] || p[a] < lo[k]) {
                    lo[k] = p[a];
                }
            }
        }

        /** Adds a triangle (a, b, c: x, y, z first). */
        public void add(float[] a, float[] b, float[] c) {
            float e = Math.max(dist(a, b), Math.max(dist(b, c), dist(c, a)));
            int s = Math.max(1, Math.min(64, (int) Math.ceil(e / cell)));
            float[] p = new float[3];
            for (int u = 0; u <= s; u++) {
                for (int v = 0; u + v <= s; v++) {
                    float fu = u / (float) s, fv = v / (float) s, fw = 1F - fu - fv;
                    for (int k = 0; k < 3; k++) {
                        p[k] = a[k] * fw + b[k] * fu + c[k] * fv;
                    }
                    point(p);
                }
            }
        }

        private static float dist(float[] p, float[] q) {
            float dx = p[0] - q[0], dy = p[1] - q[1], dz = p[2] - q[2];
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /**
         * The facing a triangle is seen with from outside: fn when it is outermost along fn, -fn when it is outermost the
         * other way (thin plates drawn both-sided whose OBJ normals point inward - the plane's fin), null when it is inside.
         */
        public float[] facing(float[] a, float[] b, float[] c, float[] fn) {
            if (outer(a, b, c, fn)) {
                return fn;
            }
            float[] r = {-fn[0], -fn[1], -fn[2]};
            return outer(a, b, c, r) ? r : null;
        }

        /** Is the triangle with face normal fn outermost along its facing? */
        public boolean outer(float[] a, float[] b, float[] c, float[] fn) {
            int ax = SkinMath.axis(fn[0], fn[1], fn[2]);
            boolean pos = fn[ax] > 0;
            float[] ctr = {(a[0] + b[0] + c[0]) / 3F, (a[1] + b[1] + c[1]) / 3F, (a[2] + b[2] + c[2]) / 3F};
            float ext = field[ax * 2 + (pos ? 0 : 1)][idx(ax, ctr[(ax + 1) % 3], ctr[(ax + 2) % 3])];
            if (ext != ext) {
                return true;
            }
            return pos ? ctr[ax] >= ext - TOL : ctr[ax] <= ext + TOL;
        }
    }
}
