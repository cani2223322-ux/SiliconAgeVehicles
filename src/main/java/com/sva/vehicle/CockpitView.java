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

    private CockpitView() {
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
