package com.sva.debug;

import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.AnimParts;
import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.CockpitView;
import com.sva.vehicle.VehicleType;

/**
 * Fix 2026-10 (the user's first-person screenshots: the heli's glare shield hid the upper half of the pilot's dials, the
 * Singular Ship's dials lay face-up under the console hoods, the buggy's steering wheel sank into the scuttle panel and
 * its hub covered the cluster): can the pilot actually READ his instruments, and do the moving controls stay clear of
 * the cabin? Pure math over the OBJ (ViewCheckSVA's mesh and rays), no Minecraft classes - the dedicated server's
 * self-test runs it for every type.
 * <p>
 * INSTRUMENTS (CockpitLayout's dials and screens) - each is checked from the seat it belongs to: the driver's, or on an
 * aircraft with a co-pilot beside him (seat 1 level with seat 0) the co-pilot's when it sits on his side; a panel behind
 * the driver (the ship's passenger wall screen) the nearest rider's, who turns his head to it (no view cone). The camera
 * is vanilla's (0.1 behind the eye) looking LOOK_PITCH down, straight ahead:
 * <ul>
 * <li>visible: sample points over the face (dials: the centre, rings at 0.5 and 0.9 r; screens: a 5 x 5 grid over 90 %
 * of the free rectangle), just in front of its surface, are ray-cast to the camera - at most MAX_BLOCKED of them may
 * meet other opaque geometry on the way (the glass is see-through, the shell cut in the cockpit windows as drawn);</li>
 * <li>facing: the angle between the face normal and the way to the eye at most MAX_ANGLE deg (not seen edge-on);</li>
 * <li>in view: every sample within VIEW_DOWN deg under / VIEW_UP over the horizon and VIEW_SIDE to the side (on the
 * screen while he looks 20..25 deg down).</li>
 * </ul>
 * CONTROLS (AnimParts CONTROL rows): over the input range (each input at -1, -0.5, 0, 0.5, 1, every combination) the
 * part's moved triangles must not cut any other opaque triangle - exact triangle / triangle intersection (an edge of one
 * piercing the other's interior). Contacts at the part's mount are allowed: within MOUNT_R of its pivot, or within
 * SHAFT_R of its first turning axis (the steering column, the yoke's shaft into the panel) - both carried along by the
 * part's slide (the yoke pulled back).
 */
public final class CockpitCheckSVA {

    /** Largest share of an instrument's samples that may be hidden. */
    public static final double MAX_BLOCKED = 0.10;
    /** Largest angle (deg) between an instrument's face normal and the way to the eye. */
    public static final double MAX_ANGLE = 40;
    /**
     * The look the instruments are checked in (deg down) and the view cone (deg from the horizon / straight ahead): every
     * sample on the 70 deg (16:9: +-51 deg wide) screen while the pilot looks 20..25 deg down - at most 58 deg under the
     * horizon, 10 over it, 50 to the side.
     */
    public static final double LOOK_PITCH = 20, VIEW_DOWN = 58, VIEW_UP = 10, VIEW_SIDE = 50;
    /** Mount allowances (model units): around the pivot, around the first turning axis. */
    public static final double MOUNT_R = 0.06, SHAFT_R = 0.035;
    /** Samples lie this far (model units) in front of the instrument's surface. */
    private static final double LIFT = 0.003;
    /** Offline tracing (null in the game): every instrument sample {x, y, z, hidden 0/1}. */
    static List<double[]> trace;

    private CockpitCheckSVA() {
    }

    /** One instrument's result. */
    public static final class Inst {
        public String what;
        public int seat;
        /** Share of the samples hidden, the facing angle (deg), the worst sample's angles: below the horizon, above, aside. */
        public double blocked, angle, down, up, side;
        /** The materials hiding samples (for the logs). */
        public final List<String> by = new ArrayList<String>();

