package com.sva.debug;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import com.sva.vehicle.CockpitLayout;
import com.sva.vehicle.CockpitView;
import com.sva.vehicle.VehicleLights;
import com.sva.vehicle.VehicleType;

/**
 * Fix 2026-10 (the user's screenshot: the shuttle pilot's first-person view was one dark grey wall): what does a seated
 * rider see in the own first-person view? Pure math over the OBJ, no Minecraft classes - the dedicated server's
 * self-test runs it for every type and seat.
 * <p>
 * The camera: vanilla 1.7.10 (EntityRenderer.orientCamera) puts the first-person camera 0.1 blocks BEHIND the player's
 * eye along the look (glTranslatef(0, 0, -0.1) before the look rotation; CockpitView.CAMERA_BACK), near plane 0.05
 * blocks. The rider-fit pass had put the shuttle pilots' eyes 0.017 blocks in front of their headrests (fabric) - so the
 * camera sat in the headrest and its inside faces filled the screen. The renderer now clips the own vehicle at the eye's
 * depth (CockpitView.EYE_CLIP: nothing of it closer than the eye plane is drawn, RenderVehicleSVA); `clip` here models
 * that (false = the old near plane only).
 * <p>
 * Measured by rays over a 70 deg (vertical) 16:9 view from the camera of each pose (straight ahead, +-45 / +-90 deg to
 * the sides, 30 deg up / down): a ray is BLOCKED when the first opaque surface it meets (the glass is see-through, the
 * shell is cut inside the cockpit windows as the renderer does) is closer than CLOSE blocks to the camera. And every
 * flat overlay drawn in the own view (VehicleLightsSVA's glow sprites, CockpitInstrumentsSVA's lamp / dial halos)
 * is measured as a disc facing the camera: its share of the screen when looked at straight, visible parts only (rays
 * through it that meet the scene behind the sprite's depth).
 */
public final class ViewCheckSVA {

    /** Vertical field of view (deg) and aspect of the measured screen; ray grid. */
    public static final double VFOV = 70, ASPECT = 16.0 / 9.0;
    private static final int NX = 64, NY = 36;
    /** A surface this close to the camera (blocks) counts as a wall in front of the face. */
    public static final double CLOSE = 0.15;
    /** Largest share of the screen a wall in front of the face / one overlay may cover. */
    public static final double LIMIT = 0.05;
    /** Poses {yaw (deg, + = right), pitch (deg, + = down)}. */
    public static final double[][] POSES = {{0, 0}, {0, -30}, {0, 30}, {45, 0}, {-45, 0}, {90, 0}, {-90, 0}};

    private ViewCheckSVA() {
    }

    /** Opaque triangles (9 floats) and whether each is shell (cut inside the cockpit windows). */
    public static final class Mesh {
        public float[][] tris;
        public boolean[] shell;
        /** Each triangle's material (group) - for the logs. */
        public String[] mat;
    }

    /** The triangle the last firstHit call ended on (-1 none; the offline report names its material). */
    static int lastHit = -1;

