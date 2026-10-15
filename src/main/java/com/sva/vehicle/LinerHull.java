package com.sva.vehicle;

/**
 * The SA-320's hull for clicks (the user's «с любой стороны открыть и убрать в инвентарь»): the liner has no ObjModelSVA
 * model (its own pipeline - LinerModelSVA), so VehiclePickSVA found no model box and only the entity's 3-block square at
 * the centre was clickable; the server's reach checks measured to the centre too (a wing tip is 8.8 blocks out). These
 * boxes, measured from liner_lod0.obj's groups (model units = blocks; x forward, y up from the wheels, z right - the frame
 * of LinerGroundSVA.DOORS), are the airframe a click / a reach is tested against: the fuselage, each wing in two spans
 * (the sweep), the nacelles, the gear, the tailplane and the fin. Level only (pitch / roll ignored, as VehiclePickSVA).
 */
public final class LinerHull {

    private LinerHull() {
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ} per part (PARTS' order). */
    public static final double[][] BOXES = {
            {-9.25, 0.70, -1.27, 9.25, 3.66, 1.27},             // fuselage (airframe)
            {-2.20, 1.00, -4.50, 2.30, 2.20, 0.00},             // wing L, inner span
            {-2.20, 1.00, 0.00, 2.30, 2.20, 4.50},              // wing R, inner span
            {-2.75, 1.40, -8.80, 0.30, 2.90, -4.50},            // wing L, outer span (the sweep) + the wing tip
            {-2.75, 1.40, 4.50, 0.30, 2.90, 8.80},              // wing R, outer span
            {-0.90, 0.35, -3.30, 2.10, 1.40, -2.20},            // nacelle L
            {-0.90, 0.35, 2.20, 2.10, 1.40, 3.30},              // nacelle R
            {-1.00, 0.00, -2.55, -0.20, 1.40, -1.10},           // main gear L (+ its door)
            {-1.00, 0.00, 1.10, -0.20, 1.40, 2.55},             // main gear R
            {6.10, 0.00, -0.26, 7.90, 1.40, 0.26},              // nose gear (+ its doors)
            {-8.65, 2.50, -3.02, -6.20, 3.00, 3.02},            // the tailplane (THS + elevators)
            {-7.55, 2.90, -0.30, -4.45, 5.70, 0.30}};           // the fin + rudder
    public static final String[] PARTS = {"fuselage", "wingL", "wingR", "wingTipL", "wingTipR", "nacelleL", "nacelleR", "mainGearL",
            "mainGearR", "noseGear", "tailplane", "fin"};
    /** The overall bounds (VehiclePickSVA's coarse test, the search radius): x, y, z min / max. */
    public static final double[] BOUNDS = {-9.25, 0.0, -8.80, 9.25, 5.70, 8.80};

    /** A world offset (from the entity's position) into the model frame {x forward, y up, z right} at this yaw (deg). */
    public static double[] local(double dx, double dy, double dz, float yawDeg) {
        double r = Math.toRadians(yawDeg);
        double s = Math.sin(r), c = Math.cos(r);
        // EntityVehicleSVA.toWorld: dx = -s fx - c fz, dz = c fx - s fz - its inverse
        return new double[] {-s * dx + c * dz, dy, -c * dx - s * dz};
    }

    /** The squared distance from a model-frame point to the nearest part (0 inside one). */
    public static double distSq(double x, double y, double z) {
        double best = Double.MAX_VALUE;
        for (double[] b : BOXES) {
            double ex = Math.max(0, Math.max(b[0] - x, x - b[3]));
            double ey = Math.max(0, Math.max(b[1] - y, y - b[4]));
            double ez = Math.max(0, Math.max(b[2] - z, z - b[5]));
            best = Math.min(best, ex * ex + ey * ey + ez * ez);
        }
        return best;
    }

    /**
     * The look ray (model frame: from, the unit direction) against the parts: the distance along it to the first part
     * within maxLen (0: the eye inside one), or -1. The part's index goes to hitPart[0] when given.
     */
    public static double ray(double[] from, double[] dir, double maxLen, int[] hitPart) {
        double best = -1;
        for (int k = 0; k < BOXES.length; k++) {
            double t = slab(BOXES[k], from, dir);
            if (t >= 0 && t <= maxLen && (best < 0 || t < best)) {
                best = t;
                if (hitPart != null && hitPart.length > 0) {
                    hitPart[0] = k;
                }
            }
        }
        return best;
    }

    /** The ray's entry into one box (0 when it starts inside), -1 a miss / behind. */
    static double slab(double[] b, double[] o, double[] d) {
        double t0 = 0, t1 = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            double lo = b[i], hi = b[i + 3];
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo || o[i] > hi) {
                    return -1;
                }
                continue;
            }
            double a = (lo - o[i]) / d[i], c = (hi - o[i]) / d[i];
            if (a > c) {
                double x = a;
                a = c;
                c = x;
            }
            t0 = Math.max(t0, a);
            t1 = Math.min(t1, c);
            if (t0 > t1) {
                return -1;
            }
        }
        return t0;
    }
}
