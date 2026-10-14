package com.sva.vehicle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liner wave 1: the SA-320's exterior as data - pure (no GL): the parser of its own OBJ files (assets liner/liner_lodN.obj,
 * written by scratchpad liner1/export_liner.py from the concept liner3d.py: one "g" per RIG GROUP, "usemtl" per material,
 * v / vn / f a//a), the rig (liner.rig.txt: each group's parent, hinge pivot and axis, its track, its limits), the
 * materials (liner.materials.txt) and the livery's UV mapping (the concept's tex_fus / tex_door / tex_fin / tex_nacelle,
 * the spinner's spiral and the cascades' grid as repeat patterns). The client (LinerModelSVA) compiles it into display
 * lists and poses the groups; the standalone preview rasterises the same triangles; the self-test checks the rig against
 * the files (every moving group has a pivot and a unit axis, every parent exists, the LODs have the same groups).
 */
public final class LinerModelData {

    /** Resource paths (under /assets/siliconagevehicles/). */
    public static final String DIR = "liner/", RIG = "liner.rig.txt", MATS = "liner.materials.txt";

    public static String objName(int lod) {
        return "liner_lod" + lod + ".obj";
    }

    /** One material of a group: unrolled triangles, 6 floats a vertex (x y z nx ny nz). */
    public static final class Mat {
        public final String name;
        public float[] v = new float[0];
        int n;

        Mat(String name) {
            this.name = name;
        }

        void add(float[] p, float[] q) {
            if (n + 6 > v.length) {
                v = java.util.Arrays.copyOf(v, Math.max(64, v.length * 2));
            }
            System.arraycopy(p, 0, v, n, 3);
            System.arraycopy(q, 0, v, n + 3, 3);
            n += 6;
        }

        void trim() {
            v = java.util.Arrays.copyOf(v, n);
        }

        public int vertices() {
            return v.length / 6;
        }
    }

    public static final class Group {
        public final String name;
        public final List<Mat> mats = new ArrayList<Mat>();

        Group(String name) {
            this.name = name;
        }

        public int triangles() {
            int t = 0;
            for (Mat m : mats) {
                t += m.vertices() / 3;
            }
            return t;
        }
    }

    public static final class Model {
        public final Map<String, Group> groups = new LinkedHashMap<String, Group>();

        public int triangles() {
            int t = 0;
            for (Group g : groups.values()) {
                t += g.triangles();
            }
            return t;
        }
    }

