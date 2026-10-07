package com.sva.vehicle;

import java.util.ArrayList;
import java.util.List;

/**
 * The cockpit-window cut (VehicleType.cabWindows) as pure math, shared by the renderer (ObjModelSVA's clip planes) and
 * the self-test. No Minecraft classes.
 */
public final class CockpitView {

    /** At most this many window boxes per model (each takes one GL clip plane; GL guarantees 6). */
    public static final int MAX_WINDOWS = 3;
    /** The seated player's eye over seatY (+ seatDy), blocks (the 1.7.10 player model sitting). */
    public static final double EYE_OVER_SEAT = 1.12;

    /**
     * Fix 2026-10 (the shuttle pilot saw one dark grey wall): vanilla 1.7.10 puts the first-person camera this far
     * (blocks) BEHIND the player's eye along the look (EntityRenderer.orientCamera: glTranslatef(0, 0, -0.1) before the
     * look rotation), with the near plane NEAR in front of it. The rider-fit pass had set the shuttle pilots' eyes 0.017
     * blocks in front of their headrests - the camera sat inside the headrest and its fabric filled the screen.
     */
    public static final double CAMERA_BACK = 0.1, NEAR = 0.05;
    /**
     * The own first-person view clips the own vehicle at this depth from the camera (blocks; RenderVehicleSVA, a GL clip
     * plane in eye space) - the plane through the player's eye: nothing of the vehicle behind the eye (a headrest, the
     * cabin wall right behind the head) can cover the view, whatever the seat. In front of the eye nothing changes.
     */
    public static final double EYE_CLIP = CAMERA_BACK;
    /** The own view's glow sprites (VehicleLightsSVA) and lamp halos (CockpitInstrumentsSVA): at most this wide (radius / distance). */
    public static final double GLOW_MAX = 0.12, HALO_MAX = 0.12;

    private CockpitView() {
    }

    /**
     * A glow sprite's radius (blocks) in the own first-person view: `size` at most GLOW_MAX x its distance `d` from the
     * camera - a landing light 1.6 blocks ahead of the windscreen stays a glow, not a 30 deg blob over the view.
     */
    public static double ownGlow(double size, double d) {
        return Math.min(size, GLOW_MAX * d);
    }

    /**
     * A cockpit lamp's halo radius (model units) for a lamp of radius r at distance d from the eye: 3 r, at most HALO_MAX x
     * d (the shuttle's overhead lamps sit 0.2 from the pilot's head).
     */
    public static double lampHalo(double r, double d) {
        return Math.max(r * 1.2, Math.min(r * 3, HALO_MAX * d));
    }

    /** See-through groups (drawn translucent; ObjModelSVA.isGlass). */
    public static boolean isGlass(String group) {
        return "glass".equals(group) || "canopy".equals(group);
    }

    /**
     * Body shell groups (the painted hull: paint, paint2, paint3, white and the shuttle's lit side windows; tiles / nb kept
     * for future models): the cockpit-window clip cuts only these (ObjModelSVA.isShell).
     */
    public static boolean isShell(String group) {
        return "paint".equals(group) || "paint2".equals(group) || "paint3".equals(group) || "white".equals(group)
                || "tiles".equals(group) || "nb".equals(group) || "window".equals(group);
    }

    /**
     * The outer half-spaces of a window box {x0, x1, y0, y1, z0, z1} as clip-plane equations {a, b, c, d}: kept where
     * a x + b y + c z + d >= 0. A side at -+VehicleType.OPEN has none.
     */
    public static double[][] outerPlanes(float[] w) {
        List<double[]> l = new ArrayList<double[]>();
        for (int a = 0; a < 3; a++) {
            float lo = w[a * 2], hi = w[a * 2 + 1];
            if (lo > -VehicleType.OPEN) {
                double[] p = new double[4];
                p[a] = -1;
                p[3] = lo;                                      // axis < lo
                l.add(p);
            }
            if (hi < VehicleType.OPEN) {
                double[] p = new double[4];
                p[a] = 1;
                p[3] = -hi;                                     // axis > hi
                l.add(p);
            }
        }
        return l.toArray(new double[l.size()][]);
    }

    /**
     * Is the model-space point drawn by the clipped shell? The renderer's passes take one outer half-space of every box
     * (one clip plane per box) - their union is the points outside every box.
     */
    public static boolean shellKept(float[][] windows, double x, double y, double z) {
        for (int b = 0; b < Math.min(windows.length, MAX_WINDOWS); b++) {
            boolean outside = false;
            for (double[] p : outerPlanes(windows[b])) {
                if (p[0] * x + p[1] * y + p[2] * z + p[3] > 0) {
                    outside = true;
                    break;
                }
            }
            if (!outside) {
                return false;
            }
        }
        return true;
    }

    /** The eye of `seat` in model units {x, y, z} (seat offset / modelScale, + modelOffsetX; seatY + seatDy + eye). */
    public static double[] eye(VehicleType t, int seat) {
        double[] o = t.seatOffsets[seat];
        return new double[] {o[0] / t.modelScale + t.modelOffsetX, (t.seatY + t.seatDy(seat) + EYE_OVER_SEAT) / t.modelScale,
                o[1] / t.modelScale};
    }

    /** Is the point inside the box (open sides endless)? */
    public static boolean inside(float[] w, double x, double y, double z) {
        return x > w[0] && x < w[1] && y > w[2] && y < w[3] && z > w[4] && z < w[5];
    }
}
