package com.sva.vehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ★10: the animated parts cut out of the OBJ models - wheels, landing gear legs and control surfaces - as pure data
 * (server-safe: the self-test loads the OBJ text and checks every box catches its triangles). ObjModelSVA turns each
 * Def into a Part when a model loads: the triangles of `groups` whose centre lies in [min, max] (model units: x forward,
 * y up, z right; export_obj.py) go to "#name/material" groups that RenderVehicleSVA draws moved. A `cut` part instead
 * CLIPS the triangles crossing its box (clipBox): the coarse wings / fins (the jet's wing is 12 triangles, the plane's fin
 * too) have no edge where an aileron or a rudder ends, so the box itself is the hinge line and the span limit.
 * Every number below was measured from the exported OBJ (connected components of the material, see the comments).
 * The table goes in FRONT of ObjModelSVA's older parts (rotors, trailer, bore): first match wins, so the truck's trailer
 * wheels are wheels (drawn with the trailer only) and not part of the plain trailer part.
 */
public final class AnimParts {

    /** Part kinds (the same numbers as ObjModelSVA.Part's - ROTOR 0 .. RINGS 5 are the older ones). */
    public static final int WHEEL = 6, GEAR = 7, SURFACE = 8;
    /** SURFACE controls. */
    public static final int AILERON = 0, ELEVATOR = 1, RUDDER = 2;
    /** Axes. */
    public static final int X = 0, Y = 1, Z = 2;

    /** One animated part. */
    public static final class Def {
        public final String model, name;
        public final int kind;
        public final String[] groups;
        public final float[] min, max;
        /** WHEEL: the axle centre (spin about `axis`); SURFACE: a point on the hinge line (turn about `axis`). */
        public final float[] pivot;
        public int axis = Z;
        /** WHEEL: rolling radius (model units; the tyre's outer radius) - 0: does not spin (a caliper that only steers). */
        public float radius;
        /** WHEEL: a front wheel - turned about y through the pivot by the steering angle. */
        public boolean steer;
        /** Drawn only while a trailer is hitched (the truck's trailer wheels). */
        public boolean trailer;
        /** Retracting gear (WHEEL or GEAR): at gear position 1 turned by gearAngle about gearAxis through gearPivot, then lifted by gearLift. */
        public float[] gearPivot;
        public int gearAxis = Z;
        public float gearAngle, gearLift;
        /** SURFACE: which control and the sign (+1: positive command = positive angle about `axis`). */
        public int control;
        public float gain = 1F;
        /** Clip the triangles crossing the box (instead of the centre test). */
        public boolean cut;
        /** Self-test: at least this many triangles must fall in (cut: be clipped into) the box. */
        public int minTris = 8;

        Def(String model, String name, int kind, String[] groups, float[] min, float[] max, float[] pivot) {
            this.model = model;
            this.name = name;
            this.kind = kind;
            this.groups = groups;
            this.min = min;
            this.max = max;
            this.pivot = pivot;
        }

        /** Does this part take triangles of material `group`? */
        public boolean takes(String group) {
            for (String g : groups) {
                if (g.equals(group)) {
                    return true;
                }
            }
            return false;
        }

        /** The centre test (non-cut parts). */
        public boolean holds(String group, float x, float y, float z) {
            return takes(group) && x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
        }