    /** Every drawn triangle of models/NAME.obj except the see-through glass; null when it can't be read. */
    public static Mesh load(String model) throws java.io.IOException {
        InputStream in = ViewCheckSVA.class.getResourceAsStream("/assets/siliconagevehicles/models/" + model + ".obj");
        if (in == null) {
            return null;
        }
        List<float[]> v = new ArrayList<float[]>();
        List<float[]> tris = new ArrayList<float[]>();
        List<Boolean> shell = new ArrayList<Boolean>();
        List<String> mats = new ArrayList<String>();
        boolean take = true, sh = false;
        String g = "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"), 1 << 16);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("v ")) {
                    String[] p = line.trim().split("\\s+");
                    v.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])});
                } else if (line.startsWith("g ") || line.startsWith("o ") || line.startsWith("usemtl ")) {
                    g = line.substring(line.indexOf(' ') + 1).trim();
                    take = !CockpitView.isGlass(g);
                    sh = CockpitView.isShell(g);
                } else if (take && line.startsWith("f ")) {
                    String[] p = line.substring(2).trim().split("\\s+");
                    float[][] poly = new float[p.length][];
                    for (int i = 0; i < p.length; i++) {
                        poly[i] = v.get(Integer.parseInt(p[i].split("/")[0]) - 1);
                    }
                    for (int i = 1; i + 1 < poly.length; i++) {
                        tris.add(new float[] {poly[0][0], poly[0][1], poly[0][2], poly[i][0], poly[i][1], poly[i][2],
                                poly[i + 1][0], poly[i + 1][1], poly[i + 1][2]});
                        shell.add(Boolean.valueOf(sh));
                        mats.add(g);
                    }
                }
            }
        } finally {
            r.close();
        }
        Mesh m = new Mesh();
        m.tris = tris.toArray(new float[tris.size()][]);
        m.shell = new boolean[m.tris.length];
        m.mat = mats.toArray(new String[mats.size()]);
        for (int i = 0; i < m.shell.length; i++) {
            m.shell[i] = shell.get(i).booleanValue();
        }
        return m;
    }

    /** The look direction (model frame: x forward, y up, z right) of a pose. */
    public static double[] dir(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[] {Math.cos(p) * Math.cos(y), -Math.sin(p), Math.cos(p) * Math.sin(y)};
    }

    /** The camera (model units) of `seat` looking along d: CAMERA_BACK blocks behind the eye. */
    public static double[] camera(VehicleType t, int seat, double[] d) {
        return cameraAt(t, CockpitView.eye(t, seat), d);
    }

    /** The camera for an eye at e (model units). */
    public static double[] cameraAt(VehicleType t, double[] e, double[] d) {
        double b = CockpitView.CAMERA_BACK / t.modelScale;
        return new double[] {e[0] - d[0] * b, e[1] - d[1] * b, e[2] - d[2] * b};
    }

    /** Screen frame {forward, right, up} for a look direction. */
    private static double[][] frame(double[] f) {
        double[] right = norm(new double[] {-f[2], 0, f[0]});
        if (len(right) < 1e-9) {
            right = new double[] {0, 0, 1};
        }
        double[] up = cross(right, f);
        return new double[][] {f, right, up};
    }

    /**
     * Distance (blocks) along the ray from cam (model units) along unit u to the first opaque surface whose depth along
     * the view axis f is at least `near` blocks (the near plane / the eye clip), or +inf.
     */
    static double firstHit(Mesh m, int[] idx, float[][] windows, double s, double[] cam, double[] u, double[] f, double near) {
        double best = Double.POSITIVE_INFINITY;
        double cosA = dot(u, f);
        lastHit = -1;
        for (int ii : idx) {
            float[] t = m.tris[ii];
            double e1x = t[3] - t[0], e1y = t[4] - t[1], e1z = t[5] - t[2];
            double e2x = t[6] - t[0], e2y = t[7] - t[1], e2z = t[8] - t[2];
            double px = u[1] * e2z - u[2] * e2y, py = u[2] * e2x - u[0] * e2z, pz = u[0] * e2y - u[1] * e2x;
            double det = e1x * px + e1y * py + e1z * pz;
            if (Math.abs(det) < 1e-12) {
                continue;
            }
            double inv = 1 / det;
            double sx = cam[0] - t[0], sy = cam[1] - t[1], sz = cam[2] - t[2];
            double a = (sx * px + sy * py + sz * pz) * inv;
            if (a < 0 || a > 1) {
                continue;
            }
            double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
            double b = (u[0] * qx + u[1] * qy + u[2] * qz) * inv;
            if (b < 0 || a + b > 1) {
                continue;
            }
            double dist = (e2x * qx + e2y * qy + e2z * qz) * inv;          // model units along u
            if (dist <= 0 || dist * s >= best || dist * cosA * s < near) {
                continue;
            }
            if (m.shell[ii] && windows != null
                    && !CockpitView.shellKept(windows, cam[0] + u[0] * dist, cam[1] + u[1] * dist, cam[2] + u[2] * dist)) {
                continue;                                                    // cut out: a cockpit window
            }
            best = dist * s;
            lastHit = ii;
        }
        return best;
    }

    /** Triangles whose bounding box comes within r (model units) of p. */
    static int[] near(Mesh m, double[] p, double r) {
        List<Integer> l = new ArrayList<Integer>();
        for (int i = 0; i < m.tris.length; i++) {
            float[] t = m.tris[i];
            double d2 = 0;
            for (int a = 0; a < 3; a++) {
                double lo = Math.min(t[a], Math.min(t[a + 3], t[a + 6])), hi = Math.max(t[a], Math.max(t[a + 3], t[a + 6]));
                double g = p[a] < lo ? lo - p[a] : p[a] > hi ? p[a] - hi : 0;
                d2 += g * g;
            }
            if (d2 <= r * r) {
                l.add(Integer.valueOf(i));
            }
        }
        int[] o = new int[l.size()];
        for (int i = 0; i < o.length; i++) {
            o[i] = l.get(i).intValue();
        }
        return o;
    }

    /** The unit ray through screen point (sx, sy) in -1..1 (x right, y up). */
    private static double[] ray(double[][] fr, double sx, double sy) {
        double th = Math.tan(Math.toRadians(VFOV / 2));
        double[] u = new double[3];
        for (int a = 0; a < 3; a++) {
            u[a] = fr[0][a] + fr[1][a] * sx * th * ASPECT + fr[2][a] * sy * th;
        }
        return norm(u);
    }

    /**
     * The share of the screen covered by a wall in front of the face (closer than CLOSE blocks), for `seat` in the pose,
     * with the eye clip (`clip`) or without (the near plane only).
     */
    public static double blocked(VehicleType t, Mesh m, int seat, double yaw, double pitch, boolean clip) {
        return blocked(t, m, seat, yaw, pitch, clip, null);
    }

    /** As above; the blocking surfaces' materials go to `mats` (one per blocked ray) when it is not null. */
    public static double blocked(VehicleType t, Mesh m, int seat, double yaw, double pitch, boolean clip, java.util.Collection<String> mats) {
        return blocked(t, m, seat, yaw, pitch, clip ? CockpitView.EYE_CLIP : CockpitView.NEAR, CLOSE, mats);
    }

    /** As above with the given clip depth and wall distance (blocks from the camera). */
    public static double blocked(VehicleType t, Mesh m, int seat, double yaw, double pitch, double near, double close,
            java.util.Collection<String> mats) {
        return blockedAt(t, m, CockpitView.eye(t, seat), yaw, pitch, near, close, mats);
    }

    /** As above for an eye at e (model units). */
    public static double blockedAt(VehicleType t, Mesh m, double[] e, double yaw, double pitch, double near, double close,
            java.util.Collection<String> mats) {
        double[] f = dir(yaw, pitch);
        double[] cam = cameraAt(t, e, f);
        double s = t.modelScale;
        int[] idx = near(m, cam, (close + 0.02) / s);
        if (idx.length == 0) {
            return 0;
        }
        double[][] fr = frame(f);
        float[][] w = t.openCab ? null : t.ownViewCuts();
        int hit = 0;
        for (int j = 0; j < NY; j++) {
            for (int i = 0; i < NX; i++) {
                double[] u = ray(fr, (i + 0.5) * 2 / NX - 1, 1 - (j + 0.5) * 2 / NY);
                if (firstHit(m, idx, w, s, cam, u, f, near) < close) {
                    hit++;
                    if (mats != null) {
                        mats.add(m.mat[lastHit]);
                    }
                }
            }
        }
        return hit / (double) (NX * NY);
    }

    /** An overlay drawn in the own view: a camera-facing disc (model units) and what it is. */
    public static final class Overlay {
        public final String what;
        public final double[] c;
        public final double r;
        /** Pulled this many blocks toward the camera (VehicleLightsSVA's glows outside the own view). */
        public final double pull;

        Overlay(String what, double[] c, double r, double pull) {
            this.what = what;
            this.c = c;
            this.r = r;
            this.pull = pull;
        }
    }

    /**
     * The own-view overlays of a type as they are drawn now: every light's glow (its largest size, VehicleLightsSVA via
     * VehicleLights.glowSize; in the own view not pulled toward the camera and at most CockpitView.GLOW_MAX_ANGLE wide),
     * the lamps' halos (CockpitLamps: 3 x the lamp's radius) and the dials' halos (1.5 x).
     */
    public static List<Overlay> overlays(VehicleType t, double[] cam, boolean fixed) {
        List<Overlay> l = new ArrayList<Overlay>();
        double s = t.modelScale;
        for (VehicleLights.Light g : VehicleLights.of(t.model)) {
            double[] c = {g.x, g.y, g.z};
            double d = len(sub(c, cam)) * s, size = VehicleLights.glowSize(g.kind);
            if (fixed) {
                l.add(new Overlay("glow " + VehicleLights.NAMES[g.kind], c, CockpitView.ownGlow(size, d) / s, 0));
            } else {
                l.add(new Overlay("glow " + VehicleLights.NAMES[g.kind], c, size / s, d > 0.5 ? 0.25 : 0));   // before the fix
            }
        }
        CockpitLayout cl = CockpitLayout.of(t);
        for (CockpitLayout.Lamp p : cl.lamps) {
            double d = len(sub(f2d(p.c), cam));
            l.add(new Overlay("lamp halo", f2d(p.c), fixed ? CockpitView.lampHalo(p.r, d) : p.r * 3, 0));
        }
        for (CockpitLayout.Lamp p : cl.okLamps) {
            double d = len(sub(f2d(p.c), cam));
            l.add(new Overlay("gear lamp halo", f2d(p.c), fixed ? CockpitView.lampHalo(p.r, d) : p.r * 3, 0));
        }
        for (CockpitLayout.Dial p : cl.dials) {
            l.add(new Overlay("dial halo", f2d(p.c), p.r * 1.5, 0));
        }
        return l;
    }

    /**
     * Screen share of an overlay for `seat`: the camera looks straight at it (the worst case); only the parts not hidden
     * behind the scene count (rays through the disc whose first opaque hit lies beyond the disc's depth). Returns
     * {share, distance from the camera (blocks)}; the share is 1 when the disc crosses the near plane round the camera.
     */
    public static double[] overlayShare(VehicleType t, Mesh m, int seat, Overlay o, boolean clip) {
        double s = t.modelScale;
        double[] e = CockpitView.eye(t, seat);
        double[] f = norm(sub(o.c, e));
        double[] cam = camera(t, seat, f);
        double[] to = sub(o.c, cam);
        double d = len(to) * s;
        f = norm(to);
        double depth = d - o.pull;
        double r = o.r * s;
        if (depth - CockpitView.NEAR < 1e-3 || d < 1e-6) {
            return new double[] {1, d};
        }
        double th = Math.tan(Math.toRadians(VFOV / 2));
        double rho = r / depth;                                    // disc radius on the unit-depth screen
        double[][] fr = frame(f);
        int[] idx = null;
        int cover = 0, n = 0;
        for (int j = 0; j < NY; j++) {
            for (int i = 0; i < NX; i++) {
                double sx = ((i + 0.5) * 2 / NX - 1) * th * ASPECT, sy = (1 - (j + 0.5) * 2 / NY) * th;
                n++;
                if (sx * sx + sy * sy > rho * rho) {
                    continue;
                }
                if (idx == null) {
                    idx = allIdx(m);
                }
                double[] u = ray(fr, sx / (th * ASPECT), sy / th);
                double hit = firstHit(m, idx, t.openCab ? null : t.ownViewCuts(), s, cam, u, f, clip ? CockpitView.EYE_CLIP : CockpitView.NEAR);
                if (hit * dot(u, f) > depth) {
                    cover++;
                }
            }
        }
        return new double[] {cover / (double) n, d};
    }

    private static int[] allIdx(Mesh m) {
        int[] o = new int[m.tris.length];
        for (int i = 0; i < o.length; i++) {
            o[i] = i;
        }
        return o;
    }

    private static double[] f2d(float[] a) {
        return new double[] {a[0], a[1], a[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double len(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] norm(double[] a) {
        double l = len(a);
        return l < 1e-12 ? a : new double[] {a[0] / l, a[1] / l, a[2] / l};
    }
}