    /** Parses one LOD's OBJ. */
    public static Model parse(InputStream in) throws java.io.IOException {
        Model m = new Model();
        List<float[]> v = new ArrayList<float[]>(), vn = new ArrayList<float[]>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        Group g = null;
        Mat cur = null;
        Map<String, Mat> byName = new HashMap<String, Mat>();
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    v.add(floats(line, 2));
                } else if (line.startsWith("vn ")) {
                    vn.add(floats(line, 3));
                } else if (line.startsWith("g ")) {
                    String name = line.substring(2).trim();
                    g = m.groups.get(name);
                    if (g == null) {
                        g = new Group(name);
                        m.groups.put(name, g);
                    }
                    byName.clear();
                    for (Mat x : g.mats) {
                        byName.put(x.name, x);
                    }
                    cur = null;
                } else if (line.startsWith("usemtl ")) {
                    String name = line.substring(7).trim();
                    if (g == null) {
                        g = new Group("airframe");
                        m.groups.put(g.name, g);
                    }
                    cur = byName.get(name);
                    if (cur == null) {
                        cur = new Mat(name);
                        g.mats.add(cur);
                        byName.put(name, cur);
                    }
                } else if (line.startsWith("f ") && cur != null) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    int[] vi = new int[p.length], ni = new int[p.length];
                    for (int i = 0; i < p.length; i++) {
                        String[] s = p[i].split("/");
                        vi[i] = Integer.parseInt(s[0]) - 1;
                        ni[i] = s.length > 2 && s[2].length() > 0 ? Integer.parseInt(s[2]) - 1 : -1;
                    }
                    for (int i = 1; i + 1 < p.length; i++) {
                        int[] idx = {0, i, i + 1};
                        for (int k : idx) {
                            cur.add(v.get(vi[k]), ni[k] >= 0 ? vn.get(ni[k]) : new float[] {0, 1, 0});
                        }
                    }
                }
            }
        } finally {
            r.close();
        }
        for (Group gr : m.groups.values()) {
            for (Mat x : gr.mats) {
                x.trim();
            }
        }
        return m;
    }

    private static float[] floats(String line, int from) {
        String[] p = line.trim().split("\\s+");
        return new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])};
    }

    // ---------------------------------------------------------------- the rig

    /** One group's joint: parent ("" none), pivot / axis (null: does not turn), track (null: does not slide), limits. */
    public static final class Joint {
        public final String name, parent;
        public final float[] pivot, axis, track;
        public final float lo, hi;
        public final boolean hasLimits;

        Joint(String name, String parent, float[] pivot, float[] axis, float[] track, float lo, float hi, boolean hasLimits) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.axis = axis;
            this.track = track;
            this.lo = lo;
            this.hi = hi;
            this.hasLimits = hasLimits;
        }
    }

    public static Map<String, Joint> parseRig(InputStream in) throws java.io.IOException {
        Map<String, Joint> rig = new LinkedHashMap<String, Joint>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\s+");
                if (p.length < 13) {
                    continue;
                }
                float[] pivot = vec(p, 2), axis = vec(p, 5), track = vec(p, 8);
                boolean lim = !"-".equals(p[11]);
                rig.put(p[0], new Joint(p[0], "-".equals(p[1]) ? "" : p[1], pivot, axis, track, lim ? Float.parseFloat(p[11]) : 0,
                        lim ? Float.parseFloat(p[12]) : 0, lim));
            }
        } finally {
            r.close();
        }
        return rig;
    }

    private static float[] vec(String[] p, int i) {
        if ("-".equals(p[i])) {
            return null;
        }
        return new float[] {Float.parseFloat(p[i]), Float.parseFloat(p[i + 1]), Float.parseFloat(p[i + 2])};
    }

    /** A material's colour and emitter flag (and its colour when off). */
    public static final class Material {
        public final int rgb, off;
        public final boolean emissive;

        Material(int rgb, boolean emissive, int off) {
            this.rgb = rgb;
            this.emissive = emissive;
            this.off = off;
        }
    }

    public static Map<String, Material> parseMaterials(InputStream in) throws java.io.IOException {
        Map<String, Material> m = new HashMap<String, Material>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\\s+");
                if (p.length < 5) {
                    continue;
                }
                int rgb = Integer.parseInt(p[1]) << 16 | Integer.parseInt(p[2]) << 8 | Integer.parseInt(p[3]);
                boolean e = "1".equals(p[4]);
                int off = p.length >= 8 ? Integer.parseInt(p[5]) << 16 | Integer.parseInt(p[6]) << 8 | Integer.parseInt(p[7]) : rgb;
                m.put(p[0], new Material(rgb, e, off));
            }
        } finally {
            r.close();
        }
        return m;
    }

    // ---------------------------------------------------------------- posing (pure: the preview; the client mirrors it in GL)

    /** A pose: per group an angle (deg about its axis through its pivot) and a travel (x its track). */
    public static final class Pose {
        public final Map<String, float[]> at = new HashMap<String, float[]>();

        public Pose set(String group, float angle, float travel) {
            at.put(group, new float[] {angle, travel});
            return this;
        }

        public float angle(String g) {
            float[] a = at.get(g);
            return a == null ? 0 : a[0];
        }

        public float travel(String g) {
            float[] a = at.get(g);
            return a == null ? 0 : a[1];
        }
    }

    /** A group's local 4x4 (row-major 3x4: rotation about the hinge, then the track) for a pose. */
    public static float[] local(Joint j, Pose p) {
        float[] m = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
        if (j == null) {
            return m;
        }
        float a = p.angle(j.name), tr = p.travel(j.name);
        if (j.pivot != null && j.axis != null && a != 0) {
            float[] R = rodrigues(j.axis, a);
            float[] c = j.pivot;
            for (int i = 0; i < 3; i++) {
                m[i * 4] = R[i * 3];
                m[i * 4 + 1] = R[i * 3 + 1];
                m[i * 4 + 2] = R[i * 3 + 2];
                m[i * 4 + 3] = c[i] - (R[i * 3] * c[0] + R[i * 3 + 1] * c[1] + R[i * 3 + 2] * c[2]);
            }
        }
        if (j.track != null && tr != 0) {
            m[3] += j.track[0] * tr;
            m[7] += j.track[1] * tr;
            m[11] += j.track[2] * tr;
        }
        return m;
    }

    /** The group's world transform: its parents' then its own. */
    public static float[] world(Map<String, Joint> rig, String name, Pose p) {
        Joint j = rig.get(name);
        float[] m = local(j, p);
        if (j != null && j.parent.length() > 0) {
            m = mul(world(rig, j.parent, p), m);
        }
        return m;
    }

    static float[] mul(float[] a, float[] b) {
        float[] r = new float[12];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 4; k++) {
                float s = a[i * 4] * b[k] + a[i * 4 + 1] * b[4 + k] + a[i * 4 + 2] * b[8 + k];
                r[i * 4 + k] = s + (k == 3 ? a[i * 4 + 3] : 0);
            }
        }
        return r;
    }

    /** The rotation matrix (row-major 3x3) about a unit axis by deg - right-handed. */
    public static float[] rodrigues(float[] ax, double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        double x = ax[0], y = ax[1], z = ax[2];
        return new float[] {(float) (t * x * x + c), (float) (t * x * y - s * z), (float) (t * x * z + s * y), (float) (t * x * y + s * z),
                (float) (t * y * y + c), (float) (t * y * z - s * x), (float) (t * x * z - s * y), (float) (t * y * z + s * x), (float) (t * z * z + c)};
    }

    // ---------------------------------------------------------------- the livery's UVs (liner3d.py's tex functions)

    /** The fuselage profile (x, centre y, radius) - liner3d.py FUS, nose first. */
    static final float[][] FUS = {{9.22F, 1.76F, 0.03F}, {9.13F, 1.78F, 0.27F}, {8.95F, 1.82F, 0.49F}, {8.65F, 1.88F, 0.7F}, {8.25F, 1.95F, 0.88F},
            {7.75F, 2.02F, 1.03F}, {7.2F, 2.06F, 1.12F}, {6.6F, 2.09F, 1.17F}, {5.9F, 2.1F, 1.2F}, {-3.6F, 2.1F, 1.2F}, {-4.4F, 2.15F, 1.15F},
            {-5.3F, 2.25F, 1.04F}, {-6.2F, 2.38F, 0.88F}, {-7.1F, 2.52F, 0.7F}, {-8.0F, 2.65F, 0.5F}, {-8.7F, 2.74F, 0.32F}, {-9.05F, 2.78F, 0.22F}};

    /** The fuselage's centre height at x (linear between the profile's stations, as numpy.interp). */
    public static float fcy(float x) {
        return interp(x, 1);
    }

    public static float fr(float x) {
        return interp(x, 2);
    }

    private static float interp(float x, int k) {
        if (x >= FUS[0][0]) {
            return FUS[0][k];
        }
        for (int i = 0; i + 1 < FUS.length; i++) {
            float a = FUS[i][0], b = FUS[i + 1][0];
            if (x <= a && x >= b) {
                float t = (a - x) / (a - b);
                return FUS[i][k] + (FUS[i + 1][k] - FUS[i][k]) * t;
            }
        }
        return FUS[FUS.length - 1][k];
    }

    /** Livery kinds: the textured materials of the export. */
    public static boolean livery(String mat) {
        return "fus".equals(mat) || "door".equals(mat) || "doorR".equals(mat) || "fin".equals(mat) || "nacelle".equals(mat) || "skin".equals(mat);
    }

    public static boolean pattern(String mat) {
        return "spinner".equals(mat) || "cascade".equals(mat);
    }

    /**
     * The livery UV (0..1 of the livery image, LinerPaintSVA.LIV_W x LIV_H) of a point of a triangle of material `mat` whose
     * centroid has z `cz` (the side: z >= 0 right).
     */
    public static void uv(String mat, float x, float y, float z, float cz, float[] out) {
        double W = 2048, H = 1024, PX = 80;
        if ("doorR".equals(mat)) {
            // wave 7: the right side's doors (R1, the cargo doors) - their crops of the right fuselage without the openings
            // (LinerPaintSVA.R_DOORS: one column each, the strip's full theta height)
            double th = Math.toDegrees(Math.atan2(Math.abs(z), y - fcy(x)));
            int col = x > 4.6 ? 0 : x > 0 ? 1 : 2;
            double[] x0 = {4.95, 3.40, -3.00};
            double u = 1840 + col * 64 + Math.max(0, Math.min(63, (x - x0[col]) * PX));
            out[0] = (float) (u / W);
            out[1] = (float) ((Math.max(0, Math.min(180, th)) / 180.0 * 304) / H);
            return;
        }
        if ("fus".equals(mat) || "door".equals(mat)) {
            double th = Math.toDegrees(Math.atan2(Math.abs(z), y - fcy(x)));
            boolean right = cz >= 0 && !"door".equals(mat);
            double u = right ? (x + 9.3) * PX : (9.3 - x) * PX;
            int oy = "door".equals(mat) ? 616 : right ? 0 : 308;
            out[0] = (float) (u / W);
            out[1] = (float) ((oy + Math.max(0, Math.min(180, th)) / 180.0 * 304) / H);
        } else if ("fin".equals(mat)) {
            boolean right = cz >= 0;
            double u = right ? (x + 8.2) * PX : (-4.0 - x) * PX;
            double v = (5.9 - y) * PX;
            out[0] = (float) ((1496 + Math.max(0, Math.min(335, u))) / W);
            out[1] = (float) (((right ? 0 : 268) + Math.max(0, Math.min(263, v))) / H);
        } else if ("nacelle".equals(mat)) {
            double t = Math.max(0, Math.min(1, (x + 0.2) / 2.4));
            out[0] = (float) ((1496 + 0.5 + t * 255) / W);
            out[1] = (float) ((540 + 8) / H);
        } else {
            out[0] = (float) ((1760 + 8) / W);                    // the white patch (pylons, nose gear doors)
            out[1] = (float) ((540 + 8) / H);
        }
    }

    /**
     * The repeat patterns' UV (their own 64 px textures, GL_REPEAT): the spinner's spiral (s = turn + 2.4 x (x - 1.6)) and the
     * cascades' grid (40 a block along x, 14 / pi round). `ref` keeps a triangle's u continuous across the seam: u within
     * half a turn of it.
     */
    public static void patternUv(String mat, float x, float y, float z, float ref, float[] out) {
        double ph = Math.atan2(y - 0.85, Math.abs(z) - 2.75);
        if ("spinner".equals(mat)) {
            double s = ph / (2 * Math.PI) + (x - 1.6) * 2.4;
            while (s - ref > 0.5) {
                s -= 1;
            }
            while (ref - s > 0.5) {
                s += 1;
            }
            out[0] = (float) s;
            out[1] = 0.5F;
        } else {
            double v = ph * 14 / Math.PI;
            while (v - ref > 7) {
                v -= 14;
            }
            while (ref - v > 7) {
                v += 14;
            }
            out[0] = x * 40;
            out[1] = (float) v;
        }
    }

    /** The reference for a triangle's spinner / cascade u (its first vertex's). */
    public static float patternRef(String mat, float x, float y, float z) {
        double ph = Math.atan2(y - 0.85, Math.abs(z) - 2.75);
        return "spinner".equals(mat) ? (float) (ph / (2 * Math.PI) + (x - 1.6) * 2.4) : (float) (ph * 14 / Math.PI);
    }

    private LinerModelData() {
    }
}