        /** The triangle's bounding box overlaps the part's box (a cut part clips it). */
        public boolean overlaps(float[] a, float[] b, float[] c) {
            for (int k = 0; k < 3; k++) {
                float lo = Math.min(a[k], Math.min(b[k], c[k])), hi = Math.max(a[k], Math.max(b[k], c[k]));
                if (hi <= min[k] || lo >= max[k]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final Map<String, List<Def>> TABLE = new HashMap<String, List<Def>>();
    private static final List<Def> ALL = new ArrayList<Def>();

    private static final String[] TYRE = {"rubber", "chrome", "disc", "accent", "dark"};

    static {
        // ---- buggy.obj: four wheels (tyre rubber r 0.488 + chrome rims + brake disc + dark spokes + accent hub cap) at
        // x -1.05 (rear) / 1.075 (front), y 0.488, z +-0.781 (rim faces z 0.649..0.914); the spare on the back (x -1.77,
        // y 1.08) stays. The front calipers (x 0.98..1.19, y 0.65..0.77) turn with the steering but do not spin.
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            wheel("buggy", "wheelF" + side, TYRE, 1.075F, 0.488F, s * 0.781F, 0.5F, 0.5F, 0.6F, 0.96F, s, 0.488F).steer = true;
            wheel("buggy", "wheelB" + side, TYRE, -1.05F, 0.488F, s * 0.781F, 0.5F, 0.5F, 0.6F, 0.96F, s, 0.488F);
            Def cal = box("buggy", "caliperF" + side, WHEEL, new String[] {"caliper"}, 0.95F, 0.62F, 0.72F, 1.22F, 0.8F, 0.82F, s,
                    new float[] {1.075F, 0.488F, s * 0.781F});
            cal.steer = true;
        }
        // ---- truck.obj: the crawler's six road wheels per side (steel + accent hub, r 0.175) at x -0.631, -0.006, 0.619,
        // 1.244, 1.869, 2.494, y 0.365, z +-0.981 (inside the tread loop, |z| 0.83..1.11). The trailer's two wheels (as the
        // buggy's, r 0.39) at x -2.256, y 0.39, z +-0.781 - drawn with the trailer only; its calipers stay with the trailer.
        float[] road = {-0.631F, -0.006F, 0.619F, 1.244F, 1.869F, 2.494F};
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < road.length; i++) {
                wheel("truck", "road" + (s > 0 ? "R" : "L") + i, new String[] {"steel", "accent"}, road[i], 0.365F, s * 0.981F,
                        0.19F, 0.19F, 0.8F, 1.16F, s, 0.175F).minTris = 40;
            }
            wheel("truck", "trailerWheel" + (s > 0 ? "R" : "L"), TYRE, -2.256F, 0.39F, s * 0.781F, 0.45F, 0.41F, 0.62F, 0.95F, s, 0.39F)
                    .trailer = true;
        }
        // ---- drill.obj: five road wheels per side (steel, r 0.194) at x -2.375, -1.656, -0.938, -0.219, 0.5, y 0.281,
        // z +-1.006 (the tread runs at |z| 0.85..1.15 around them)
        float[] droad = {-2.375F, -1.656F, -0.938F, -0.219F, 0.5F};
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < droad.length; i++) {
                wheel("drill", "road" + (s > 0 ? "R" : "L") + i, new String[] {"steel"}, droad[i], 0.281F, s * 1.006F, 0.21F, 0.21F,
                        0.82F, 1.19F, s, 0.194F).minTris = 40;
            }
        }
        // ---- plane.obj: fixed-looking main gear in spats - wheels (r 0.144) at x 0.547, y 0.144, z +-0.375 inside the paint2
        // spats (x 0.359..0.734, y 0..0.325, |z| 0.275..0.475) on a steel strut from the fuselage side (strut top y 0.457,
        // |z| 0.17). Retracts by folding back 85 deg about z through the strut top: the spat ends lying along the fuselage.
        // The tail wheel (r 0.084, x -1.953, y 0.6) only spins. Control surfaces (cut): ailerons = the outer wing trailing
        // edge (wing x 0.297 TE .. 1.047 LE, y 0.88..1.07, span to 2.5 with the paint2 tip cap from 2.375), elevator halves
        // = the tailplane's trailing edge (x -2.078..-1.641, y 0.656..0.731, |z| 0.062..0.812), rudder = the fin's aft
        // part (fin x -2.328..-1.641, y 0.756..1.569, |z| <= 0.038).
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            Def w = wheel("plane", "gearWheel" + side, new String[] {"rubber", "chrome", "dark", "accent"}, 0.547F, 0.144F, s * 0.375F,
                    0.16F, 0.16F, 0.32F, 0.43F, s, 0.144F);
            gear(w, new float[] {0.547F, 0.457F, s * 0.17F}, Z, -85F, 0F);
            Def leg = box("plane", "gearSpat" + side, GEAR, new String[] {"paint2"}, 0.35F, -0.01F, 0.26F, 0.75F, 0.34F, 0.48F, s,
                    new float[] {0.547F, 0.457F, s * 0.17F});
            gear(leg, leg.pivot, Z, -85F, 0F);
            // the strut (x 0.522..0.572) - the wing brace's foot right next to it (x <= 0.446, y 0.42..0.47) stays
            Def strut = box("plane", "gearStrut" + side, GEAR, new String[] {"steel"}, 0.48F, 0.1F, 0.16F, 0.6F, 0.47F, 0.41F, s,
                    new float[] {0.547F, 0.457F, s * 0.17F});
            gear(strut, strut.pivot, Z, -85F, 0F);
            surface("plane", "aileron" + side, new String[] {"paint"}, 0.29F, 0.85F, 1.14F, 0.46F, 1.10F, 2.37F, s,
                    new float[] {0.46F, 0.96F, 0F}, Z, AILERON, -s);
            surface("plane", "elevator" + side, new String[] {"paint"}, -2.09F, 0.64F, 0.08F, -1.93F, 0.75F, 0.83F, s,
                    new float[] {-1.93F, 0.694F, 0F}, Z, ELEVATOR, -1);
        }
        wheel("plane", "tailWheel", new String[] {"rubber", "dark", "accent"}, -1.953F, 0.6F, 0F, 0.1F, 0.1F, -0.04F, 0.04F, 0, 0.084F);
        surface("plane", "rudder", new String[] {"paint"}, -2.34F, 0.80F, -0.05F, -2.12F, 1.58F, 0.05F, 0,
                new float[] {-2.12F, 1.1F, 0F}, Y, RUDDER, 1);
        // ---- jet.obj: main wheels (r 0.124) at x -0.638, y 0.124, z +-0.312 - no visible leg: they rise 0.25 into the
        // wing root; the nose wheel (r 0.104) at x 1.612, y 0.124 on a steel strut (x 1.59..1.63, up to y 0.374) folds back
        // 90 deg about z through the strut top into the nose. Surfaces (cut; the wing is 12 triangles): ailerons on the
        // swept trailing edge (root TE x -1.888 at |z| 0.312, tip TE -2.138 at 2.125) behind the hinge x -1.80 for |z|
        // 1.0..1.9; elevators = the tailplane aft of x -2.60 (|z| 0.31..0.88; the tail cone stays: |z| <= 0.31 there);
        // rudders = the twin canted fins aft of x -2.55 (fins y 0.749..1.561, |z| 0.188..0.438).
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            Def w = wheel("jet", "gearWheel" + side, new String[] {"rubber", "chrome", "dark", "accent"}, -0.638F, 0.124F, s * 0.312F,
                    0.14F, 0.14F, 0.26F, 0.37F, s, 0.124F);
            gear(w, w.pivot, Z, 0F, 0.25F);
            surface("jet", "aileron" + side, new String[] {"paint"}, -2.2F, 0.5F, 1.0F, -1.8F, 0.62F, 1.9F, s,
                    new float[] {-1.8F, 0.561F, 0F}, Z, AILERON, -s);
            surface("jet", "elevator" + side, new String[] {"paint"}, -2.92F, 0.52F, 0.31F, -2.6F, 0.61F, 0.89F, s,
                    new float[] {-2.6F, 0.564F, 0F}, Z, ELEVATOR, -1);
            surface("jet", "rudder" + side, new String[] {"paint"}, -2.85F, 0.76F, 0.17F, -2.55F, 1.57F, 0.46F, s,
                    new float[] {-2.55F, 1.15F, s * 0.3F}, Y, RUDDER, 1);
        }
        Def nose = wheel("jet", "noseWheel", new String[] {"rubber", "chrome", "dark", "accent"}, 1.612F, 0.124F, 0F, 0.12F, 0.12F,
                -0.04F, 0.04F, 0, 0.104F);
        gear(nose, new float[] {1.612F, 0.374F, 0F}, Z, -90F, 0F);
        Def strut = box("jet", "noseLeg", GEAR, new String[] {"steel"}, 1.57F, 0.1F, -0.04F, 1.66F, 0.39F, 0.04F, 0,
                new float[] {1.612F, 0.374F, 0F});
        gear(strut, strut.pivot, Z, -90F, 0F);
        // ---- tilt.obj: main wheels (r 0.156) at x -0.531, y 0.156, z +-0.375 and the nose wheel (r 0.13) at x 1.594, y 0.156
        // - they only spin (no retract on the tiltrotor). Ailerons: the wing's trailing edge (wing x 0.094 TE .. 0.844,
        // y 1.02..1.17, |z| 0.312..2.125) behind x 0.25 for |z| 1.2..2.05; elevator: the H-tail plank (x -2.281..-1.781,
        // y 1.875..1.95, |z| <= 0.75) behind x -2.12; rudder: the fin (x -2.219..-1.406, y 0.906..1.906) behind x -2.0.
        for (int s = -1; s <= 1; s += 2) {
            String side = s > 0 ? "R" : "L";
            wheel("tilt", "gearWheel" + side, new String[] {"rubber", "chrome", "dark", "accent"}, -0.531F, 0.156F, s * 0.375F, 0.17F, 0.17F,
                    0.31F, 0.44F, s, 0.156F);
            surface("tilt", "aileron" + side, new String[] {"white"}, 0.08F, 1.0F, 1.2F, 0.25F, 1.2F, 2.05F, s,
                    new float[] {0.25F, 1.094F, 0F}, Z, AILERON, -s);
        }
        wheel("tilt", "noseWheel", new String[] {"rubber", "chrome", "dark", "accent"}, 1.594F, 0.156F, 0F, 0.145F, 0.145F, -0.07F, 0.07F,
                0, 0.13F);
        surface("tilt", "elevator", new String[] {"white"}, -2.29F, 1.86F, -0.745F, -2.12F, 1.96F, 0.745F, 0,
                new float[] {-2.12F, 1.912F, 0F}, Z, ELEVATOR, -1);
        surface("tilt", "rudder", new String[] {"paint"}, -2.23F, 0.95F, -0.05F, -2.0F, 1.89F, 0.05F, 0,
                new float[] {-2.0F, 1.4F, 0F}, Y, RUDDER, 1);
        // ---- shuttle.obj: four landing legs (steel leg + paint2 foot pad, y 0..0.629) at x -1.772 / 1.103, z +-0.625; they
        // draw up 0.42 into the hull (its bottom at y 0.5) when the gear retracts.
        float[] legs = {-1.772F, 1.103F};
        for (int s = -1; s <= 1; s += 2) {
            for (int i = 0; i < legs.length; i++) {
                Def g = box("shuttle", "leg" + (s > 0 ? "R" : "L") + i, GEAR, new String[] {"steel", "paint2"}, legs[i] - 0.16F, -0.01F, 0.47F,
                        legs[i] + 0.16F, 0.64F, 0.78F, s, new float[] {legs[i], 0.3F, s * 0.625F});
                gear(g, g.pivot, Z, 0F, 0.42F);
                g.minTris = 60;
            }
        }
        for (List<Def> l : TABLE.values()) {
            ALL.addAll(l);
        }
    }

    private AnimParts() {
    }

    /** The animated parts of a model (empty for most). */
    public static List<Def> of(String model) {
        List<Def> l = TABLE.get(model);
        return l == null ? Collections.<Def>emptyList() : l;
    }

    /** Every Def (self-test). */
    public static List<Def> all() {
        return ALL;
    }

    /** A wheel: centre (x, y, z); box x +-dxFront/back, y centre +-dy..., |z| zIn..zOut on side s (0: centred +-zOut). */
    private static Def wheel(String model, String name, String[] groups, float x, float y, float z, float dx, float dy, float zIn, float zOut,
            int s, float radius) {
        Def d = box(model, name, WHEEL, groups, x - dx, y - dy, zIn, x + dx, y + dy, zOut, s, new float[] {x, y, z});
        d.radius = radius;
        d.axis = Z;
        return d;
    }

    private static void gear(Def d, float[] pivot, int axis, float angle, float lift) {
        d.gearPivot = pivot;
        d.gearAxis = axis;
        d.gearAngle = angle;
        d.gearLift = lift;
    }

    private static void surface(String model, String name, String[] groups, float x0, float y0, float z0, float x1, float y1, float z1, int s,
            float[] pivot, int axis, int control, float gain) {
        Def d = box(model, name, SURFACE, groups, x0, y0, z0, x1, y1, z1, s, pivot);
        d.axis = axis;
        d.control = control;
        d.gain = gain;
        d.cut = true;
        d.minTris = 2;
    }

    /**
     * A box part. z0..z1 are given for the RIGHT side (s = 1: z0 <= z <= z1); s = -1 mirrors them (-z1 .. -z0);
     * s = 0: the box is centred, z0..z1 used as they are.
     */
    private static Def box(String model, String name, int kind, String[] groups, float x0, float y0, float z0, float x1, float y1, float z1,
            int s, float[] pivot) {
        float lo = s < 0 ? -z1 : z0, hi = s < 0 ? -z0 : z1;
        Def d = new Def(model, name, kind, groups, new float[] {x0, y0, lo}, new float[] {x1, y1, hi}, pivot);
        List<Def> l = TABLE.get(model);
        if (l == null) {
            l = new ArrayList<Def>();
            TABLE.put(model, l);
        }
        l.add(d);
        return d;
    }

    // ------------------------------------------------------------------ clipping (cut parts)

    /**
     * Splits a convex polygon (vertices {x, y, z, nx, ny, nz, ...}) by the box [min, max]: returns the piece inside (null
     * if none) and adds the pieces outside to `outside` (each convex). Sutherland-Hodgman one plane at a time - what falls
     * outside a plane is set aside, the rest goes on to the next plane - so the pieces partition the polygon exactly.
     */
    public static float[][] clipBox(float[][] poly, float[] min, float[] max, List<float[][]> outside) {
        float[][] cur = poly;
        for (int k = 0; k < 6 && cur != null; k++) {
            int ax = k >> 1;
            boolean low = (k & 1) == 0;
            float edge = low ? min[ax] : max[ax];
            List<float[]> in = new ArrayList<float[]>(), out = new ArrayList<float[]>();
            for (int i = 0; i < cur.length; i++) {
                float[] p = cur[i], q = cur[(i + 1) % cur.length];
                float dp = low ? p[ax] - edge : edge - p[ax], dq = low ? q[ax] - edge : edge - q[ax];
                if (dp >= 0) {
                    in.add(p);
                } else {
                    out.add(p);
                }
                if (dp > 0 && dq < 0 || dp < 0 && dq > 0) {
                    float[] m = lerp(p, q, dp / (dp - dq));
                    in.add(m);
                    out.add(m);
                }
            }
            if (out.size() >= 3 && area(out) > 1e-9F) {
                outside.add(out.toArray(new float[out.size()][]));
            }
            cur = in.size() >= 3 && area(in) > 1e-9F ? in.toArray(new float[in.size()][]) : null;
        }
        return cur;
    }

    private static float[] lerp(float[] p, float[] q, float t) {
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i++) {
            r[i] = p[i] + (q[i] - p[i]) * t;
        }
        if (r.length >= 6) {                                    // the normal stays unit length
            float l = (float) Math.sqrt(r[3] * r[3] + r[4] * r[4] + r[5] * r[5]);
            if (l > 1e-6F) {
                r[3] /= l;
                r[4] /= l;
                r[5] /= l;
            }
        }
        return r;
    }

    /** Area of a planar polygon in 3D (fan of cross products). */
    public static float area(List<float[]> poly) {
        return area(poly.toArray(new float[poly.size()][]));
    }

    public static float area(float[][] poly) {
        double sx = 0, sy = 0, sz = 0;
        float[] a = poly[0];
        for (int i = 1; i + 1 < poly.length; i++) {
            float[] b = poly[i], c = poly[i + 1];
            double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2], vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            sx += uy * vz - uz * vy;
            sy += uz * vx - ux * vz;
            sz += ux * vy - uy * vx;
        }
        return (float) (Math.sqrt(sx * sx + sy * sy + sz * sz) / 2);
    }
}
