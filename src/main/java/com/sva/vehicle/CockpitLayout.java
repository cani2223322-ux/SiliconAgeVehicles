package com.sva.vehicle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★14 live instruments: where a model's dials, screens and warning lamps are. The OBJ's instrument pieces are emissive
 * material groups (models/*.groups.txt: gauge, needle, holo, screen, warn...) - one group per material, so a group holds
 * every dial of the dash at once. Here the triangles of those groups are split into connected components (triangles that
 * share a vertex position) and each component is measured: its plane (the least-variance axis of its vertices = the
 * normal, turned toward the pilot's eye), its "up" in that plane and its extents.
 * <ul>
 * <li>DIAL: a "gauge" component that is a round disc / short cylinder (every vertex off the centre lies on one rim
 * radius, the rim goes all the way round) of radius >= DIAL_MIN_R. The export_obj.py dials are 12-gon cylinders of
 * r ~0.06 (n = 72 triangles, a static "needle" box in front of each) and 14-gon discs of r 0.03..0.045; the overhead
 * buttons and lamps of the same colour are flattened 8x12 spheres of r ~0.019 (192 triangles) - not round in this sense.</li>
 * <li>SCREEN: a flat "holo" / "screen" component (thickness under half its smaller side, both sides >= 0.03): the part of
 * it not covered by other instrument pieces in front (the plane's wide back-lit panel runs behind both dials) is cut to
 * the largest free rectangle.</li>
 * <li>LAMP: a small "warn" component (the stall / warning lamp).</li>
 * <li>PANEL (aircraft stage C): a flat named face - 'apanel' (the light plane's glare-shield autopilot panel), 'hsi', 'flapind'
 * (the flap position indicator, round), 'trimind' (the pitch trim strip), 'flapgate' (the flap lever's marks plate on the
 * quadrant, tilted to the pilot: its "up" is forward), 'ovhd' (round 2, D2: the overhead switch panel, client/OverheadPanelSVA)
 * - measured like a screen; client/AvionicsSVA draws each by its name.</li>
 * </ul>
 * Only pieces near the driver's eye (MAX_DIST model units; the dashes are 0.35..0.6 away) and facing it count: the ship's
 * passenger side panels, the holo table and the jet's tail panel stay decoration. No Minecraft classes: the dedicated
 * server's self-test runs it on the real models. Model units throughout (x forward, y up, z right).
 */
public final class CockpitLayout {

    /** The materials an instrument can be made of (anything else is ignored, also as an occluder). */
    static final String[] MATS = {"gauge", "needle", "holo", "screen", "ok", "warn", "dashlite", "apanel", "hsi", "flapind", "trimind", "flapgate",
            "ovhd", "lampbar", "pfd", "mfd", "mfdkeys", "xpdr", "rwr", "swpanel", "tach2", "torque", "hoverind", "radalt"};
    /**
     * Stage C: the named panel materials (veh_real8.py; CockpitInstrumentsSVA draws each by its name); D2 'ovhd' (veh_real10.py).
     * Cockpit wave (veh_real12.py, plan-next §5 / §6 - the light plane's and the helicopter's C+ cockpits; client/GlassCockpitSVA
     * draws them): 'lampbar' (the lamp strip under the glare shield), 'pfd', 'mfd' + 'mfdkeys' (its page keys), 'xpdr' (the
     * transponder), 'rwr' (the radar-warning scope, round), 'swpanel' (the plane's switch panel on the left wall - OverheadPanelSVA),
     * the heli's round 'tach2' (rotor / engine), 'torque', 'hoverind' (the drift vector) and 'radalt'; the heli's overhead panel
     * is 'ovhd' again (3.0 x 5.0 under the roof, every switch on it).
     */
    public static final String[] PANELS = {"apanel", "hsi", "flapind", "trimind", "flapgate", "ovhd", "lampbar", "pfd", "mfd", "mfdkeys", "xpdr", "rwr",
            "swpanel", "tach2", "torque", "hoverind", "radalt"};
    /**
     * Round 2, D2 (★17): the light plane's overhead switch panel - under the canopy's top, forward of the pilot's head; he
     * looks up ~25 deg to it (debug/CockpitCheckSVA checks it from that look, not from the dials' 20 deg down).
     */
    public static final String OVERHEAD = "ovhd";
    /**
     * Stage C: the flap lever's marks plate - low on the quadrant, in no face's plane (screen() doesn't count it over the
     * pedestal's trim strip in front of which its projection falls) and its "up" is forward (the lever's travel).
     */
    public static final String GATE = "flapgate";
    /**
     * Smallest dial radius. The round overhead buttons (~0.019) fail the round test anyway (spheres: their vertices lie
     * between the centre and the rim). Cockpit wave: 0.015 (was 0.025) - the C+ dials are placed by screen angle: the
     * plane's vario (4.2 deg) is r 0.0175, the heli's airspeed (5.8 deg) 0.0208; no other model gains a dial.
     */
    public static final float DIAL_MIN_R = 0.015F;
    /** Farthest an instrument may be from the driver's eye (model units). */
    public static final float MAX_DIST = 0.95F;
    /**
     * Smallest cosine between a piece's normal and the way to the eye: only pieces turned away are left out. (Until the
     * 2026-10 visibility fix the ship's console dials faced down-back, cos 0.13 - debug/CockpitCheckSVA now holds every
     * dial and screen within 40 deg of its pilot's eye.)
     */
    public static final float MIN_FACING = 0.05F;
    /** Smallest screen side (model units). */
    public static final float SCREEN_MIN = 0.03F;
    /**
     * Cockpit wave: the C+ cockpits' faces (veh_real12.py) - each its own flat piece with nothing in front, so measured
     * whole (no free-rectangle cut: the heli's tach lost a sixth of its height to the lamp strip's plane over it) and down
     * to GLASS_MIN a side (the lamp strips are 2.7 deg = 0.020 tall, the MFD keys 1.2 deg = 0.010).
     */
    static final String[] GLASS = {"lampbar", "pfd", "mfd", "mfdkeys", "xpdr", "rwr", "swpanel", "tach2", "torque", "hoverind", "radalt"};
    public static final float GLASS_MIN = 0.008F;

    static boolean glass(String mat) {
        for (String g : GLASS) {
            if (g.equals(mat)) {
                return true;
            }
        }
        return false;
    }

    /** A round dial: centre, normal (toward the eye), in-plane up / right, radius, the face plane (depth along n). */
    public static final class Dial {
        public final float[] c, n, up, right;
        public final float r;
        /** Depth (along n, from c) in front of everything of the dial incl. its static needle. */
        public float front;

        Dial(float[] c, float[] n, float[] up, float[] right, float r, float front) {
            this.c = c;
            this.n = n;
            this.up = up;
            this.right = right;
            this.r = r;
            this.front = front;
        }
    }

    /** A flat screen's free rectangle: its centre, frame, width (along right), height (along up), its surface depth. */
    public static final class Screen {
        public final float[] c, n, up, right;
        public final float w, h, front;
        public final String mat;

        public Screen(String mat, float[] c, float[] n, float[] up, float[] right, float w, float h, float front) {
            this.mat = mat;
            this.c = c;
            this.n = n;
            this.up = up;
            this.right = right;
            this.w = w;
            this.h = h;
            this.front = front;
        }
    }

    /** A warning lamp (a small sphere): centre, radius. */
    public static final class Lamp {
        public final float[] c;
        public final float r;

        Lamp(float[] c, float r) {
            this.c = c;
            this.r = r;
        }
    }

    /** Dials left -> right as the pilot sees them (then top -> bottom). */
    public final List<Dial> dials = new ArrayList<Dial>();
    /** Screens, the largest first. */
    public final List<Screen> screens = new ArrayList<Screen>();
    /** Warning lamps in rows top -> bottom, each row left -> right (sortLamps; CockpitLamps.table follows this order). */
    public final List<Lamp> lamps = new ArrayList<Lamp>();
    /**
     * Cockpit stage 2: small green 'ok' lamps (at most OK_MAX across - the round 0.037 buttons and the heli's overhead
     * panel stay out): the plane's three landing-gear lamps; left -> right.
     */
    public final List<Lamp> okLamps = new ArrayList<Lamp>();
    /** Stage C: the named panels (PANELS), each a face like a screen's free rectangle; at most one of each name. */
    public final List<Screen> panels = new ArrayList<Screen>();
    /** Cockpit stage 2 (★8): the cabin floor's ('carpet') box {x0, x1, y0, y1, z0, z1}, null without one - the floor light strip. */
    public float[] floor;

    /** Largest 'ok' piece that is a lamp (the gear lamps are 0.021..0.027 across, the buttons 0.037). */
    public static final float OK_MAX = 0.032F;
    /** Lamps whose heights differ by no more than this are one row (model units; the rows are 0.04+ apart). */
    public static final float ROW_DY = 0.02F;

    private static final Map<String, CockpitLayout> CACHE = new HashMap<String, CockpitLayout>();

    /**
     * The driver's eye in model units: the first seat (x forward, z right, blocks) / modelScale + modelOffsetX, at
     * seatY + seatDy + 1.12 (the sitting player's eye, see VehicleType's rider-fit notes) / modelScale.
     */
    public static float[] eye(VehicleType t) {
        double[] s = t.seatOffsets[0];
        float k = t.modelScale;
        return new float[] {(float) (s[0] / k) + t.modelOffsetX, (float) ((t.seatY + t.seatDy(0) + 1.12) / k), (float) (s[1] / k)};
    }

    /** The type's layout (parsed once per type, from the jar's models/NAME.obj); an empty layout when it can't be read. */
    public static synchronized CockpitLayout of(VehicleType t) {
        CockpitLayout l = CACHE.get(t.key);
        if (l == null) {
            InputStream in = CockpitLayout.class.getResourceAsStream("/assets/siliconagevehicles/models/" + t.model + ".obj");
            try {
                l = in == null ? new CockpitLayout() : parse(in, eye(t), seatEyes(t));
            } catch (Exception e) {
                l = new CockpitLayout();
            }
            CACHE.put(t.key, l);
        }
        return l;
    }

    /** Every seat's eye in model units (CockpitView.eye). */
    static float[][] seatEyes(VehicleType t) {
        float[][] e = new float[t.seatOffsets.length][];
        for (int i = 0; i < e.length; i++) {
            double[] d = CockpitView.eye(t, i);
            e[i] = new float[] {(float) d[0], (float) d[1], (float) d[2]};
        }
        return e;
    }

    /** Reads an OBJ (closes the stream) and finds its instruments as seen from `eye`. */
    public static CockpitLayout parse(InputStream in, float[] eye) throws java.io.IOException {
        return parse(in, eye, null);
    }

    /**
     * As above; a piece BEHIND the driver's eye (a passengers' panel - the Singular Ship's wall screens by the front row)
     * faces the nearest of `seatEyes` (null: the driver's only) - its normal, "up" and facing test go by that rider's eye.
     */
    public static CockpitLayout parse(InputStream in, float[] eye, float[][] seatEyes) throws java.io.IOException {
        List<float[]> v = new ArrayList<float[]>();
        Map<String, List<int[]>> faces = new HashMap<String, List<int[]>>();
        java.util.Set<String> wanted = new java.util.HashSet<String>(java.util.Arrays.asList(MATS));
        List<int[]> cur = null;
        boolean carpet = false;
        float[] floor = null;
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    v.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ") || line.startsWith("o ") || line.startsWith("usemtl ")) {
                    String g = line.substring(line.indexOf(' ') + 1).trim();
                    carpet = "carpet".equals(g);
                    if (wanted.contains(g)) {
                        cur = faces.get(g);
                        if (cur == null) {
                            cur = new ArrayList<int[]>();
                            faces.put(g, cur);
                        }
                    } else {
                        cur = null;
                    }
                } else if (carpet && line.startsWith("f ")) {
                    for (String q : line.substring(2).trim().split("\\s+")) {
                        float[] p = v.get(Integer.parseInt(q.split("/")[0]) - 1);
                        if (floor == null) {
                            floor = new float[] {p[0], p[0], p[1], p[1], p[2], p[2]};
                        }
                        for (int i = 0; i < 3; i++) {
                            floor[2 * i] = Math.min(floor[2 * i], p[i]);
                            floor[2 * i + 1] = Math.max(floor[2 * i + 1], p[i]);
                        }
                    }
                } else if (cur != null && line.startsWith("f ")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    int[] f = new int[p.length];
                    for (int i = 0; i < p.length; i++) {
                        f[i] = Integer.parseInt(p[i].split("/")[0]) - 1;
                    }
                    cur.add(f);
                }
            }
        } finally {
            r.close();
        }
        List<Comp> comps = new ArrayList<Comp>();
        for (String m : MATS) {
            List<int[]> f = faces.get(m);
            if (f != null) {
                for (List<float[]> pts : components(v, f)) {
                    comps.add(new Comp(m, pts, ownEye(centroid(pts), eye, seatEyes)));
                }
            }
        }
        CockpitLayout l = build(comps, eye);
        l.floor = floor;
        return l;
    }

    /** The eye a piece at c is read from: the driver's, or for a piece behind him the nearest seat's. */
    static float[] ownEye(float[] c, float[] eye, float[][] seatEyes) {
        if (seatEyes == null || c[0] >= eye[0]) {
            return eye;
        }
        float[] best = eye;
        float bd = len(sub(c, eye));
        for (float[] e : seatEyes) {
            float d = len(sub(c, e));
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * Connected components of polygons (vertex index lists into v): polygons sharing a vertex POSITION (rounded to
     * 0.0001) belong together - the exporter writes each piece's vertices once, but two pieces may touch. Each
     * component comes back as its distinct vertex positions.
     */
    public static List<List<float[]>> components(List<float[]> v, List<int[]> faces) {
        int n = faces.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        Map<Long, Integer> owner = new HashMap<Long, Integer>();
        for (int i = 0; i < n; i++) {
            for (int ix : faces.get(i)) {
                Long k = Long.valueOf(key(v.get(ix)));
                Integer o = owner.get(k);
                if (o == null) {
                    owner.put(k, Integer.valueOf(i));
                } else {
                    parent[find(parent, i)] = find(parent, o.intValue());
                }
            }
        }
        Map<Integer, Map<Long, float[]>> groups = new java.util.LinkedHashMap<Integer, Map<Long, float[]>>();
        for (int i = 0; i < n; i++) {
            Integer root = Integer.valueOf(find(parent, i));
            Map<Long, float[]> g = groups.get(root);
            if (g == null) {
                g = new java.util.LinkedHashMap<Long, float[]>();
                groups.put(root, g);
            }
            for (int ix : faces.get(i)) {
                float[] p = v.get(ix);
                g.put(Long.valueOf(key(p)), p);
            }
        }
        List<List<float[]>> out = new ArrayList<List<float[]>>();
        for (Map<Long, float[]> g : groups.values()) {
            out.add(new ArrayList<float[]>(g.values()));
        }
        return out;
    }

    private static long key(float[] p) {
        long x = Math.round(p[0] * 10000) & 0x1FFFFF, y = Math.round(p[1] * 10000) & 0x1FFFFF, z = Math.round(p[2] * 10000) & 0x1FFFFF;
        return x << 42 | y << 21 | z;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** One measured component: centroid, frame (normal toward the eye, up, right), in-frame extents. */
    static final class Comp {
        final String mat;
        final List<float[]> pts;
        final float[] c, n, up, right;
        /** The eye the piece faces (the driver's; a passengers' panel's nearest rider's). */
        final float[] eye;
        float a0 = Float.MAX_VALUE, a1 = -Float.MAX_VALUE, b0 = Float.MAX_VALUE, b1 = -Float.MAX_VALUE, d0 = Float.MAX_VALUE, d1 = -Float.MAX_VALUE;

        Comp(String mat, List<float[]> pts, float[] eye) {
            this.mat = mat;
            this.pts = pts;
            this.eye = eye;
            c = centroid(pts);
            float[] nn = leastAxis(pts, c);
            if (dot(nn, sub(eye, c)) < 0) {
                nn = scale(nn, -1);
            }
            n = nn;
            // stage C: the flap marks plate lies (tilted) on the quadrant - its "up" is forward (the lever's travel)
            up = GATE.equals(mat) ? norm(sub(new float[] {1, 0, 0}, scale(n, n[0]))) : upIn(n, c, eye);
            right = cross(up, n);
            for (float[] p : pts) {
                float[] d = sub(p, c);
                float a = dot(d, right), b = dot(d, up), h = dot(d, n);
                a0 = Math.min(a0, a);
                a1 = Math.max(a1, a);
                b0 = Math.min(b0, b);
                b1 = Math.max(b1, b);
                d0 = Math.min(d0, h);
                d1 = Math.max(d1, h);
            }
        }

        float width() {
            return a1 - a0;
        }

        float height() {
            return b1 - b0;
        }
    }

    /**
     * "Up" on a plane with normal n: the model's +y laid into the plane; for a (nearly) flat-lying or ceiling piece the
     * direction away from the eye (a flat screen's top is its far edge, an overhead dial's top points forward).
     */
    static float[] upIn(float[] n, float[] c, float[] eye) {
        float[] u = sub(new float[] {0, 1, 0}, scale(n, n[1]));
        if (len(u) < 0.45F) {
            float[] away = sub(c, eye);
            away[1] = 0;
            u = sub(away, scale(n, dot(away, n)));
            if (len(u) < 1e-4F) {
                u = sub(new float[] {1, 0, 0}, scale(n, n[0]));
            }
        }
        return norm(u);
    }

    /** The round-dial test (see the class comment) on a component's vertices in its own frame. @return radius, or 0 */
    public static float roundRadius(List<float[]> pts, float[] c, float[] up, float[] right) {
        float rmax = 0;
        float[] rr = new float[pts.size()];
        float[] ang = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            float[] d = sub(pts.get(i), c);
            float a = dot(d, right), b = dot(d, up);
            rr[i] = (float) Math.sqrt(a * a + b * b);
            ang[i] = (float) Math.atan2(b, a);
            rmax = Math.max(rmax, rr[i]);
        }
        if (rmax <= 0) {
            return 0;
        }
        int rim = 0, middle = 0, sectors = 0;
        boolean[] seen = new boolean[16];
        for (int i = 0; i < rr.length; i++) {
            if (rr[i] >= 0.85F * rmax) {
                rim++;
                int s = (int) Math.floor((ang[i] + Math.PI) / (2 * Math.PI) * 16) & 15;
                if (!seen[s]) {
                    seen[s] = true;
                    sectors++;
                }
            } else if (rr[i] > 0.2F * rmax) {
                middle++;
            }
        }
        return rim >= 8 && sectors >= 12 && middle <= rr.length / 10 ? rmax : 0;
    }

    private static CockpitLayout build(List<Comp> comps, float[] eye) {
        CockpitLayout l = new CockpitLayout();
        for (Comp k : comps) {
            float[] toEye = sub(eye, k.c);
            float dist = len(toEye);
            if (dist > MAX_DIST) {
                continue;
            }
            float[] own = sub(k.eye, k.c);
            float facing = dot(k.n, own) / Math.max(1e-6F, len(own));
            if ("gauge".equals(k.mat)) {
                float r = facing >= MIN_FACING ? roundRadius(k.pts, k.c, k.up, k.right) : 0;
                if (r >= DIAL_MIN_R) {
                    l.dials.add(dial(k, r, comps));
                }
            } else if ("holo".equals(k.mat) || "screen".equals(k.mat)) {
                float w = k.width(), h = k.height();
                if (facing >= MIN_FACING && w >= SCREEN_MIN && h >= SCREEN_MIN && k.d1 - k.d0 <= 0.5F * Math.min(w, h)) {
                    Screen s = screen(k, comps);
                    if (s != null) {
                        l.screens.add(s);
                    }
                }
            } else if (isPanel(k.mat)) {
                float w = k.width(), h = k.height(), min = glass(k.mat) ? GLASS_MIN : SCREEN_MIN;
                if (facing >= MIN_FACING && w >= min && h >= min && k.d1 - k.d0 <= 0.5F * Math.min(w, h) && l.panel(k.mat) == null) {
                    Screen s = glass(k.mat) ? whole(k) : screen(k, comps);
                    if (s != null) {
                        l.panels.add(s);
                    }
                }
            } else if ("warn".equals(k.mat) && k.c[0] - eye[0] > 0.05F) {
                float size = Math.max(k.width(), Math.max(k.height(), k.d1 - k.d0));
                if (size <= 0.08F) {
                    l.lamps.add(new Lamp(k.c, size / 2));
                }
            } else if ("ok".equals(k.mat) && k.c[0] - eye[0] > 0.05F) {
                float size = Math.max(k.width(), Math.max(k.height(), k.d1 - k.d0));
                if (size <= OK_MAX) {
                    l.okLamps.add(new Lamp(k.c, size / 2));
                }
            }
        }
        sortLamps(l.lamps);
        sortLamps(l.okLamps);
        dropHidden(l.dials);
        Collections.sort(l.dials, new Comparator<Dial>() {
            @Override
            public int compare(Dial a, Dial b) {
                if (Math.abs(a.c[2] - b.c[2]) > 0.01F) {
                    return a.c[2] < b.c[2] ? -1 : 1;
                }
                return a.c[1] > b.c[1] ? -1 : a.c[1] < b.c[1] ? 1 : 0;
            }
        });
        Collections.sort(l.screens, new Comparator<Screen>() {
            @Override
            public int compare(Screen a, Screen b) {
                return Float.compare(b.w * b.h, a.w * a.h);
            }
        });
        return l;
    }

    /** Stage C: is this one of the named panel materials? */
    public static boolean isPanel(String mat) {
        for (String p : PANELS) {
            if (p.equals(mat)) {
                return true;
            }
        }
        return false;
    }

    /** Stage C: the panel of that name, or null. */
    public Screen panel(String mat) {
        for (Screen s : panels) {
            if (s.mat.equals(mat)) {
                return s;
            }
        }
        return null;
    }

    /**
     * Cockpit stage 2: lamps in rows, top -> bottom (heights within ROW_DY of the row's top lamp), each row left -> right
     * (z up) - the order the per-type role tables (CockpitLamps.table) are written in.
     */
    public static void sortLamps(List<Lamp> lamps) {
        List<Lamp> byY = new ArrayList<Lamp>(lamps);
        Collections.sort(byY, new Comparator<Lamp>() {
            @Override
            public int compare(Lamp a, Lamp b) {
                return Float.compare(b.c[1], a.c[1]);
            }
        });
        lamps.clear();
        int i = 0;
        while (i < byY.size()) {
            float top = byY.get(i).c[1];
            int j = i;
            while (j < byY.size() && top - byY.get(j).c[1] <= ROW_DY) {
                j++;
            }
            List<Lamp> row = new ArrayList<Lamp>(byY.subList(i, j));
            Collections.sort(row, new Comparator<Lamp>() {
                @Override
                public int compare(Lamp a, Lamp b) {
                    return Float.compare(a.c[2], b.c[2]);
                }
            });
            lamps.addAll(row);
            i = j;
        }
    }

    /**
     * Cockpit stage 2: a frame at c facing `eye` - {n (toward the eye), up (the model's up laid into that plane), right} -
     * for the lamps' labels and glow sprites.
     */
    public static float[][] faceFrame(float[] c, float[] eye) {
        float[] n = norm(sub(eye, c));
        float[] up = upIn(n, c, eye);
        return new float[][] {n, up, cross(up, n)};
    }

    /** Lamp i's label room: the distance to the nearest other lamp of the list (model units; 1 when it is alone). */
    public static float labelRoom(List<Lamp> lamps, int i) {
        float best = 1F;
        for (int j = 0; j < lamps.size(); j++) {
            if (j != i) {
                best = Math.min(best, len(sub(lamps.get(j).c, lamps.get(i).c)));
            }
        }
        return best;
    }

    /**
     * Drops a dial that sits behind a bigger one and mostly under it: the buggy's / truck's 14-gon disc (r 0.045) behind
     * the 12-gon dial body (r 0.063) - its centre within R + r/2 of the big dial's axis, behind its face.
     */
    static void dropHidden(List<Dial> dials) {
        for (java.util.Iterator<Dial> it = dials.iterator(); it.hasNext();) {
            Dial s = it.next();
            for (Dial b : dials) {
                if (b == s || b.r <= s.r) {
                    continue;
                }
                float[] d = sub(s.c, b.c);
                float depth = dot(d, b.n);
                float a = dot(d, b.right), u = dot(d, b.up);
                if (depth <= b.front && Math.sqrt(a * a + u * u) < b.r + s.r / 2) {
                    it.remove();
                    break;
                }
            }
        }
    }

    /** A dial: the face plane goes in front of the dial body and of any static needle over it. */
    private static Dial dial(Comp k, float r, List<Comp> comps) {
        float front = k.d1;
        for (Comp o : comps) {
            if (o == k || !"needle".equals(o.mat)) {
                continue;
            }
            float[] d = sub(o.c, k.c);
            float a = dot(d, k.right), b = dot(d, k.up);
            if (a * a + b * b > r * r) {
                continue;
            }
            for (float[] p : o.pts) {
                front = Math.max(front, dot(sub(p, k.c), k.n));
            }
        }
        return new Dial(k.c, k.n, k.up, k.right, r, front);
    }

    /** Cockpit wave: a C+ face measured whole (its own extents, no occluders). */
    private static Screen whole(Comp k) {
        float am = (k.a0 + k.a1) / 2, bm = (k.b0 + k.b1) / 2;
        float[] c = add(k.c, add(scale(k.right, am), scale(k.up, bm)));
        return new Screen(k.mat, c, k.n, k.up, k.right, k.a1 - k.a0, k.b1 - k.b0, k.d1);
    }

    /** A screen cut to its largest rectangle not covered by the other instrument pieces at or in front of its surface. */
    private static Screen screen(Comp k, List<Comp> comps) {
        List<float[]> occ = new ArrayList<float[]>();
        for (Comp o : comps) {
            if (o == k || GATE.equals(o.mat)) {
                continue;
            }
            float a0 = Float.MAX_VALUE, a1 = -Float.MAX_VALUE, b0 = Float.MAX_VALUE, b1 = -Float.MAX_VALUE, front = -Float.MAX_VALUE;
            for (float[] p : o.pts) {
                float[] d = sub(p, k.c);
                a0 = Math.min(a0, dot(d, k.right));
                a1 = Math.max(a1, dot(d, k.right));
                b0 = Math.min(b0, dot(d, k.up));
                b1 = Math.max(b1, dot(d, k.up));
                front = Math.max(front, dot(d, k.n));
            }
            if (front < k.d1 - 0.004F || front > 0.25F) {
                continue;                                      // behind the surface, or a different piece far in front
            }
            if (a1 > k.a0 && a0 < k.a1 && b1 > k.b0 && b0 < k.b1) {
                occ.add(new float[] {a0, a1, b0, b1});
            }
        }
        float[] rect = freeRect(new float[] {k.a0, k.a1, k.b0, k.b1}, occ);
        if (rect == null || rect[1] - rect[0] < SCREEN_MIN || rect[3] - rect[2] < SCREEN_MIN) {
            return null;
        }
        float am = (rect[0] + rect[1]) / 2, bm = (rect[2] + rect[3]) / 2;
        float[] c = add(k.c, add(scale(k.right, am), scale(k.up, bm)));
        return new Screen(k.mat, c, k.n, k.up, k.right, rect[1] - rect[0], rect[3] - rect[2], k.d1);
    }

    /**
     * The largest axis-aligned rectangle inside `box` {a0, a1, b0, b1} that overlaps none of `occ` (same layout):
     * candidates span between the box's and the occluders' edges - a handful of pieces, so the brute force is tiny.
     * @return {a0, a1, b0, b1}, or null when nothing is free
     */
    public static float[] freeRect(float[] box, List<float[]> occ) {
        List<Float> as = new ArrayList<Float>(), bs = new ArrayList<Float>();
        as.add(Float.valueOf(box[0]));
        as.add(Float.valueOf(box[1]));
        bs.add(Float.valueOf(box[2]));
        bs.add(Float.valueOf(box[3]));
        for (float[] o : occ) {
            for (int i = 0; i < 2; i++) {
                if (o[i] > box[0] && o[i] < box[1]) {
                    as.add(Float.valueOf(o[i]));
                }
                if (o[2 + i] > box[2] && o[2 + i] < box[3]) {
                    bs.add(Float.valueOf(o[2 + i]));
                }
            }
        }
        float[] best = null;
        float bestArea = 0;
        for (int i = 0; i < as.size(); i++) {
            for (int j = 0; j < as.size(); j++) {
                float x0 = as.get(i).floatValue(), x1 = as.get(j).floatValue();
                if (x1 <= x0) {
                    continue;
                }
                for (int p = 0; p < bs.size(); p++) {
                    for (int q = 0; q < bs.size(); q++) {
                        float y0 = bs.get(p).floatValue(), y1 = bs.get(q).floatValue();
                        float area = (x1 - x0) * (y1 - y0);
                        if (y1 <= y0 || area <= bestArea) {
                            continue;
                        }
                        boolean free = true;
                        for (float[] o : occ) {
                            if (o[1] > x0 + 1e-5F && o[0] < x1 - 1e-5F && o[3] > y0 + 1e-5F && o[2] < y1 - 1e-5F) {
                                free = false;
                                break;
                            }
                        }
                        if (free) {
                            bestArea = area;
                            best = new float[] {x0, x1, y0, y1};
                        }
                    }
                }
            }
        }
        return best;
    }

    // ---- small vector maths

    static float[] centroid(List<float[]> pts) {
        float[] c = new float[3];
        for (float[] p : pts) {
            c[0] += p[0];
            c[1] += p[1];
            c[2] += p[2];
        }
        return scale(c, 1F / Math.max(1, pts.size()));
    }

    /** The unit axis of least spread of the points about c (the normal of a flat piece): Jacobi on the covariance. */
    static float[] leastAxis(List<float[]> pts, float[] c) {
        double[][] m = new double[3][3];
        for (float[] p : pts) {
            double[] d = {p[0] - c[0], p[1] - c[1], p[2] - c[2]};
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    m[i][j] += d[i] * d[j];
                }
            }
        }
        double[][] vec = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int sweep = 0; sweep < 30; sweep++) {
            double off = Math.abs(m[0][1]) + Math.abs(m[0][2]) + Math.abs(m[1][2]);
            if (off < 1e-14) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(m[p][q]) < 1e-18) {
                        continue;
                    }
                    double theta = (m[q][q] - m[p][p]) / (2 * m[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) {
                        t = 1;
                    }
                    double cs = 1 / Math.sqrt(t * t + 1), sn = t * cs;
                    for (int k = 0; k < 3; k++) {                      // m = J^T m J
                        double mkp = m[k][p], mkq = m[k][q];
                        m[k][p] = cs * mkp - sn * mkq;
                        m[k][q] = sn * mkp + cs * mkq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double mpk = m[p][k], mqk = m[q][k];
                        m[p][k] = cs * mpk - sn * mqk;
                        m[q][k] = sn * mpk + cs * mqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = vec[k][p], vkq = vec[k][q];
                        vec[k][p] = cs * vkp - sn * vkq;
                        vec[k][q] = sn * vkp + cs * vkq;
                    }
                }
            }
        }
        int least = 0;
        for (int i = 1; i < 3; i++) {
            if (m[i][i] < m[least][least]) {
                least = i;
            }
        }
        return norm(new float[] {(float) vec[0][least], (float) vec[1][least], (float) vec[2][least]});
    }

    public static float[] sub(float[] a, float[] b) {
        return new float[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static float[] add(float[] a, float[] b) {
        return new float[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static float[] scale(float[] a, float k) {
        return new float[] {a[0] * k, a[1] * k, a[2] * k};
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float[] cross(float[] a, float[] b) {
        return new float[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    public static float len(float[] a) {
        return (float) Math.sqrt(dot(a, a));
    }

    static float[] norm(float[] a) {
        float l = len(a);
        return l < 1e-9F ? new float[] {0, 1, 0} : scale(a, 1 / l);
    }
}