        public boolean ok() {
            return blocked <= MAX_BLOCKED && angle <= MAX_ANGLE && down <= VIEW_DOWN && up <= VIEW_UP && side <= VIEW_SIDE;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%s (seat %d): hidden %.0f %%, facing %.0f deg, view %.0f down / %.0f up / %.0f aside%s%s",
                    what, Integer.valueOf(seat), Double.valueOf(blocked * 100), Double.valueOf(angle), Double.valueOf(down), Double.valueOf(up),
                    Double.valueOf(side), by.isEmpty() ? "" : " by " + summary(by), ok() ? "" : " - FAIL");
        }
    }

    /** One control's result: the poses checked and the worst pose's cut triangles (other part's material counts). */
    public static final class Ctl {
        public String what;
        public int poses, worstHits;
        public String worstPose = "";
        public final List<String> by = new ArrayList<String>();
        /** Where the worst pose's first cut is (model units), for the logs. */
        public double[] where;

        public boolean ok() {
            return worstHits == 0;
        }

        @Override
        public String toString() {
            return what + ": " + poses + " poses, " + (ok() ? "clear" : worstHits + " cuts at " + worstPose + " into " + summary(by)
                    + String.format(java.util.Locale.ROOT, " (first at %.3f %.3f %.3f)", Double.valueOf(where[0]), Double.valueOf(where[1]),
                            Double.valueOf(where[2])) + " - FAIL");
        }
    }

    /** "mat x n, ..." of a list of names. */
    static String summary(List<String> l) {
        java.util.Map<String, Integer> m = new java.util.TreeMap<String, Integer>();
        for (String s : l) {
            Integer n = m.get(s);
            m.put(s, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : m.entrySet()) {
            b.append(b.length() == 0 ? "" : ", ").append(e.getKey()).append(" x").append(e.getValue());
        }
        return b.toString();
    }

    /**
     * The seat an instrument at c belongs to: the driver (0); on an aircraft whose seat 1 sits level with seat 0 (the
     * co-pilot) seat 1 when c is nearer to his eye sideways; a panel BEHIND the driver's eye (the ship's passenger wall
     * screen) belongs to the nearest rider.
     */
    public static int seatOf(VehicleType t, float[] c) {
        double[] e0 = CockpitView.eye(t, 0);
        if (c[0] < e0[0] && t.seatOffsets.length > 1) {
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i < t.seatOffsets.length; i++) {
                double[] e = CockpitView.eye(t, i);
                double d = (c[0] - e[0]) * (c[0] - e[0]) + (c[1] - e[1]) * (c[1] - e[1]) + (c[2] - e[2]) * (c[2] - e[2]);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }
        if (!t.movement.flies || t.seatOffsets.length < 2 || Math.abs(t.seatOffsets[1][0] - t.seatOffsets[0][0]) > 0.05) {
            return 0;
        }
        double[] e1 = CockpitView.eye(t, 1);
        return Math.abs(c[2] - e1[2]) < Math.abs(c[2] - e0[2]) ? 1 : 0;
    }

    /** A panel behind the driver's eye (a passenger's): no default look - he turns his head to it (no view cone). */
    static boolean passenger(VehicleType t, float[] c) {
        return c[0] < CockpitView.eye(t, 0)[0] && t.seatOffsets.length > 1;
    }

    /** Every dial and screen of the type's layout, checked (see the class comment). */
    public static List<Inst> instruments(VehicleType t, ViewCheckSVA.Mesh m) {
        List<Inst> out = new ArrayList<Inst>();
        CockpitLayout l = CockpitLayout.of(t);
        int[] all = new int[m.tris.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = i;
        }
        for (int i = 0; i < l.dials.size(); i++) {
            CockpitLayout.Dial d = l.dials.get(i);
            List<double[]> pts = new ArrayList<double[]>();
            double depth = d.front + LIFT;
            pts.add(at(d.c, d.n, d.right, d.up, 0, 0, depth));
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * 2 * k / 8 + Math.PI / 8;
                pts.add(at(d.c, d.n, d.right, d.up, Math.cos(a) * d.r * 0.5, Math.sin(a) * d.r * 0.5, depth));
            }
            for (int k = 0; k < 16; k++) {
                double a = Math.PI * 2 * k / 16;
                pts.add(at(d.c, d.n, d.right, d.up, Math.cos(a) * d.r * 0.9, Math.sin(a) * d.r * 0.9, depth));
            }
            out.add(check(t, m, all, "dial " + i, d.c, d.n, pts));
        }
        for (int i = 0; i < l.screens.size(); i++) {
            CockpitLayout.Screen s = l.screens.get(i);
            List<double[]> pts = new ArrayList<double[]>();
            for (int a = -2; a <= 2; a++) {
                for (int b = -2; b <= 2; b++) {
                    pts.add(at(s.c, s.n, s.right, s.up, a * 0.225 * s.w, b * 0.225 * s.h, s.front + LIFT));
                }
            }
            out.add(check(t, m, all, String.format(java.util.Locale.ROOT, "screen %d (%.3f x %.3f)", Integer.valueOf(i), Double.valueOf(s.w),
                    Double.valueOf(s.h)), s.c, s.n, pts));
        }
        // aircraft stage C: the named panels (AP panel, HSI, flap / trim indicators, the flap gate) - as screens
        for (CockpitLayout.Screen s : l.panels) {
            List<double[]> pts = new ArrayList<double[]>();
            for (int a = -2; a <= 2; a++) {
                for (int b = -2; b <= 2; b++) {
                    pts.add(at(s.c, s.n, s.right, s.up, a * 0.225 * s.w, b * 0.225 * s.h, s.front + LIFT));
                }
            }
            // round 2, D2: the overhead panel is read looking up at it (the head turned to it - no view cone), as a
            // passengers' panel is; hidden samples and the facing are checked the same
            out.add(check(t, m, all, String.format(java.util.Locale.ROOT, "panel %s (%.3f x %.3f)", s.mat, Double.valueOf(s.w), Double.valueOf(s.h)),
                    s.c, s.n, pts, CockpitLayout.OVERHEAD.equals(s.mat)));
        }
        return out;
    }

    private static double[] at(float[] c, float[] n, float[] right, float[] up, double a, double b, double depth) {
        double[] p = new double[3];
        for (int k = 0; k < 3; k++) {
            p[k] = c[k] + right[k] * a + up[k] * b + n[k] * depth;
        }
        return p;
    }

    private static Inst check(VehicleType t, ViewCheckSVA.Mesh m, int[] all, String what, float[] c, float[] n, List<double[]> pts) {
        return check(t, m, all, what, c, n, pts, false);
    }

    /** turned: the pilot turns his head to it (the overhead panel) - looked at straight, no view cone. */
    private static Inst check(VehicleType t, ViewCheckSVA.Mesh m, int[] all, String what, float[] c, float[] n, List<double[]> pts, boolean turned) {
        Inst r = new Inst();
        r.what = what;
        r.seat = turned ? 0 : seatOf(t, c);
        double[] e = CockpitView.eye(t, r.seat);
        boolean pass = turned || passenger(t, c);
        double[] look = pass ? ViewCheckSVA.norm(ViewCheckSVA.sub(new double[] {c[0], c[1], c[2]}, e)) : ViewCheckSVA.dir(0, LOOK_PITCH);
        double[] cam = ViewCheckSVA.cameraAt(t, e, look);
        double[] toEye = ViewCheckSVA.norm(ViewCheckSVA.sub(e, new double[] {c[0], c[1], c[2]}));
        r.angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, n[0] * toEye[0] + n[1] * toEye[1] + n[2] * toEye[2]))));
        float[][] w = t.openCab ? null : t.ownViewCuts();
        double s = t.modelScale;
        int hidden = 0;
        for (double[] p : pts) {
            double[] d = ViewCheckSVA.sub(p, cam);
            double dist = ViewCheckSVA.len(d);
            double[] u = ViewCheckSVA.norm(d);
            double hit = ViewCheckSVA.firstHit(m, all, w, s, cam, u, u, CockpitView.EYE_CLIP);
            boolean hid = hit < dist * s - 1e-4;
            if (hid) {
                hidden++;
                r.by.add(m.mat[ViewCheckSVA.lastHit]);
            }
            if (trace != null) {
                trace.add(new double[] {p[0], p[1], p[2], hid ? 1 : 0});
            }
            if (pass) {
                continue;
            }
            double horiz = Math.sqrt(u[0] * u[0] + u[2] * u[2]);
            double el = Math.toDegrees(Math.atan2(u[1], horiz));
            r.down = Math.max(r.down, -el);
            r.up = Math.max(r.up, el);
            r.side = Math.max(r.side, Math.abs(Math.toDegrees(Math.atan2(u[2], u[0]))));
        }
        r.blocked = hidden / (double) pts.size();
        return r;
    }

    // ------------------------------------------------------------------ controls

    /** Every CONTROL part of the type, swept over its inputs (see the class comment). */
    public static List<Ctl> controls(VehicleType t, ViewCheckSVA.Mesh m) {
        List<Ctl> out = new ArrayList<Ctl>();
        for (AnimParts.Def d : AnimParts.of(t.model)) {
            if (d.kind != AnimParts.CONTROL) {
                continue;
            }
            List<Integer> own = new ArrayList<Integer>();
            boolean[] mine = new boolean[m.tris.length];
            for (int i = 0; i < m.tris.length; i++) {
                float[] q = m.tris[i];
                if (d.holds(m.mat[i], (q[0] + q[3] + q[6]) / 3, (q[1] + q[4] + q[7]) / 3, (q[2] + q[5] + q[8]) / 3)) {
                    own.add(Integer.valueOf(i));
                    mine[i] = true;
                }
            }
            int[] ins = {d.in1, d.in2, d.inMove};
            List<Integer> inputs = new ArrayList<Integer>();
            for (int in : ins) {
                if (in >= 0 && !inputs.contains(Integer.valueOf(in))) {
                    inputs.add(Integer.valueOf(in));
                }
            }
            double[] steps = {-1, -0.5, 0, 0.5, 1};
            int poses = 1;
            for (int k = 0; k < inputs.size(); k++) {
                poses *= steps.length;
            }
            // every pose's moved triangles, and their common box (+ a margin) to pick the other triangles near them once
            float[][][] moved = new float[poses][][];
            double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
            String[] names = new String[poses];
            float[][] vals = new float[poses][];
            for (int p = 0; p < poses; p++) {
                float[] val = new float[AnimParts.INPUTS];
                vals[p] = val;
                StringBuilder nm = new StringBuilder();
                int code = p;
                for (int k = 0; k < inputs.size(); k++) {
                    double v = steps[code % steps.length];
                    code /= steps.length;
                    val[inputs.get(k).intValue()] = (float) v;
                    nm.append(nm.length() == 0 ? "" : " ").append(INPUT_NAMES[inputs.get(k).intValue()]).append(' ').append(v);
                }
                names[p] = nm.toString();
                moved[p] = new float[own.size()][];
                for (int j = 0; j < own.size(); j++) {
                    float[] q = m.tris[own.get(j).intValue()], o = new float[9];
                    for (int v = 0; v < 3; v++) {
                        double[] r = pose(d, val, q[v * 3], q[v * 3 + 1], q[v * 3 + 2]);
                        for (int a = 0; a < 3; a++) {
                            o[v * 3 + a] = (float) r[a];
                            lo[a] = Math.min(lo[a], r[a]);
                            hi[a] = Math.max(hi[a], r[a]);
                        }
                    }
                    moved[p][j] = o;
                }
            }
            List<Integer> cand = new ArrayList<Integer>();
            for (int i = 0; i < m.tris.length; i++) {
                if (!mine[i] && boxOverlap(m.tris[i], lo, hi, 0.002)) {
                    cand.add(Integer.valueOf(i));
                }
            }
            Ctl c = new Ctl();
            c.what = d.name;
            c.poses = poses;
            for (int p = 0; p < poses; p++) {
                List<String> by = new ArrayList<String>();
                double[] first = null;
                double[] at = pose(d, vals[p], d.pivot[0], d.pivot[1], d.pivot[2]);    // the pivot moves with the part
                for (float[] a : moved[p]) {
                    for (Integer ci : cand) {
                        float[] b = m.tris[ci.intValue()];
                        if (bboxOverlap(a, b) && cuts(a, b, d, at)) {
                            by.add(m.mat[ci.intValue()]);
                            if (first == null) {
                                first = lastCut.clone();
                            }
                        }
                    }
                }
                if (by.size() > c.worstHits) {
                    c.worstHits = by.size();
                    c.worstPose = names[p];
                    c.where = first;
                    c.by.clear();
                    c.by.addAll(by);
                }
            }
            out.add(c);
        }
        return out;
    }

    static final String[] INPUT_NAMES = {"steer", "pitch", "roll", "yaw", "lift", "throttle", "flaps", "trim"};

    /** A point of the part at the inputs `val` (indexed by AnimParts.STEER..THROTTLE): RenderVehicleSVA's transform. */
    public static double[] pose(AnimParts.Def d, float[] val, double x, double y, double z) {
        double[] p = {x - d.pivot[0], y - d.pivot[1], z - d.pivot[2]};
        if (d.in2 >= 0) {
            p = rot(p, d.axis2, d.ang2 * val[d.in2]);
        }
        if (d.in1 >= 0) {
            p = rot(p, d.axis1, d.ang1 * val[d.in1]);
        }
        double mx = 0, my = 0, mz = 0;
        if (d.inMove >= 0) {
            mx = d.move[0] * val[d.inMove];
            my = d.move[1] * val[d.inMove];
            mz = d.move[2] * val[d.inMove];
        }
        return new double[] {p[0] + d.pivot[0] + mx, p[1] + d.pivot[1] + my, p[2] + d.pivot[2] + mz};
    }

    /** Rodrigues: p turned by deg about the unit axis k (right-hand, as glRotatef). */
    static double[] rot(double[] p, float[] k, double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        double kd = k[0] * p[0] + k[1] * p[1] + k[2] * p[2];
        double cx = k[1] * p[2] - k[2] * p[1], cy = k[2] * p[0] - k[0] * p[2], cz = k[0] * p[1] - k[1] * p[0];
        return new double[] {p[0] * c + cx * s + k[0] * kd * (1 - c), p[1] * c + cy * s + k[1] * kd * (1 - c), p[2] * c + cz * s + k[2] * kd * (1 - c)};
    }

    private static boolean boxOverlap(float[] t, double[] lo, double[] hi, double m) {
        for (int a = 0; a < 3; a++) {
            double tl = Math.min(t[a], Math.min(t[a + 3], t[a + 6])), th = Math.max(t[a], Math.max(t[a + 3], t[a + 6]));
            if (th < lo[a] - m || tl > hi[a] + m) {
                return false;
            }
        }
        return true;
    }

    private static boolean bboxOverlap(float[] a, float[] b) {
        for (int k = 0; k < 3; k++) {
            float al = Math.min(a[k], Math.min(a[k + 3], a[k + 6])), ah = Math.max(a[k], Math.max(a[k + 3], a[k + 6]));
            float bl = Math.min(b[k], Math.min(b[k + 3], b[k + 6])), bh = Math.max(b[k], Math.max(b[k + 3], b[k + 6]));
            if (ah < bl || bh < al) {
                return false;
            }
        }
        return true;
    }

    /**
     * Do the triangles cut each other away from the part's mount? The intersection points are where an edge of one
     * pierces the other's interior (touching faces, shared edges and coplanar contact do not count); a cut counts when
     * one of them lies off the mount (MOUNT_R round the pivot, SHAFT_R round the first turning axis).
     */
    /** The last cut point found (the logs). */
    private static final double[] lastCut = new double[3];

    static boolean cuts(float[] a, float[] b, AnimParts.Def d, double[] pivot) {
        double[] p = lastCut;
        for (int e = 0; e < 3; e++) {
            if (pierce(a, e, b, p) && !mount(d, pivot, p)) {
                return true;
            }
            if (pierce(b, e, a, p) && !mount(d, pivot, p)) {
                return true;
            }
        }
        return false;
    }

    /** Does edge e of triangle s pierce the interior of triangle t? The point goes to out. */
    static boolean pierce(float[] s, int e, float[] t, double[] out) {
        int i = e * 3, j = ((e + 1) % 3) * 3;
        double ox = s[i], oy = s[i + 1], oz = s[i + 2];
        double dx = s[j] - ox, dy = s[j + 1] - oy, dz = s[j + 2] - oz;
        double e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2];
        double e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
        double px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        double det = e1x * px + e1y * py + e1z * pz;
        double scale = Math.sqrt(dx * dx + dy * dy + dz * dz) * Math.sqrt((e1y * e2z - e1z * e2y) * (e1y * e2z - e1z * e2y)
                + (e1z * e2x - e1x * e2z) * (e1z * e2x - e1x * e2z) + (e1x * e2y - e1y * e2x) * (e1x * e2y - e1y * e2x));
        if (Math.abs(det) <= 1e-3 * scale || scale == 0) {
            return false;                                            // parallel / coplanar: a contact, not a cut
        }
        double inv = 1 / det;
        double sx = ox - t[0], sy = oy - t[1], sz = oz - t[2];
        double u = (sx * px + sy * py + sz * pz) * inv;
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double v = (dx * qx + dy * qy + dz * qz) * inv;
        double k = (e2x * qx + e2y * qy + e2z * qz) * inv;
        final double eps = 1e-4;
        if (u <= eps || v <= eps || u + v >= 1 - eps || k <= eps || k >= 1 - eps) {
            return false;
        }
        out[0] = ox + dx * k;
        out[1] = oy + dy * k;
        out[2] = oz + dz * k;
        return true;
    }

    /** Is p on the part's mount (round its pivot - where the pose moved it - or its first turning axis)? */
    static boolean mount(AnimParts.Def d, double[] pivot, double[] p) {
        double x = p[0] - pivot[0], y = p[1] - pivot[1], z = p[2] - pivot[2];
        if (x * x + y * y + z * z <= MOUNT_R * MOUNT_R) {
            return true;
        }
        if (d.in1 >= 0 && d.axis1 != null) {
            double k = x * d.axis1[0] + y * d.axis1[1] + z * d.axis1[2];
            double rx = x - d.axis1[0] * k, ry = y - d.axis1[1] * k, rz = z - d.axis1[2] * k;
            return rx * rx + ry * ry + rz * rz <= SHAFT_R * SHAFT_R;
        }
        return false;
    }
}
